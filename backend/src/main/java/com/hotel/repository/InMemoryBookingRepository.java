package com.hotel.repository;

import com.hotel.exception.BookingConflictException;
import com.hotel.exception.BookingValidationException;
import com.hotel.model.Booking;
import com.hotel.model.BookingStatus;
import com.hotel.model.InventoryStatus;
import com.hotel.model.PaymentMethod;
import com.hotel.model.RoomInventory;
import com.hotel.util.PhoneNormalizer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LOCAL DEVELOPMENT ONLY (Spring profile "local"): keeps every booking in memory so the backend can run on a
 * laptop with no AWS account, credentials or DynamoDB table. Data is lost when the process stops.
 * It mirrors the conditional-write semantics of {@link DynamoDbBookingRepository} (same conflict / expiry /
 * UTR-claim rules) and is never active in the deployed Lambda (profile "lambda").
 *
 * Start it with:  .\mvnw spring-boot:run "-Dspring-boot.run.profiles=local"
 */
@Repository
@Profile("local")
public class InMemoryBookingRepository implements BookingRepository {
    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private final Map<String, RoomInventory> inventories = new ConcurrentHashMap<>();
    /** UTR claims, mirroring the "UTR#<utr>" claim items of DynamoDbBookingRepository. */
    private final Set<String> utrClaims = ConcurrentHashMap.newKeySet();
    /** Mirrors the "RECON#PENDING" marker rows: UPI payments staff have not reconciled yet. */
    private final Set<String> pendingReconciliation = new LinkedHashSet<>();

    private String invKey(int roomNumber, LocalDate date) {
        return roomNumber + "#" + date.toString();
    }

    @Override
    public synchronized void holdRoom(Booking booking, List<RoomInventory> items, long nowEpochSeconds) {
        List<LocalDate> conflictingDates = new ArrayList<>();
        for (RoomInventory item : items) {
            String key = invKey(item.roomNumber(), item.date());
            RoomInventory existing = inventories.get(key);
            if (existing != null && existing.isCurrentlyLocked(nowEpochSeconds)) {
                conflictingDates.add(item.date());
            }
        }

        if (!conflictingDates.isEmpty()) {
            throw new BookingConflictException(
                    "Room " + booking.roomNumber() + " is already reserved or held for requested dates",
                    conflictingDates
            );
        }

        bookings.put(booking.bookingId(), booking);
        for (RoomInventory item : items) {
            inventories.put(invKey(item.roomNumber(), item.date()), item);
        }
    }

    @Override
    public synchronized void confirmBooking(Booking booking, List<RoomInventory> items, long nowEpochSeconds) {
        Booking existing = bookings.get(booking.bookingId());
        if (existing == null || existing.isHoldExpired(nowEpochSeconds)) {
            throw new BookingValidationException("Booking hold has expired");
        }
        if (booking.paymentMethod() == PaymentMethod.UPI_QR && booking.paymentReference() != null
                && !utrClaims.add(booking.paymentReference())) {
            throw new BookingValidationException(
                    "This UTR / transaction reference has already been used for another booking.");
        }
        bookings.put(booking.bookingId(), booking);
        for (RoomInventory item : items) {
            inventories.put(invKey(item.roomNumber(), item.date()), item);
        }
        if (booking.paymentMethod() == PaymentMethod.UPI_QR && booking.paymentReference() != null) {
            pendingReconciliation.add(booking.bookingId());
        }
    }

    @Override
    public synchronized void extendBooking(Booking updatedBooking, List<RoomInventory> extensionInventories, long nowEpochSeconds) {
        List<LocalDate> conflictingDates = new ArrayList<>();
        for (RoomInventory item : extensionInventories) {
            String key = invKey(item.roomNumber(), item.date());
            RoomInventory existing = inventories.get(key);
            if (existing != null && existing.isCurrentlyLocked(nowEpochSeconds)) {
                conflictingDates.add(item.date());
            }
        }

        if (!conflictingDates.isEmpty()) {
            throw new BookingConflictException("Room unavailable for requested extension period", conflictingDates);
        }

        bookings.put(updatedBooking.bookingId(), updatedBooking);
        for (RoomInventory item : extensionInventories) {
            inventories.put(invKey(item.roomNumber(), item.date()), item);
        }
    }

    @Override
    public synchronized void recordPayment(Booking updated, BigDecimal expectedPreviousAmountPaid, long nowEpochSeconds) {
        Booking existing = bookings.get(updated.bookingId());
        if (existing == null || !existing.isLive()
                || existing.amountPaid().compareTo(expectedPreviousAmountPaid) != 0) {
            throw new BookingValidationException("Booking changed while the payment was being recorded.");
        }
        String ref = updated.paymentReference();
        String utr = ref.substring(ref.lastIndexOf(',') + 1);
        if (!utrClaims.add(utr)) {
            throw new BookingValidationException(
                    "This UTR / transaction reference has already been used for another booking.");
        }
        bookings.put(updated.bookingId(), updated);
        pendingReconciliation.add(updated.bookingId());
    }

    @Override
    public synchronized void cancelBooking(Booking cancelled, List<RoomInventory> items, long nowEpochSeconds) {
        Booking existing = bookings.get(cancelled.bookingId());
        if (existing == null || existing.status() != BookingStatus.CONFIRMED) {
            throw new BookingValidationException("Booking could not be cancelled (its status changed).");
        }
        for (RoomInventory item : items) {
            String key = invKey(item.roomNumber(), item.date());
            RoomInventory row = inventories.get(key);
            if (row != null && row.bookingId().equals(cancelled.bookingId())) {
                inventories.remove(key);   // only ever delete this booking's own rows
            }
        }
        bookings.put(cancelled.bookingId(), cancelled);
    }

    @Override
    public synchronized void releaseHold(Booking releasedBooking, List<RoomInventory> items, long nowEpochSeconds) {
        Booking existing = bookings.get(releasedBooking.bookingId());
        if (existing == null || existing.status() != BookingStatus.HOLD) {
            throw new BookingValidationException("Booking can no longer be released (its status has changed).");
        }
        // Mirrors DynamoDbBookingRepository: only a still-live hold owns its rows outright. An
        // expired hold's rows may already belong to another guest, so leave the inventory alone.
        if (nowEpochSeconds < releasedBooking.expiresAt()) {
            for (RoomInventory item : items) {
                String key = invKey(item.roomNumber(), item.date());
                RoomInventory row = inventories.get(key);
                if (row != null && row.bookingId().equals(releasedBooking.bookingId())) {
                    inventories.remove(key);
                }
            }
        }
        bookings.put(releasedBooking.bookingId(), releasedBooking);
    }

    @Override
    public Optional<Booking> findBookingById(String bookingId) {
        return Optional.ofNullable(bookings.get(bookingId));
    }

    @Override
    public synchronized List<Booking> findBookingsByGuestPhone(String guestPhone) {
        if (guestPhone == null || guestPhone.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<Booking> matches = new ArrayList<>();
        for (Booking b : bookings.values()) {
            if (PhoneNormalizer.forStored(guestPhone).equals(PhoneNormalizer.forStored(b.guestPhone()))) {
                matches.add(b);
            }
        }
        matches.sort(Comparator.comparing(Booking::checkInDate).reversed());
        return matches;
    }

    @Override
    public List<RoomInventory> findInventoriesForRoomAndDates(int roomNumber, List<LocalDate> dates) {
        List<RoomInventory> result = new ArrayList<>();
        for (LocalDate d : dates) {
            RoomInventory inv = inventories.get(invKey(roomNumber, d));
            if (inv != null) {
                result.add(inv);
            }
        }
        return result;
    }

    @Override
    public List<RoomInventory> findInventoriesForDates(List<LocalDate> dates) {
        List<RoomInventory> result = new ArrayList<>();
        for (RoomInventory inv : inventories.values()) {
            if (dates.contains(inv.date())) {
                result.add(inv);
            }
        }
        return result;
    }

    // ---- Phase 4: front-desk operations (same semantics as DynamoDbBookingRepository) ----

    @Override
    public synchronized List<Booking> findBookingsByIds(Collection<String> bookingIds) {
        List<Booking> result = new ArrayList<>();
        for (String id : new LinkedHashSet<>(bookingIds)) {
            Booking b = bookings.get(id);
            if (b != null) {
                result.add(b);
            }
        }
        return result;
    }

    @Override
    public synchronized List<String> findPendingReconciliationIds() {
        return new ArrayList<>(pendingReconciliation);
    }

    @Override
    public synchronized void checkIn(Booking updated) {
        Booking existing = bookings.get(updated.bookingId());
        if (existing == null || existing.status() != BookingStatus.CONFIRMED) {
            throw new BookingValidationException("Booking can no longer be checked in (its status changed).");
        }
        bookings.put(updated.bookingId(), existing.toBuilder()
                .status(BookingStatus.CHECKED_IN).checkedInAt(updated.checkedInAt()).build());
    }

    @Override
    public synchronized void checkOut(Booking updated, List<RoomInventory> released, long nowEpochSeconds) {
        Booking existing = bookings.get(updated.bookingId());
        if (existing == null || existing.status() != BookingStatus.CHECKED_IN) {
            throw new BookingValidationException("Booking could not be checked out (its status changed).");
        }
        for (RoomInventory item : released) {
            String key = invKey(item.roomNumber(), item.date());
            RoomInventory row = inventories.get(key);
            if (row != null && row.bookingId().equals(updated.bookingId())) {
                inventories.remove(key);
            }
        }
        bookings.put(updated.bookingId(), existing.toBuilder()
                .status(BookingStatus.CHECKED_OUT).checkedOutAt(updated.checkedOutAt())
                .checkOutDate(updated.checkOutDate()).ttl(updated.ttl()).build());
    }

    @Override
    public synchronized void recordStaffPayment(Booking updated, BigDecimal expectedPreviousAmountPaid) {
        Booking existing = bookings.get(updated.bookingId());
        if (existing == null || !existing.isLive()
                || existing.amountPaid().compareTo(expectedPreviousAmountPaid) != 0) {
            throw new BookingValidationException("Booking changed while the payment was being recorded.");
        }
        bookings.put(updated.bookingId(), existing.toBuilder()
                .amountPaid(updated.amountPaid()).paymentStatus(updated.paymentStatus())
                .paymentVerifiedAt(updated.paymentVerifiedAt()).paidAt(updated.paidAt()).build());
        pendingReconciliation.remove(updated.bookingId());
    }

    @Override
    public synchronized void createDirectBooking(Booking booking, List<RoomInventory> items, long nowEpochSeconds) {
        List<LocalDate> conflicts = new ArrayList<>();
        for (RoomInventory item : items) {
            RoomInventory existing = inventories.get(invKey(item.roomNumber(), item.date()));
            if (existing != null && existing.isCurrentlyLocked(nowEpochSeconds)) {
                conflicts.add(item.date());
            }
        }
        if (!conflicts.isEmpty()) {
            throw new BookingConflictException(
                    "Room " + booking.roomNumber() + " is not available for the requested dates", conflicts);
        }
        bookings.put(booking.bookingId(), booking);
        for (RoomInventory item : items) {
            inventories.put(invKey(item.roomNumber(), item.date()), item);
        }
    }

    @Override
    public synchronized void blockRoom(List<RoomInventory> items, long nowEpochSeconds) {
        List<LocalDate> conflicts = new ArrayList<>();
        for (RoomInventory item : items) {
            RoomInventory existing = inventories.get(invKey(item.roomNumber(), item.date()));
            if (existing != null && existing.isCurrentlyLocked(nowEpochSeconds)) {
                conflicts.add(item.date());
            }
        }
        if (!conflicts.isEmpty()) {
            throw new BookingConflictException("Room cannot be blocked: booked, held or already blocked", conflicts);
        }
        for (RoomInventory item : items) {
            inventories.put(invKey(item.roomNumber(), item.date()), item);
        }
    }

    @Override
    public synchronized int unblockRoom(int roomNumber, List<LocalDate> dates) {
        int released = 0;
        for (LocalDate d : dates) {
            RoomInventory row = inventories.get(invKey(roomNumber, d));
            if (row != null && row.status() == InventoryStatus.MAINTENANCE) {
                inventories.remove(invKey(roomNumber, d));
                released++;
            }
        }
        return released;
    }
}

package com.hotel;

import com.hotel.dto.ConfirmBookingRequest;
import com.hotel.dto.ExtendBookingRequest;
import com.hotel.dto.HoldBookingRequest;
import com.hotel.dto.BookingResponse;
import com.hotel.dto.BookingSummaryResponse;
import com.hotel.util.PhoneNormalizer;
import com.hotel.dto.RoomAvailabilityResponse;
import com.hotel.exception.BookingConflictException;
import com.hotel.exception.BookingValidationException;
import com.hotel.model.*;
import com.hotel.repository.BookingRepository;
import com.hotel.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BookingConcurrencyTest {

    private InMemoryBookingRepository repository;
    private MutableClock testClock;
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        repository = new InMemoryBookingRepository();
        // Fixed initial time: 2026-10-01 10:00:00 UTC
        Instant initialInstant = Instant.parse("2026-10-01T10:00:00Z");
        testClock = new MutableClock(initialInstant, ZoneOffset.UTC);
        com.hotel.security.BookingTokenService tokenService = new com.hotel.security.BookingTokenService("");
        bookingService = new BookingService(repository, testClock, tokenService);
    }

    @Test
    @DisplayName("1. Simultaneous hold attempts for the same room and overlapping dates: one succeeds, second rejected with 409 Conflict")
    void testOverlappingHoldCollision() {
        LocalDate checkIn = LocalDate.of(2026, 10, 5);
        LocalDate checkOut = LocalDate.of(2026, 10, 8); // 3 nights

        HoldBookingRequest req1 = new HoldBookingRequest(
                101, checkIn, checkOut, 2, "Alice", "+919876543210", "alice@example.com"
        );
        BookingResponse hold1 = bookingService.holdRoom(req1);
        assertNotNull(hold1.bookingId());
        assertEquals(BookingStatus.HOLD, hold1.status());
        assertEquals(600L, hold1.remainingHoldSeconds());

        // Attempt 2: Same room, overlapping date range (Oct 7 - Oct 10)
        HoldBookingRequest req2 = new HoldBookingRequest(
                101, LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 10),
                2, "Bob", "+919876543211", "bob@example.com"
        );

        BookingConflictException ex = assertThrows(BookingConflictException.class, () -> {
            bookingService.holdRoom(req2);
        });

        assertTrue(ex.getMessage().contains("already reserved or held"));
        assertTrue(ex.getConflictingDates().contains(LocalDate.of(2026, 10, 7)));
    }

    @Test
    @DisplayName("2. Multi-threaded race condition: 10 concurrent threads attempt to hold the same room for the same date")
    void testConcurrentThreadRaceCondition() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        LocalDate checkIn = LocalDate.of(2026, 11, 1);
        LocalDate checkOut = LocalDate.of(2026, 11, 3);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int guestId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    bookingService.holdRoom(new HoldBookingRequest(
                            103, checkIn, checkOut, 2, "Guest-" + guestId,
                            String.format("+91980000%04d", guestId), "guest" + guestId + "@example.com"
                    ));
                    successCount.incrementAndGet();
                } catch (BookingConflictException e) {
                    conflictCount.incrementAndGet();
                } catch (Exception e) {
                    fail("Unexpected exception: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Fire all threads simultaneously
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        // Exactly 1 thread must win the hold; exactly 9 threads must be rejected with Conflict
        assertEquals(1, successCount.get(), "Exactly one hold must succeed");
        assertEquals(9, conflictCount.get(), "All other concurrent attempts must fail with conflict");
    }

    @Test
    @DisplayName("3. Expired hold overwrite: new hold succeeds after 10-minute expiration has elapsed")
    void testExpiredHoldOverwriting() {
        LocalDate checkIn = LocalDate.of(2026, 10, 15);
        LocalDate checkOut = LocalDate.of(2026, 10, 17);

        // User A holds Room 102
        HoldBookingRequest reqA = new HoldBookingRequest(
                102, checkIn, checkOut, 2, "User A", "+919111111111", "usera@example.com"
        );
        BookingResponse holdA = bookingService.holdRoom(reqA);
        assertEquals(BookingStatus.HOLD, holdA.status());

        // Advance time by 601 seconds (10 minutes + 1 second)
        testClock.plusSeconds(601);

        // User B attempts to hold the same room for the same dates
        HoldBookingRequest reqB = new HoldBookingRequest(
                102, checkIn, checkOut, 2, "User B", "+919222222222", "userb@example.com"
        );
        BookingResponse holdB = bookingService.holdRoom(reqB);

        assertNotNull(holdB.bookingId());
        assertNotEquals(holdA.bookingId(), holdB.bookingId());
        assertEquals("User B", holdB.guestName());
        assertEquals(BookingStatus.HOLD, holdB.status());
    }

    @Test
    @DisplayName("4. Booking confirmation transitions state and secures room inventory permanently")
    void testBookingConfirmation() {
        LocalDate checkIn = LocalDate.of(2026, 10, 20);
        LocalDate checkOut = LocalDate.of(2026, 10, 22);

        HoldBookingRequest holdReq = new HoldBookingRequest(
                104, checkIn, checkOut, 1, "Charlie", "+919333333333", "charlie@example.com"
        );
        BookingResponse holdRes = bookingService.holdRoom(holdReq);

        // Confirm within hold window
        ConfirmBookingRequest confirmReq = ConfirmBookingRequest.upi("987654321098", holdRes.bookingToken());
        BookingResponse confirmedRes = bookingService.confirmBooking(holdRes.bookingId(), confirmReq);

        assertEquals(BookingStatus.CONFIRMED, confirmedRes.status());
        assertEquals("987654321098", confirmedRes.paymentReference());
        assertEquals(PaymentStatus.PAID_ONLINE, confirmedRes.paymentStatus());
        assertNotNull(confirmedRes.confirmedAt());

        // Verify that even after 10 minutes pass, the confirmed room CANNOT be overwritten
        testClock.plusSeconds(1200);

        HoldBookingRequest collisionReq = new HoldBookingRequest(
                104, checkIn, checkOut, 1, "Intruder", "+919444444444", "intruder@example.com"
        );
        assertThrows(BookingConflictException.class, () -> bookingService.holdRoom(collisionReq));
    }

    @Test
    @DisplayName("5. Extension conflict: rejected when another reservation exists for extended dates")
    void testExtensionConflict() {
        // Booking 1: Oct 10 to Oct 12 on Room 110 (AC)
        BookingResponse b1 = bookingService.holdRoom(new HoldBookingRequest(
                110, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12),
                2, "Guest One", "+919555555551", "g1@example.com"
        ));
        BookingResponse c1 = bookingService.confirmBooking(b1.bookingId(), ConfirmBookingRequest.payAtHotel(b1.bookingToken()));

        // Booking 2: Oct 12 to Oct 15 on Room 110 (AC)
        BookingResponse b2 = bookingService.holdRoom(new HoldBookingRequest(
                110, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 15),
                2, "Guest Two", "+919555555552", "g2@example.com"
        ));
        bookingService.confirmBooking(b2.bookingId(), ConfirmBookingRequest.payAtHotel(b2.bookingToken()));

        // Booking 1 tries to extend checkout from Oct 12 to Oct 14 -> must conflict!
        ExtendBookingRequest extendReq = new ExtendBookingRequest(LocalDate.of(2026, 10, 14));
        BookingConflictException ex = assertThrows(BookingConflictException.class, () -> {
            bookingService.extendBooking(b1.bookingId(), extendReq, c1.manageToken());
        });

        assertTrue(ex.getMessage().contains("Room unavailable for requested extension period"));
    }

    @Test
    @DisplayName("6. Successful extension: dates locked and additional tariff + 5% GST calculated accurately")
    void testSuccessfulExtension() {
        // Room 101: Deluxe, Base Rate ₹600, Double Bed.
        // Booking for 2 guests, 2 nights (Oct 5 to Oct 7)
        // Base subtotal = 600 * 2 = 1200, GST = 60, Total = 1260
        BookingResponse b1 = bookingService.holdRoom(new HoldBookingRequest(
                101, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 7),
                2, "Solo", "+919666666661", "solo@example.com"
        ));
        BookingResponse c1 = bookingService.confirmBooking(b1.bookingId(), ConfirmBookingRequest.payAtHotel(b1.bookingToken()));

        assertEquals(new BigDecimal("1200.00"), b1.subtotal());
        assertEquals(new BigDecimal("60.00"), b1.gstAmount());
        assertEquals(new BigDecimal("1260.00"), b1.totalAmount());

        // Extend from Oct 7 to Oct 9 (2 extra nights)
        // Additional: 600 * 2 = 1200, GST = 60, Additional total = 1260
        // New total: subtotal = 2400, gst = 120, total = 2520
        ExtendBookingRequest extendReq = new ExtendBookingRequest(LocalDate.of(2026, 10, 9));
        BookingResponse extended = bookingService.extendBooking(b1.bookingId(), extendReq, c1.manageToken());

        assertEquals(LocalDate.of(2026, 10, 9), extended.checkOutDate());
        assertEquals(4, extended.nights());
        assertEquals(new BigDecimal("2400.00"), extended.subtotal());
        assertEquals(new BigDecimal("120.00"), extended.gstAmount());
        assertEquals(new BigDecimal("2520.00"), extended.totalAmount());
    }

    @Test
    @DisplayName("7. Availability search correctly filters out held and confirmed rooms for requested dates")
    void testAvailabilitySearch() {
        LocalDate checkIn = LocalDate.of(2026, 10, 10);
        LocalDate checkOut = LocalDate.of(2026, 10, 12);

        // Initially all 32 rooms are available
        RoomAvailabilityResponse initial = bookingService.getAvailability(checkIn, checkOut, Optional.empty());
        assertEquals(32, initial.totalAvailableRooms());

        // Hold Room 101 and Confirm Room 201
        bookingService.holdRoom(new HoldBookingRequest(
                101, checkIn, checkOut, 2, "A", "+919000000001", "a@a.com"
        ));
        BookingResponse b201 = bookingService.holdRoom(new HoldBookingRequest(
                201, checkIn, checkOut, 2, "B", "+919000000002", "b@b.com"
        ));
        bookingService.confirmBooking(b201.bookingId(), ConfirmBookingRequest.payAtHotel(b201.bookingToken()));

        // Now exactly 30 rooms should be available
        RoomAvailabilityResponse afterBooking = bookingService.getAvailability(checkIn, checkOut, Optional.empty());
        assertEquals(30, afterBooking.totalAvailableRooms());

        List<Integer> availableNos = afterBooking.availableRooms().stream()
                .map(RoomAvailabilityResponse.AvailableRoomDto::roomNumber)
                .toList();

        assertFalse(availableNos.contains(101));
        assertFalse(availableNos.contains(201));
        assertTrue(availableNos.contains(102));
    }

    @Test
    @DisplayName("8. Phone-only lookup returns that guest's bookings as MASKED summaries only (most recent check-in first, no PII, no tokens)")
    void testGuestPhoneLookup() {
        String targetPhone = "+919812345670";

        BookingResponse earlier = bookingService.holdRoom(new HoldBookingRequest(
                103, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3),
                1, "Priya Sharma", targetPhone, "priya@example.com"
        ));
        BookingResponse later = bookingService.holdRoom(new HoldBookingRequest(
                105, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12),
                1, "Priya Sharma", "98123 45670", "priya@example.com"   // same person, different spelling
        ));
        // A different guest's booking must never show up in Priya's lookup.
        bookingService.holdRoom(new HoldBookingRequest(
                106, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6),
                1, "Someone Else", "+919999999999", "other@example.com"
        ));

        List<BookingSummaryResponse> results = bookingService.lookupSummariesByPhone(targetPhone);

        assertEquals(2, results.size());
        assertEquals(later.checkInDate(), results.get(0).checkInDate(), "Most recent check-in should come first");
        assertEquals(earlier.checkInDate(), results.get(1).checkInDate());
        assertTrue(results.stream().allMatch(r -> "P**** S****".equals(r.guestNameMasked())), "guest name is masked");
        assertEquals(earlier.bookingId().substring(0, 4) + "...", results.get(1).bookingIdHint());
        assertEquals(later.bookingId().substring(0, 4) + "...", results.get(0).bookingIdHint(),
                "only a 4-character hint of the booking ID is returned, never the full ID");

        // Valid-but-unknown number: empty. Malformed numbers: 400 before any lookup.
        assertTrue(bookingService.lookupSummariesByPhone("6000000000").isEmpty());
        assertThrows(BookingValidationException.class, () -> bookingService.lookupSummariesByPhone("+910000000000"));
        assertThrows(BookingValidationException.class, () -> bookingService.lookupSummariesByPhone(""));
    }

    // --- In-Memory Repository replicating DynamoDB Condition Expression Semantics ---
    static class InMemoryBookingRepository implements BookingRepository {
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

    // --- Mutable Clock for Time Machine Testing ---
    static class MutableClock extends Clock {
        private Instant currentInstant;
        private final ZoneId zone;

        public MutableClock(Instant initialInstant, ZoneId zone) {
            this.currentInstant = initialInstant;
            this.zone = zone;
        }

        public void plusSeconds(long seconds) {
            this.currentInstant = this.currentInstant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(currentInstant, zone);
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }
    }
}

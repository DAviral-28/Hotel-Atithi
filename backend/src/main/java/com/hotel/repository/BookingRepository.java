package com.hotel.repository;

import com.hotel.model.Booking;
import com.hotel.model.RoomInventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BookingRepository {

    /**
     * Atomically locks room inventory for all requested dates and creates a HOLD booking.
     * Throws BookingConflictException if any date is already held or confirmed.
     */
    void holdRoom(Booking booking, List<RoomInventory> inventories, long nowEpochSeconds);

    /**
     * Atomically transitions a HOLD booking and its inventory to CONFIRMED, persisting the payment
     * fields carried by {@code booking} (method, status, amount paid, UTR, timestamps). For a UPI
     * payment the UTR is claimed in the same transaction so one UTR can never confirm two bookings;
     * a duplicate UTR raises BookingValidationException.
     */
    void confirmBooking(Booking booking, List<RoomInventory> inventories, long nowEpochSeconds);

    /**
     * Atomically locks extended dates and updates the booking record.
     */
    void extendBooking(Booking updatedBooking, List<RoomInventory> extensionInventories, long nowEpochSeconds);

    /**
     * Records an online (UPI) payment against a CONFIRMED booking — the balance from an extension or
     * a pay-at-hotel booking the guest chose to settle early. Writes amountPaid / paymentStatus /
     * paymentReference / paidAt / paymentMethod from {@code updated} and claims the UTR in the same
     * transaction. The write is conditional on the booking still being CONFIRMED and on amountPaid
     * still equalling {@code expectedPreviousAmountPaid} (optimistic concurrency), otherwise
     * BookingValidationException is thrown.
     */
    void recordPayment(Booking updated, BigDecimal expectedPreviousAmountPaid, long nowEpochSeconds);

    /**
     * Cancels a CONFIRMED booking in one transaction: the booking becomes CANCELLED (cancelledAt,
     * lateCancellation, shortened ttl taken from {@code cancelled}) and every inventory row that
     * belongs to the booking is deleted so the room is bookable again immediately. Throws
     * BookingValidationException if the booking is no longer CONFIRMED.
     */
    void cancelBooking(Booking cancelled, List<RoomInventory> inventories, long nowEpochSeconds);

    /**
     * Rolls back a HOLD: marks the booking RELEASED and frees its inventory rows so the room is
     * bookable again immediately. {@code inventories} identifies the rows (room + dates) that were
     * locked for the booking. If the hold has already expired, the rows may now belong to another
     * guest, so only the booking record is updated. Throws BookingValidationException if the
     * booking is no longer in HOLD status.
     */
    void releaseHold(Booking releasedBooking, List<RoomInventory> inventories, long nowEpochSeconds);

    /**
     * Retrieves a booking by its unique booking ID.
     */
    Optional<Booking> findBookingById(String bookingId);

    /**
     * Retrieves all bookings for a guest identified by phone number, via the HMAC-SHA256
     * blind-indexed GSI1 (GUEST#<hmac> / BOOKING#<checkInDate>) — the guest's raw phone number
     * is never used as a lookup key. The argument should be the canonical 10-digit number
     * (see PhoneNormalizer). Rows still keyed with the pre-Phase-3 plain SHA-256 are found through a
     * fallback query and re-keyed. Returns an empty list if the phone number is blank or unknown.
     */
    List<Booking> findBookingsByGuestPhone(String guestPhone);

    /**
     * Retrieves inventory records for a specific room and list of dates.
     */
    List<RoomInventory> findInventoriesForRoomAndDates(int roomNumber, List<LocalDate> dates);

    /**
     * Retrieves all inventory records matching the given dates across all rooms.
     */
    List<RoomInventory> findInventoriesForDates(List<LocalDate> dates);

    // ------------------------------------------------------------------------------------------
    // Phase 4 - front-desk (staff) operations
    // ------------------------------------------------------------------------------------------

    /** Batch read of bookings by ID (unknown IDs are simply absent). Used to build the staff dashboard. */
    List<Booking> findBookingsByIds(Collection<String> bookingIds);

    /** IDs of bookings whose UPI payment still waits for staff to reconcile it against the UPI statement. */
    List<String> findPendingReconciliationIds();

    /**
     * CONFIRMED -> CHECKED_IN (checkedInAt taken from {@code updated}). Conditional on the booking still
     * being CONFIRMED; otherwise BookingValidationException.
     */
    void checkIn(Booking updated);

    /**
     * CHECKED_IN -> CHECKED_OUT (checkedOutAt / checkOutDate / ttl taken from {@code updated}) and deletes
     * {@code releasedInventories} (the unused nights, so an early departure frees the room at once) in one
     * transaction. Conditional on the booking still being CHECKED_IN.
     */
    void checkOut(Booking updated, List<RoomInventory> releasedInventories, long nowEpochSeconds);

    /**
     * Records that staff reconciled / collected payment: writes amountPaid, paymentStatus, paymentVerifiedAt
     * (and paidAt) from {@code updated} and clears the booking from the reconciliation queue. Conditional on
     * the booking being live (CONFIRMED / CHECKED_IN) and on amountPaid still equalling
     * {@code expectedPreviousAmountPaid}, otherwise BookingValidationException.
     */
    void recordStaffPayment(Booking updated, BigDecimal expectedPreviousAmountPaid);

    /**
     * Atomically creates a booking that is already CONFIRMED or CHECKED_IN (walk-in) together with its
     * CONFIRMED inventory rows. Same lock rule as a hold: fails with BookingConflictException when any
     * night is confirmed, held (unexpired) or under maintenance.
     */
    void createDirectBooking(Booking booking, List<RoomInventory> inventories, long nowEpochSeconds);

    /**
     * Puts the given MAINTENANCE inventory rows in place atomically. Throws BookingConflictException
     * (with the clashing dates) if any night is booked, held or already blocked.
     */
    void blockRoom(List<RoomInventory> maintenanceInventories, long nowEpochSeconds);

    /**
     * Deletes MAINTENANCE rows for the room on the given dates; rows in any other state are left alone.
     * Returns how many nights were actually released.
     */
    int unblockRoom(int roomNumber, List<LocalDate> dates);
}

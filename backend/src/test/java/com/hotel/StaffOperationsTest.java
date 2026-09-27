package com.hotel;

import com.hotel.config.RoomDirectoryConfig;
import com.hotel.dto.*;
import com.hotel.dto.StaffDashboardResponse.RoomLiveStatus;
import com.hotel.dto.StaffDashboardResponse.RoomStatusView;
import com.hotel.exception.BookingConflictException;
import com.hotel.exception.BookingNotFoundException;
import com.hotel.exception.BookingValidationException;
import com.hotel.model.*;
import com.hotel.security.BookingTokenService;
import com.hotel.service.BookingService;
import com.hotel.service.StaffService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 front-desk flows against the in-memory repository (same conditional-write semantics as
 * DynamoDbBookingRepository): dashboard, check-in, check-out, payment verification, maintenance
 * blocking and walk-ins. "Today" is 2026-10-01 15:30 IST unless a test moves the clock.
 */
class StaffOperationsTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    private BookingConcurrencyTest.InMemoryBookingRepository repository;
    private BookingConcurrencyTest.MutableClock clock;
    private BookingService bookings;
    private StaffService staff;
    private int phoneSeq;

    @BeforeEach
    void setUp() {
        repository = new BookingConcurrencyTest.InMemoryBookingRepository();
        clock = new BookingConcurrencyTest.MutableClock(Instant.parse("2026-10-01T10:00:00Z"), IST);
        bookings = new BookingService(repository, clock, new BookingTokenService(""));
        staff = new StaffService(repository, clock);
        phoneSeq = 0;
    }

    private String phone() {
        return String.format("98000%05d", ++phoneSeq);
    }

    private BookingResponse hold(int room, LocalDate in, LocalDate out) {
        return bookings.holdRoom(new HoldBookingRequest(room, in, out, 2, "Guest Kumar", phone(), "guest@example.com"));
    }

    private BookingResponse payAtHotel(int room, LocalDate in, LocalDate out) {
        BookingResponse h = hold(room, in, out);
        return bookings.confirmBooking(h.bookingId(), ConfirmBookingRequest.payAtHotel(h.bookingToken()));
    }

    private BookingResponse upi(int room, LocalDate in, LocalDate out, String utr) {
        BookingResponse h = hold(room, in, out);
        return bookings.confirmBooking(h.bookingId(), ConfirmBookingRequest.upi(utr, h.bookingToken()));
    }

    private Booking stored(String id) {
        return repository.findBookingById(id).orElseThrow();
    }

    private RoomStatusView room(StaffDashboardResponse d, int number) {
        return d.rooms().stream().filter(r -> r.roomNumber() == number).findFirst().orElseThrow();
    }

    private WalkInRequest walkIn(int room, LocalDate out, String amountCollected) {
        return new WalkInRequest(room, null, out, 2, "Walk In Guest", phone(), null,
                amountCollected == null ? null : new BigDecimal(amountCollected), null);
    }

    // ------------------------------------------------------------------ check-in

    @Test
    @DisplayName("check-in: a CONFIRMED booking arriving today becomes CHECKED_IN with a timestamp")
    void checkInTransitionsStatus() {
        BookingResponse b = payAtHotel(101, TODAY, TODAY.plusDays(2));
        StaffBookingView v = staff.checkIn(b.bookingId());
        assertEquals(BookingStatus.CHECKED_IN, v.status());
        assertNotNull(v.checkedInAt());
        Booking s = stored(b.bookingId());
        assertEquals(BookingStatus.CHECKED_IN, s.status());
        assertEquals(clock.instant().getEpochSecond(), s.checkedInAt());
    }

    @Test
    @DisplayName("check-in is idempotent (double-tap on the button is harmless)")
    void checkInIsIdempotent() {
        BookingResponse b = payAtHotel(101, TODAY, TODAY.plusDays(1));
        staff.checkIn(b.bookingId());
        Long first = stored(b.bookingId()).checkedInAt();
        clock.plusSeconds(300);
        assertEquals(BookingStatus.CHECKED_IN, staff.checkIn(b.bookingId()).status());
        assertEquals(first, stored(b.bookingId()).checkedInAt(), "original check-in time is kept");
    }

    @Test
    @DisplayName("check-in before the check-in date, of a HOLD, of a cancelled booking, or of an unknown ID is refused")
    void checkInRules() {
        BookingResponse future = payAtHotel(102, TODAY.plusDays(3), TODAY.plusDays(4));
        BookingValidationException early = assertThrows(BookingValidationException.class, () -> staff.checkIn(future.bookingId()));
        assertTrue(early.getMessage().contains("Too early"), early.getMessage());

        BookingResponse hold = hold(103, TODAY, TODAY.plusDays(1));
        assertThrows(BookingValidationException.class, () -> staff.checkIn(hold.bookingId()));

        BookingResponse toCancel = payAtHotel(104, TODAY.plusDays(5), TODAY.plusDays(6));
        bookings.cancelBooking(toCancel.bookingId(), new CancelBookingRequest(false), toCancel.manageToken());
        assertThrows(BookingValidationException.class, () -> staff.checkIn(toCancel.bookingId()));

        assertThrows(BookingNotFoundException.class, () -> staff.checkIn("11111111-2222-3333-4444-555555555555"));
        assertThrows(BookingNotFoundException.class, () -> staff.checkIn("../etc/passwd"));
    }

    @Test
    @DisplayName("a checked-in guest can still extend their stay, but can no longer cancel online")
    void checkedInGuestCanExtendButNotCancel() {
        BookingResponse b = payAtHotel(105, TODAY, TODAY.plusDays(1));
        staff.checkIn(b.bookingId());
        BookingResponse extended = bookings.extendBooking(b.bookingId(), new ExtendBookingRequest(TODAY.plusDays(2)), b.manageToken());
        assertEquals(TODAY.plusDays(2), extended.checkOutDate());
        assertEquals(BookingStatus.CHECKED_IN, stored(b.bookingId()).status());
        assertThrows(BookingValidationException.class,
                () -> bookings.cancelBooking(b.bookingId(), new CancelBookingRequest(true), b.manageToken()));
    }

    // ------------------------------------------------------------------ check-out

    @Test
    @DisplayName("check-out: CHECKED_IN -> CHECKED_OUT on the departure day, inventory of the stay is kept for the record")
    void checkOutTransitionsStatus() {
        LocalDate in = TODAY.minusDays(2);
        // book in the past by moving the clock: hold on 09-29, then jump to 10-01
        clock = new BookingConcurrencyTest.MutableClock(Instant.parse("2026-09-29T10:00:00Z"), IST);
        bookings = new BookingService(repository, clock, new BookingTokenService(""));
        staff = new StaffService(repository, clock);
        BookingResponse b = payAtHotel(106, in, TODAY);
        staff.checkIn(b.bookingId());
        clock.plusSeconds(2 * 86400L);           // now 2026-10-01

        StaffBookingView out = staff.checkOut(b.bookingId());
        assertEquals(BookingStatus.CHECKED_OUT, out.status());
        assertNotNull(out.checkedOutAt());
        assertEquals(BookingStatus.CHECKED_OUT, stored(b.bookingId()).status());
        assertEquals(2, repository.findInventoriesForRoomAndDates(106, List.of(in, in.plusDays(1))).size(),
                "past nights stay on record");
    }

    @Test
    @DisplayName("early check-out releases the unused nights so the room can be sold again at once")
    void earlyCheckOutReleasesRemainingNights() {
        BookingResponse b = payAtHotel(107, TODAY, TODAY.plusDays(3));
        staff.checkIn(b.bookingId());
        assertThrows(BookingConflictException.class, () -> hold(107, TODAY.plusDays(1), TODAY.plusDays(2)),
                "room is still locked while the guest is in-house");

        staff.checkOut(b.bookingId());

        assertTrue(repository.findInventoriesForRoomAndDates(107, List.of(TODAY, TODAY.plusDays(1), TODAY.plusDays(2))).isEmpty(),
                "all three unused nights were released (the guest leaves today)");
        assertDoesNotThrow(() -> hold(107, TODAY.plusDays(1), TODAY.plusDays(2)), "public can book the freed room");
    }

    @Test
    @DisplayName("check-out is idempotent and refused for guests who never checked in")
    void checkOutRules() {
        BookingResponse confirmedOnly = payAtHotel(108, TODAY, TODAY.plusDays(1));
        BookingValidationException ex = assertThrows(BookingValidationException.class, () -> staff.checkOut(confirmedOnly.bookingId()));
        assertTrue(ex.getMessage().contains("checked in"), ex.getMessage());

        BookingResponse b = payAtHotel(109, TODAY, TODAY.plusDays(1));
        staff.checkIn(b.bookingId());
        staff.checkOut(b.bookingId());
        Long first = stored(b.bookingId()).checkedOutAt();
        clock.plusSeconds(600);
        assertEquals(BookingStatus.CHECKED_OUT, staff.checkOut(b.bookingId()).status());
        assertEquals(first, stored(b.bookingId()).checkedOutAt());
        assertThrows(BookingNotFoundException.class, () -> staff.checkOut("99999999-9999-9999-9999-999999999999"));
    }

    @Test
    @DisplayName("an outstanding balance does not block check-out but is reported to the desk")
    void checkOutReportsBalanceDue() {
        BookingResponse b = payAtHotel(110, TODAY, TODAY.plusDays(1));
        staff.checkIn(b.bookingId());
        StaffBookingView v = staff.checkOut(b.bookingId());
        assertEquals(0, b.totalAmount().compareTo(v.balanceDue()), "nothing was collected, full total still due");
    }

    // ------------------------------------------------------------------ payments

    @Test
    @DisplayName("an unreconciled UPI payment shows up on the dashboard until staff verify it")
    void upiPaymentIsListedUntilVerified() {
        BookingResponse b = upi(111, TODAY.plusDays(4), TODAY.plusDays(5), "ABCD12345678");
        StaffDashboardResponse before = staff.dashboard();
        assertEquals(1, before.stats().unverifiedPayments());
        assertEquals(b.bookingId(), before.unverifiedPayments().get(0).bookingId());
        assertEquals("ABCD12345678", before.unverifiedPayments().get(0).paymentReference());
        assertFalse(before.unverifiedPayments().get(0).paymentVerified());

        StaffBookingView v = staff.verifyPayment(b.bookingId(), null);
        assertTrue(v.paymentVerified());
        assertEquals(PaymentStatus.PAID_ONLINE, v.paymentStatus());
        assertEquals(0, v.balanceDue().signum());

        assertEquals(0, staff.dashboard().stats().unverifiedPayments());
        assertNotNull(stored(b.bookingId()).paymentVerifiedAt());
    }

    @Test
    @DisplayName("verify-payment on a pay-at-hotel booking collects the whole balance at the desk")
    void deskCollectsFullBalance() {
        BookingResponse b = payAtHotel(112, TODAY, TODAY.plusDays(1));
        StaffBookingView v = staff.verifyPayment(b.bookingId(), null);
        assertEquals(PaymentStatus.PAID_AT_DESK, v.paymentStatus());
        assertEquals(0, b.totalAmount().compareTo(v.amountPaid()));
        assertEquals(0, v.balanceDue().signum());
        assertTrue(v.paymentVerified());
        assertNotNull(stored(b.bookingId()).paidAt());
    }

    @Test
    @DisplayName("a partial desk payment leaves the remainder due; a second call collects it")
    void partialThenRemainingPayment() {
        BookingResponse b = payAtHotel(113, TODAY, TODAY.plusDays(1));   // AC room: 1155.00
        StaffBookingView first = staff.verifyPayment(b.bookingId(), new VerifyPaymentRequest(new BigDecimal("500.00")));
        assertEquals(PaymentStatus.DUE_AT_CHECKIN, first.paymentStatus());
        assertEquals(0, b.totalAmount().subtract(new BigDecimal("500.00")).compareTo(first.balanceDue()));

        StaffBookingView second = staff.verifyPayment(b.bookingId(), null);
        assertEquals(PaymentStatus.PAID_AT_DESK, second.paymentStatus());
        assertEquals(0, second.balanceDue().signum());
        assertEquals(0, b.totalAmount().compareTo(second.amountPaid()));
    }

    @Test
    @DisplayName("collecting more than the balance, a negative amount, or 0 for an unpaid desk booking is refused")
    void paymentAmountValidation() {
        BookingResponse b = payAtHotel(114, TODAY, TODAY.plusDays(1));
        assertThrows(BookingValidationException.class, () ->
                staff.verifyPayment(b.bookingId(), new VerifyPaymentRequest(b.totalAmount().add(BigDecimal.ONE))));
        assertThrows(BookingValidationException.class, () ->
                staff.verifyPayment(b.bookingId(), new VerifyPaymentRequest(new BigDecimal("-1"))));
        assertThrows(BookingValidationException.class, () ->
                staff.verifyPayment(b.bookingId(), new VerifyPaymentRequest(BigDecimal.ZERO)));
        assertEquals(0, stored(b.bookingId()).amountPaid().signum(), "nothing was recorded by the failed attempts");
    }

    @Test
    @DisplayName("verify-payment is idempotent once everything is settled and verified")
    void verifyPaymentIdempotent() {
        BookingResponse b = upi(115, TODAY.plusDays(2), TODAY.plusDays(3), "ZXCV12345678");
        staff.verifyPayment(b.bookingId(), null);
        Long verifiedAt = stored(b.bookingId()).paymentVerifiedAt();
        clock.plusSeconds(120);
        StaffBookingView again = staff.verifyPayment(b.bookingId(), null);
        assertTrue(again.paymentVerified());
        assertEquals(verifiedAt, stored(b.bookingId()).paymentVerifiedAt(), "no second write");
    }

    @Test
    @DisplayName("verify-payment is refused for cancelled bookings and HOLDs")
    void verifyPaymentStatusRules() {
        BookingResponse cancelled = payAtHotel(116, TODAY.plusDays(6), TODAY.plusDays(7));
        bookings.cancelBooking(cancelled.bookingId(), new CancelBookingRequest(false), cancelled.manageToken());
        assertThrows(BookingValidationException.class, () -> staff.verifyPayment(cancelled.bookingId(), null));
        BookingResponse hold = hold(101, TODAY.plusDays(8), TODAY.plusDays(9));
        assertThrows(BookingValidationException.class, () -> staff.verifyPayment(hold.bookingId(), null));
    }

    // ------------------------------------------------------------------ maintenance

    @Test
    @DisplayName("block: the public can neither hold a blocked room nor see it in availability")
    void blockedRoomCannotBeBookedByThePublic() {
        RoomBlockResponse r = staff.blockRoom(201, new BlockRoomRequest(TODAY.plusDays(1), TODAY.plusDays(3)));
        assertEquals(3, r.nights(), "start and end are inclusive nights");

        BookingConflictException ex = assertThrows(BookingConflictException.class,
                () -> hold(201, TODAY.plusDays(2), TODAY.plusDays(4)));
        assertTrue(ex.getConflictingDates().contains(TODAY.plusDays(2)));

        RoomAvailabilityResponse avail = bookings.getAvailability(TODAY.plusDays(1), TODAY.plusDays(2), Optional.empty());
        assertTrue(avail.availableRooms().stream().noneMatch(x -> x.roomNumber() == 201), "blocked room is not offered");
        assertEquals(31, avail.totalAvailableRooms());

        // outside the blocked range the room is still bookable
        assertDoesNotThrow(() -> hold(201, TODAY.plusDays(4), TODAY.plusDays(5)));
    }

    @Test
    @DisplayName("block: a single night (start == end) blocks exactly one night")
    void blockSingleNight() {
        RoomBlockResponse r = staff.blockRoom(202, new BlockRoomRequest(TODAY, TODAY));
        assertEquals(1, r.nights());
        assertEquals(1, repository.findInventoriesForRoomAndDates(202, List.of(TODAY, TODAY.plusDays(1))).size());
        RoomInventory row = repository.findInventoriesForRoomAndDates(202, List.of(TODAY)).get(0);
        assertEquals(InventoryStatus.MAINTENANCE, row.status());
        assertTrue(row.isCurrentlyLocked(clock.instant().getEpochSecond()));
    }

    @Test
    @DisplayName("block: nights that already have a booking or a live hold are never overwritten (409)")
    void blockRefusesBookedNights() {
        BookingResponse booked = payAtHotel(203, TODAY.plusDays(2), TODAY.plusDays(3));
        BookingConflictException ex = assertThrows(BookingConflictException.class,
                () -> staff.blockRoom(203, new BlockRoomRequest(TODAY.plusDays(1), TODAY.plusDays(4))));
        assertEquals(List.of(TODAY.plusDays(2)), ex.getConflictingDates());
        // nothing was blocked, the guest's booking is intact
        assertEquals(1, repository.findInventoriesForRoomAndDates(203,
                List.of(TODAY.plusDays(1), TODAY.plusDays(2), TODAY.plusDays(3))).size());
        assertEquals(BookingStatus.CONFIRMED, stored(booked.bookingId()).status());

        hold(204, TODAY.plusDays(2), TODAY.plusDays(3));
        assertThrows(BookingConflictException.class,
                () -> staff.blockRoom(204, new BlockRoomRequest(TODAY.plusDays(2), TODAY.plusDays(2))));
    }

    @Test
    @DisplayName("block: rejects past starts, reversed ranges, > 90 nights and unknown rooms")
    void blockValidation() {
        assertThrows(BookingValidationException.class,
                () -> staff.blockRoom(205, new BlockRoomRequest(TODAY.minusDays(1), TODAY)));
        assertThrows(BookingValidationException.class,
                () -> staff.blockRoom(205, new BlockRoomRequest(TODAY.plusDays(5), TODAY.plusDays(4))));
        assertThrows(BookingValidationException.class,
                () -> staff.blockRoom(205, new BlockRoomRequest(TODAY, TODAY.plusDays(90))));
        assertDoesNotThrow(() -> staff.blockRoom(205, new BlockRoomRequest(TODAY, TODAY.plusDays(89))));
        assertThrows(BookingValidationException.class,
                () -> staff.blockRoom(999, new BlockRoomRequest(TODAY, TODAY.plusDays(1))));
        assertThrows(BookingValidationException.class, () -> staff.blockRoom(206, null));
    }

    @Test
    @DisplayName("unblock: restores the room to AVAILABLE so the public can book it again")
    void unblockRestoresAvailability() {
        staff.blockRoom(207, new BlockRoomRequest(TODAY.plusDays(1), TODAY.plusDays(3)));
        assertThrows(BookingConflictException.class, () -> hold(207, TODAY.plusDays(1), TODAY.plusDays(2)));

        RoomBlockResponse r = staff.unblockRoom(207, null);
        assertEquals(3, r.nights(), "three maintenance nights released");
        assertTrue(repository.findInventoriesForRoomAndDates(207,
                List.of(TODAY.plusDays(1), TODAY.plusDays(2), TODAY.plusDays(3))).isEmpty());
        assertDoesNotThrow(() -> hold(207, TODAY.plusDays(1), TODAY.plusDays(4)));
    }

    @Test
    @DisplayName("unblock with a range releases only those nights; unblock is idempotent")
    void unblockRangeAndIdempotent() {
        staff.blockRoom(208, new BlockRoomRequest(TODAY.plusDays(1), TODAY.plusDays(4)));   // 4 nights
        RoomBlockResponse part = staff.unblockRoom(208, new UnblockRoomRequest(TODAY.plusDays(2), TODAY.plusDays(3)));
        assertEquals(2, part.nights());
        assertEquals(2, repository.findInventoriesForRoomAndDates(208, List.of(
                TODAY.plusDays(1), TODAY.plusDays(2), TODAY.plusDays(3), TODAY.plusDays(4))).size());

        staff.unblockRoom(208, null);
        RoomBlockResponse none = staff.unblockRoom(208, null);
        assertEquals(0, none.nights());
        assertTrue(none.message().contains("no maintenance"), none.message());
    }

    @Test
    @DisplayName("unblock never touches a guest's booking on the same room")
    void unblockLeavesGuestBookingsAlone() {
        BookingResponse b = payAtHotel(209, TODAY.plusDays(1), TODAY.plusDays(2));
        staff.blockRoom(209, new BlockRoomRequest(TODAY.plusDays(2), TODAY.plusDays(3)));
        staff.unblockRoom(209, new UnblockRoomRequest(TODAY, TODAY.plusDays(10)));
        List<RoomInventory> rows = repository.findInventoriesForRoomAndDates(209,
                List.of(TODAY.plusDays(1), TODAY.plusDays(2), TODAY.plusDays(3)));
        assertEquals(1, rows.size());
        assertEquals(b.bookingId(), rows.get(0).bookingId());
        assertEquals(InventoryStatus.CONFIRMED, rows.get(0).status());
    }

    // ------------------------------------------------------------------ walk-in

    @Test
    @DisplayName("walk-in: creates a CHECKED_IN booking today with no hold or token, and locks the room")
    void walkInCreatesCheckedInBooking() {
        StaffBookingView v = staff.walkIn(walkIn(210, TODAY.plusDays(2), null));
        assertEquals(BookingStatus.CHECKED_IN, v.status());
        assertEquals(TODAY, v.checkInDate());
        assertEquals(2, v.nights());
        assertNotNull(v.checkedInAt());
        assertEquals(PaymentStatus.DUE_AT_CHECKIN, v.paymentStatus());
        assertEquals(0, v.amountPaid().signum());

        Booking s = stored(v.bookingId());
        assertEquals(BookingStatus.CHECKED_IN, s.status());
        assertEquals(PaymentMethod.PAY_AT_HOTEL, s.paymentMethod());
        assertEquals(2, repository.findInventoriesForRoomAndDates(210, List.of(TODAY, TODAY.plusDays(1))).size());
        assertThrows(BookingConflictException.class, () -> hold(210, TODAY, TODAY.plusDays(1)),
                "the public cannot book a walk-in's room");
    }

    @Test
    @DisplayName("walk-in: price is the normal GST tariff and full payment at the desk is recorded as PAID_AT_DESK")
    void walkInPaidInFull() {
        Room.TariffCalculation t = RoomDirectoryConfig.findByRoomNumber(211).orElseThrow().calculateTariff(2, 1);
        StaffBookingView v = staff.walkIn(walkIn(211, TODAY.plusDays(1), t.totalAmount().toPlainString()));
        assertEquals(0, t.totalAmount().compareTo(v.totalAmount()));
        assertEquals(PaymentStatus.PAID_AT_DESK, v.paymentStatus());
        assertEquals(0, v.balanceDue().signum());
        assertTrue(v.paymentVerified(), "cash taken at the desk needs no further reconciliation");
    }

    @Test
    @DisplayName("walk-in: a partial payment leaves the rest due; over-collecting is refused")
    void walkInPartialPayment() {
        StaffBookingView v = staff.walkIn(walkIn(212, TODAY.plusDays(1), "300"));
        assertEquals(PaymentStatus.DUE_AT_CHECKIN, v.paymentStatus());
        assertEquals(0, v.totalAmount().subtract(new BigDecimal("300")).compareTo(v.balanceDue()));
        assertThrows(BookingValidationException.class, () -> staff.walkIn(walkIn(213, TODAY.plusDays(1), "100000")));
        assertThrows(BookingValidationException.class, () -> staff.walkIn(walkIn(213, TODAY.plusDays(1), "-5")));
    }

    @Test
    @DisplayName("walk-in: refused (409) for a room that is booked, held or under maintenance")
    void walkInConflicts() {
        payAtHotel(214, TODAY, TODAY.plusDays(1));
        assertThrows(BookingConflictException.class, () -> staff.walkIn(walkIn(214, TODAY.plusDays(1), null)));

        staff.blockRoom(215, new BlockRoomRequest(TODAY, TODAY));
        assertThrows(BookingConflictException.class, () -> staff.walkIn(walkIn(215, TODAY.plusDays(1), null)));

        hold(216, TODAY, TODAY.plusDays(1));
        assertThrows(BookingConflictException.class, () -> staff.walkIn(walkIn(216, TODAY.plusDays(1), null)));
    }

    @Test
    @DisplayName("walk-in: a future check-in date creates a CONFIRMED booking (checkInNow only applies to today)")
    void walkInForFutureDateIsConfirmed() {
        StaffBookingView v = staff.walkIn(new WalkInRequest(101, TODAY.plusDays(3), TODAY.plusDays(4), 2,
                "Phone Booking", phone(), "pb@example.com", null, true));
        assertEquals(BookingStatus.CONFIRMED, v.status());
        assertNull(v.checkedInAt());
    }

    @Test
    @DisplayName("walk-in: checkInNow=false keeps a same-day arrival CONFIRMED for later check-in")
    void walkInCheckInNowFalse() {
        StaffBookingView v = staff.walkIn(new WalkInRequest(102, null, TODAY.plusDays(1), 2,
                "Later Arrival", phone(), null, null, false));
        assertEquals(BookingStatus.CONFIRMED, v.status());
        assertEquals(BookingStatus.CHECKED_IN, staff.checkIn(v.bookingId()).status());
    }

    @Test
    @DisplayName("walk-in: validates room, phone, guest count, dates and name")
    void walkInValidation() {
        assertThrows(BookingValidationException.class, () -> staff.walkIn(walkIn(999, TODAY.plusDays(1), null)));
        assertThrows(BookingValidationException.class, () -> staff.walkIn(new WalkInRequest(
                101, null, TODAY.plusDays(1), 2, "Walk In", "12345", null, null, null)));
        assertThrows(BookingValidationException.class, () -> staff.walkIn(new WalkInRequest(
                104, null, TODAY.plusDays(1), 3, "Walk In", phone(), null, null, null)),
                "room 104 sleeps at most 2");
        assertThrows(BookingValidationException.class, () -> staff.walkIn(walkIn(101, TODAY, null)),
                "check-out must be after check-in");
        assertThrows(BookingValidationException.class, () -> staff.walkIn(walkIn(101, TODAY.plusDays(31), null)),
                "more than 30 nights");
        assertThrows(BookingValidationException.class, () -> staff.walkIn(new WalkInRequest(
                101, TODAY.minusDays(1), TODAY.plusDays(1), 2, "Walk In", phone(), null, null, null)),
                "check-in in the past");
        assertThrows(BookingValidationException.class, () -> staff.walkIn(new WalkInRequest(
                101, null, TODAY.plusDays(1), 2, "<b></b>", phone(), null, null, null)),
                "a name that is only markup is empty after sanitising");
    }

    @Test
    @DisplayName("walk-in: guest name markup is stripped and the phone stored in canonical form")
    void walkInSanitises() {
        StaffBookingView v = staff.walkIn(new WalkInRequest(103, null, TODAY.plusDays(1), 2,
                "<script>alert(1)</script>Ravi Kumar", "+91 98000-12345", null, null, null));
        assertEquals("Ravi Kumar", v.guestName());
        assertEquals("9800012345", v.guestPhone());
    }

    @Test
    @DisplayName("a walk-in guest can later look up and manage the booking with their phone, like any other guest")
    void walkInGuestCanAccessBooking() {
        StaffBookingView v = staff.walkIn(new WalkInRequest(104, null, TODAY.plusDays(1), 1,
                "Walk In", "9811122233", null, null, null));
        BookingResponse access = bookings.accessBooking(v.bookingId(), new AccessBookingRequest("+91 98111 22233"));
        assertEquals(BookingStatus.CHECKED_IN, access.status());
        assertNotNull(access.manageToken());
    }

    // ------------------------------------------------------------------ dashboard

    @Test
    @DisplayName("dashboard: arrivals, in-house, departures and the 32-room grid reflect the day")
    void dashboardShowsTheDay() {
        BookingResponse arriving = payAtHotel(101, TODAY, TODAY.plusDays(2));
        BookingResponse inHouse = payAtHotel(102, TODAY, TODAY.plusDays(3));
        staff.checkIn(inHouse.bookingId());
        BookingResponse futureGuest = payAtHotel(103, TODAY.plusDays(5), TODAY.plusDays(6));
        staff.blockRoom(104, new BlockRoomRequest(TODAY, TODAY.plusDays(1)));
        hold(105, TODAY, TODAY.plusDays(1));                                 // live public hold

        StaffDashboardResponse d = staff.dashboard();
        assertEquals(TODAY, d.date());
        assertEquals(32, d.rooms().size());
        assertEquals(List.of(arriving.bookingId()), d.arrivals().stream().map(StaffBookingView::bookingId).toList());
        assertEquals(List.of(inHouse.bookingId()), d.inHouse().stream().map(StaffBookingView::bookingId).toList());
        assertTrue(d.departures().isEmpty());

        assertEquals(RoomLiveStatus.RESERVED, room(d, 101).status());
        assertEquals(RoomLiveStatus.IN_HOUSE, room(d, 102).status());
        assertEquals(RoomLiveStatus.AVAILABLE, room(d, 103).status(), "a booking in 5 days does not occupy the room today");
        assertEquals(RoomLiveStatus.MAINTENANCE, room(d, 104).status());
        assertEquals(RoomLiveStatus.RESERVED, room(d, 105).status(), "a live online hold shows as reserved");
        assertEquals(RoomLiveStatus.AVAILABLE, room(d, 201).status());
        assertEquals(futureGuest.bookingId(), stored(futureGuest.bookingId()).bookingId());

        var stats = d.stats();
        assertEquals(32, stats.totalRooms());
        assertEquals(1, stats.roomsOccupied());
        assertEquals(2, stats.roomsReserved());
        assertEquals(1, stats.roomsMaintenance());
        assertEquals(28, stats.roomsAvailable());
        assertEquals(32, stats.roomsOccupied() + stats.roomsReserved() + stats.roomsMaintenance() + stats.roomsAvailable());
        assertEquals(1, stats.arrivalsToday());
        assertEquals(0, stats.departuresToday());
        assertEquals("101", String.valueOf(room(d, 101).roomNumber()));
        assertEquals(1, room(d, 101).floor());
        assertEquals(2, room(d, 201).floor());
    }

    @Test
    @DisplayName("dashboard: a guest checked in whose stay ends today is listed as a departure and still occupies the room")
    void dashboardDepartures() {
        clock = new BookingConcurrencyTest.MutableClock(Instant.parse("2026-09-30T10:00:00Z"), IST);
        bookings = new BookingService(repository, clock, new BookingTokenService(""));
        staff = new StaffService(repository, clock);
        BookingResponse b = payAtHotel(106, LocalDate.of(2026, 9, 30), TODAY);
        staff.checkIn(b.bookingId());
        clock.plusSeconds(86400L);                                              // 2026-10-01

        StaffDashboardResponse d = staff.dashboard();
        assertEquals(List.of(b.bookingId()), d.departures().stream().map(StaffBookingView::bookingId).toList());
        assertEquals(1, d.stats().departuresToday());
        assertEquals(RoomLiveStatus.IN_HOUSE, room(d, 106).status(),
                "no inventory row exists for the departure night, but the room is still occupied until check-out");

        staff.checkOut(b.bookingId());
        StaffDashboardResponse after = staff.dashboard();
        assertTrue(after.departures().isEmpty());
        assertEquals(RoomLiveStatus.AVAILABLE, room(after, 106).status());
    }

    @Test
    @DisplayName("dashboard: an overdue departure (should have left yesterday) is still surfaced")
    void dashboardOverdueDeparture() {
        clock = new BookingConcurrencyTest.MutableClock(Instant.parse("2026-09-28T10:00:00Z"), IST);
        bookings = new BookingService(repository, clock, new BookingTokenService(""));
        staff = new StaffService(repository, clock);
        BookingResponse b = payAtHotel(107, LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 30));
        staff.checkIn(b.bookingId());
        clock.plusSeconds(3 * 86400L);                                          // 2026-10-01, guest never checked out

        StaffDashboardResponse d = staff.dashboard();
        assertEquals(1, d.departures().size());
        assertEquals(b.bookingId(), d.departures().get(0).bookingId());
        assertEquals(RoomLiveStatus.IN_HOUSE, room(d, 107).status());
    }

    @Test
    @DisplayName("dashboard: 'today' is the Asia/Kolkata date, not the UTC date")
    void dashboardUsesIstDate() {
        // 2026-10-01 19:00 UTC == 2026-10-02 00:30 IST
        clock = new BookingConcurrencyTest.MutableClock(Instant.parse("2026-10-01T19:00:00Z"), IST);
        bookings = new BookingService(repository, clock, new BookingTokenService(""));
        staff = new StaffService(repository, clock);
        LocalDate istToday = LocalDate.of(2026, 10, 2);
        BookingResponse arriving = payAtHotel(108, istToday, istToday.plusDays(1));
        BookingResponse utcDateOnly = payAtHotel(109, LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 4));

        StaffDashboardResponse d = staff.dashboard();
        assertEquals(istToday, d.date());
        assertEquals(List.of(arriving.bookingId()), d.arrivals().stream().map(StaffBookingView::bookingId).toList());
        assertTrue(d.arrivals().stream().noneMatch(a -> a.bookingId().equals(utcDateOnly.bookingId())));
    }

    @Test
    @DisplayName("dashboard: pending desk dues add up the balances of today's arrivals and in-house guests")
    void dashboardPendingDeskDues() {
        BookingResponse a = payAtHotel(110, TODAY, TODAY.plusDays(1));
        BookingResponse paidOnline = upi(111, TODAY, TODAY.plusDays(1), "PAID12345678");
        BookingResponse later = payAtHotel(112, TODAY.plusDays(4), TODAY.plusDays(5));   // not today: not counted
        StaffBookingView desk = staff.walkIn(walkIn(113, TODAY.plusDays(1), "100"));

        var stats = staff.dashboard().stats();
        assertEquals(2, stats.pendingDeskDues(), "arrival 110 + in-house walk-in 113");
        BigDecimal expected = a.totalAmount().add(desk.totalAmount().subtract(new BigDecimal("100")));
        assertEquals(0, expected.compareTo(stats.pendingDeskDuesAmount()));
        assertEquals(1, stats.unverifiedPayments(), "only the UPI booking waits for reconciliation");
        assertEquals(paidOnline.bookingId(), staff.dashboard().unverifiedPayments().get(0).bookingId());
        assertNotNull(later.bookingId());
    }

    @Test
    @DisplayName("dashboard: staff see full guest contact details (name, phone, e-mail) but never a token")
    void dashboardShowsFullGuestDetails() {
        BookingResponse b = payAtHotel(114, TODAY, TODAY.plusDays(1));
        StaffBookingView v = staff.dashboard().arrivals().get(0);
        assertEquals("Guest Kumar", v.guestName());
        assertEquals(stored(b.bookingId()).guestPhone(), v.guestPhone());
        assertEquals("guest@example.com", v.guestEmail());
        assertTrue(StaffBookingView.class.getRecordComponents().length > 0);
        assertTrue(java.util.Arrays.stream(StaffBookingView.class.getRecordComponents())
                .noneMatch(c -> c.getName().toLowerCase().contains("token")), "the desk view carries no tokens");
    }

    @Test
    @DisplayName("dashboard: a cancelled booking disappears from arrivals and frees its room")
    void dashboardIgnoresCancelled() {
        // 09:30 IST: before today's 12:00 check-in time, so a (late) cancellation is still possible
        clock = new BookingConcurrencyTest.MutableClock(Instant.parse("2026-10-01T04:00:00Z"), IST);
        bookings = new BookingService(repository, clock, new BookingTokenService(""));
        staff = new StaffService(repository, clock);
        BookingResponse b = payAtHotel(115, TODAY.plusDays(3), TODAY.plusDays(4));
        StaffDashboardResponse before = staff.dashboard();
        assertTrue(before.arrivals().isEmpty(), "arrives in 3 days, not today");

        BookingResponse today = payAtHotel(116, TODAY, TODAY.plusDays(1));
        assertEquals(1, staff.dashboard().arrivals().size());
        bookings.cancelBooking(today.bookingId(), new CancelBookingRequest(true), today.manageToken());
        StaffDashboardResponse after = staff.dashboard();
        assertTrue(after.arrivals().isEmpty());
        assertEquals(RoomLiveStatus.AVAILABLE, room(after, 116).status());
        assertNotNull(b.bookingId());
    }
}

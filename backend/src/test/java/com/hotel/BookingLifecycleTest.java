package com.hotel;

import com.hotel.config.AppConfig;
import com.hotel.dto.AccessBookingRequest;
import com.hotel.dto.BookingResponse;
import com.hotel.dto.CancelBookingRequest;
import com.hotel.dto.ConfirmBookingRequest;
import com.hotel.dto.ExtendBookingRequest;
import com.hotel.dto.HoldBookingRequest;
import com.hotel.dto.PayBalanceRequest;
import com.hotel.exception.BookingConflictException;
import com.hotel.exception.BookingForbiddenException;
import com.hotel.exception.BookingNotFoundException;
import com.hotel.exception.BookingValidationException;
import com.hotel.model.Booking;
import com.hotel.model.BookingStatus;
import com.hotel.model.CancellationPolicy;
import com.hotel.model.InventoryStatus;
import com.hotel.model.PaymentMethod;
import com.hotel.model.PaymentStatus;
import com.hotel.model.RoomInventory;
import com.hotel.security.BookingTokenService;
import com.hotel.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 production-bug regression tests (data retention TTL, stay/advance limits, hold release,
 * idempotent confirmation, IST clock) and Phase 2 tests (pay-at-hotel, UPI/UTR payments, balance
 * payment for extensions, GST breakdown, phone access and the cancellation workflow). Reuses the in-memory repository / mutable clock from
 * {@link BookingConcurrencyTest}.
 */
class BookingLifecycleTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private BookingConcurrencyTest.InMemoryBookingRepository repository;
    private BookingConcurrencyTest.MutableClock clock;
    private BookingService service;
    /** Same (default dev) signing key as the service under test, so the test can mint tokens itself. */
    private BookingTokenService tokens;

    @BeforeEach
    void setUp() {
        repository = new BookingConcurrencyTest.InMemoryBookingRepository();
        // 2026-10-01 15:30 IST (10:00 UTC)
        clock = new BookingConcurrencyTest.MutableClock(Instant.parse("2026-10-01T10:00:00Z"), IST);
        tokens = new BookingTokenService("");
        service = new BookingService(repository, clock, tokens);
    }

    private HoldBookingRequest req(int room, LocalDate in, LocalDate out, String phone) {
        return new HoldBookingRequest(room, in, out, 2, "Guest", phone, "guest@example.com");
    }

    private long expectedTtl(LocalDate checkOut) {
        return checkOut.atTime(23, 59, 59).atZone(IST).toEpochSecond() + BookingService.RETENTION_TTL_SECONDS;
    }

    // ---------------------------------------------------------------- 1. TTL

    @Test
    @DisplayName("TTL is anchored to check-out + retention, so a stay booked ~8 months ahead is not purged early")
    void ttlIsAnchoredToCheckOutNotToHoldTime() {
        LocalDate checkIn = LocalDate.of(2027, 6, 1);
        LocalDate checkOut = LocalDate.of(2027, 6, 4);
        BookingResponse hold = service.holdRoom(req(101, checkIn, checkOut, "+919800000001"));
        service.confirmBooking(hold.bookingId(), ConfirmBookingRequest.payAtHotel(hold.bookingToken()));

        long ttl = expectedTtl(checkOut);
        Booking booking = repository.findBookingById(hold.bookingId()).orElseThrow();
        assertEquals(ttl, booking.ttl());

        List<RoomInventory> rows = repository.findInventoriesForRoomAndDates(101, List.of(
                checkIn, checkIn.plusDays(1), checkIn.plusDays(2)));
        assertEquals(3, rows.size());
        rows.forEach(r -> assertEquals(ttl, r.ttl(), "every inventory night must share the stay-based TTL"));

        // The old formula (hold expiry + 30 days) would have been ~2026-11-01, i.e. long before check-in.
        long checkInEpoch = checkIn.atStartOfDay(IST).toEpochSecond();
        assertTrue(booking.ttl() > checkInEpoch, "TTL must be after check-in");
        assertTrue(booking.ttl() > checkOut.atTime(23, 59, 59).atZone(IST).toEpochSecond(), "TTL must be after check-out");
    }

    @Test
    @DisplayName("hold expiry (lockExpiresAt) stays independent of the data TTL")
    void lockExpiryIsSeparateFromTtl() {
        LocalDate checkIn = LocalDate.of(2026, 12, 1);
        LocalDate checkOut = LocalDate.of(2026, 12, 3);
        BookingResponse hold = service.holdRoom(req(102, checkIn, checkOut, "+919800000002"));

        long now = clock.instant().getEpochSecond();
        RoomInventory row = repository.findInventoriesForRoomAndDates(102, List.of(checkIn)).get(0);
        assertEquals(now + BookingService.HOLD_DURATION_SECONDS, row.lockExpiresAt());
        assertEquals(expectedTtl(checkOut), row.ttl());
        assertEquals(600L, hold.remainingHoldSeconds());
    }

    @Test
    @DisplayName("extending a stay moves the booking and new inventory TTL to the new check-out + retention")
    void extensionRecalculatesTtl() {
        LocalDate checkIn = LocalDate.of(2026, 10, 10);
        LocalDate checkOut = LocalDate.of(2026, 10, 12);
        BookingResponse hold = service.holdRoom(req(103, checkIn, checkOut, "+919800000003"));
        BookingResponse confirmed = service.confirmBooking(hold.bookingId(), ConfirmBookingRequest.payAtHotel(hold.bookingToken()));
        assertEquals(expectedTtl(checkOut), repository.findBookingById(hold.bookingId()).orElseThrow().ttl());

        LocalDate newCheckOut = LocalDate.of(2026, 10, 20);
        service.extendBooking(hold.bookingId(), new ExtendBookingRequest(newCheckOut), confirmed.manageToken());

        assertEquals(expectedTtl(newCheckOut), repository.findBookingById(hold.bookingId()).orElseThrow().ttl());
        List<RoomInventory> added = repository.findInventoriesForRoomAndDates(103, List.of(
                LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 19)));
        assertEquals(2, added.size());
        added.forEach(r -> assertEquals(expectedTtl(newCheckOut), r.ttl()));
    }

    // ---------------------------------------------------------------- 2. Limits

    @Test
    @DisplayName("stays: 30 nights accepted, 31 rejected with a clear message")
    void maxStayLength() {
        LocalDate checkIn = LocalDate.of(2026, 11, 1);
        assertDoesNotThrow(() -> service.holdRoom(req(104, checkIn, checkIn.plusDays(30), "+919800000004")));

        BookingValidationException ex = assertThrows(BookingValidationException.class,
                () -> service.holdRoom(req(105, checkIn, checkIn.plusDays(31), "+919800000005")));
        assertTrue(ex.getMessage().contains("30 nights"), ex.getMessage());

        // The rejected request must not have locked anything.
        assertTrue(repository.findInventoriesForRoomAndDates(105, List.of(checkIn)).isEmpty());
    }

    @Test
    @DisplayName("100+ night request is rejected up front instead of blowing the 100-item DynamoDB transaction limit")
    void veryLongStayRejected() {
        LocalDate checkIn = LocalDate.of(2026, 11, 1);
        assertThrows(BookingValidationException.class,
                () -> service.holdRoom(req(106, checkIn, checkIn.plusDays(150), "+919800000006")));
    }

    @Test
    @DisplayName("advance window: check-in 365 days out accepted, 366 rejected")
    void maxAdvanceWindow() {
        LocalDate today = LocalDate.now(clock);
        LocalDate latest = today.plusDays(365);
        assertDoesNotThrow(() -> service.holdRoom(req(107, latest, latest.plusDays(1), "+919800000007")));

        LocalDate tooFar = today.plusDays(366);
        BookingValidationException ex = assertThrows(BookingValidationException.class,
                () -> service.holdRoom(req(108, tooFar, tooFar.plusDays(1), "+919800000008")));
        assertTrue(ex.getMessage().contains("365 days"), ex.getMessage());
    }

    @Test
    @DisplayName("check-out must be strictly after check-in and check-in cannot be in the past")
    void basicDateRules() {
        LocalDate d = LocalDate.of(2026, 11, 5);
        assertThrows(BookingValidationException.class, () -> service.holdRoom(req(109, d, d, "+919800000009")));
        assertThrows(BookingValidationException.class, () -> service.holdRoom(req(109, d, d.minusDays(1), "+919800000009")));
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);
        assertThrows(BookingValidationException.class,
                () -> service.holdRoom(req(109, yesterday, yesterday.plusDays(2), "+919800000009")));
    }

    @Test
    @DisplayName("extension that would push the total stay past 30 nights is rejected")
    void extensionCannotExceedMaxStay() {
        LocalDate checkIn = LocalDate.of(2026, 10, 10);
        BookingResponse hold = service.holdRoom(req(110, checkIn, checkIn.plusDays(20), "+919800000010"));
        BookingResponse confirmed = service.confirmBooking(hold.bookingId(), ConfirmBookingRequest.payAtHotel(hold.bookingToken()));
        String manage = confirmed.manageToken();

        assertDoesNotThrow(() -> service.extendBooking(hold.bookingId(), new ExtendBookingRequest(checkIn.plusDays(30)), manage));
        BookingValidationException ex = assertThrows(BookingValidationException.class,
                () -> service.extendBooking(hold.bookingId(), new ExtendBookingRequest(checkIn.plusDays(31)), manage));
        assertTrue(ex.getMessage().contains("30 nights"), ex.getMessage());
    }

    @Test
    @DisplayName("availability search applies the same stay-length rules")
    void availabilityValidatesRange() {
        LocalDate checkIn = LocalDate.of(2026, 11, 1);
        assertThrows(BookingValidationException.class,
                () -> service.getAvailability(checkIn, checkIn.plusDays(45), Optional.empty()));
    }

    // ---------------------------------------------------------------- 3. Release hold

    @Test
    @DisplayName("releasing a live hold frees the room immediately and marks the booking RELEASED")
    void releaseFreesRoomImmediately() {
        LocalDate in = LocalDate.of(2026, 10, 15);
        LocalDate out = LocalDate.of(2026, 10, 17);
        BookingResponse hold = service.holdRoom(req(111, in, out, "+919800000011"));

        // A second guest is blocked while the hold is live...
        assertThrows(BookingConflictException.class, () -> service.holdRoom(req(111, in, out, "+919800000012")));

        BookingResponse released = service.releaseHold(hold.bookingId(), hold.bookingToken());
        assertEquals(BookingStatus.RELEASED, released.status());
        assertEquals(BookingStatus.RELEASED, repository.findBookingById(hold.bookingId()).orElseThrow().status());

        // ...and can book straight away once it is released (no 10-minute wait).
        BookingResponse second = service.holdRoom(req(111, in, out, "+919800000012"));
        assertEquals(BookingStatus.HOLD, second.status());
    }

    @Test
    @DisplayName("edit-details flow: release then re-hold the same room/dates does not conflict with the guest's own hold")
    void modifyDetailsFlowDoesNotSelfConflict() {
        LocalDate in = LocalDate.of(2026, 10, 25);
        LocalDate out = LocalDate.of(2026, 10, 26);
        BookingResponse first = service.holdRoom(req(112, in, out, "+919800000013"));

        // Without a release the guest hits a 409 against themselves (the original bug).
        assertThrows(BookingConflictException.class, () -> service.holdRoom(req(112, in, out, "+919800000013")));

        service.releaseHold(first.bookingId(), first.bookingToken());
        BookingResponse again = service.holdRoom(req(112, in, out, "+919800000013"));
        assertNotEquals(first.bookingId(), again.bookingId());
    }

    @Test
    @DisplayName("release requires a valid token while the hold is live")
    void releaseRequiresValidToken() {
        LocalDate in = LocalDate.of(2026, 10, 15);
        LocalDate out = LocalDate.of(2026, 10, 16);
        BookingResponse hold = service.holdRoom(req(113, in, out, "+919800000014"));
        BookingResponse other = service.holdRoom(req(114, in, out, "+919800000015"));

        assertThrows(BookingValidationException.class, () -> service.releaseHold(hold.bookingId(), null));
        assertThrows(BookingValidationException.class, () -> service.releaseHold(hold.bookingId(), "garbage"));
        // A genuine token that belongs to a DIFFERENT booking must not release this one.
        assertThrows(BookingValidationException.class, () -> service.releaseHold(hold.bookingId(), other.bookingToken()));

        assertEquals(BookingStatus.HOLD, repository.findBookingById(hold.bookingId()).orElseThrow().status());
        assertThrows(BookingConflictException.class, () -> service.holdRoom(req(113, in, out, "+919800000099")));
    }

    @Test
    @DisplayName("release is idempotent, 404s for unknown ids and refuses confirmed bookings")
    void releaseEdgeCases() {
        LocalDate in = LocalDate.of(2026, 10, 15);
        LocalDate out = LocalDate.of(2026, 10, 16);
        BookingResponse hold = service.holdRoom(req(115, in, out, "+919800000016"));

        service.releaseHold(hold.bookingId(), hold.bookingToken());
        assertEquals(BookingStatus.RELEASED, service.releaseHold(hold.bookingId(), hold.bookingToken()).status());
        assertEquals(BookingStatus.RELEASED, service.releaseHold(hold.bookingId(), null).status());

        assertThrows(BookingNotFoundException.class, () -> service.releaseHold("no-such-id", "x"));

        BookingResponse confirmed = service.holdRoom(req(116, in, out, "+919800000017"));
        service.confirmBooking(confirmed.bookingId(), ConfirmBookingRequest.payAtHotel(confirmed.bookingToken()));
        assertThrows(BookingValidationException.class,
                () -> service.releaseHold(confirmed.bookingId(), confirmed.bookingToken()));
        assertEquals(BookingStatus.CONFIRMED, repository.findBookingById(confirmed.bookingId()).orElseThrow().status());
    }

    @Test
    @DisplayName("releasing an already-expired hold needs no token and never disturbs the guest who took the room since")
    void releaseExpiredHoldLeavesNewOwnerAlone() {
        LocalDate in = LocalDate.of(2026, 10, 15);
        LocalDate out = LocalDate.of(2026, 10, 16);
        BookingResponse a = service.holdRoom(req(201, in, out, "+919800000018"));
        clock.plusSeconds(601);
        BookingResponse b = service.holdRoom(req(201, in, out, "+919800000019")); // overwrites A's expired rows

        BookingResponse released = service.releaseHold(a.bookingId(), null);
        assertEquals(BookingStatus.RELEASED, released.status());

        // B still owns the room.
        RoomInventory row = repository.findInventoriesForRoomAndDates(201, List.of(in)).get(0);
        assertEquals(b.bookingId(), row.bookingId());
        assertEquals(InventoryStatus.HOLD, row.status());
        assertThrows(BookingConflictException.class, () -> service.holdRoom(req(201, in, out, "+919800000020")));
    }

    @Test
    @DisplayName("a released booking can no longer be confirmed")
    void cannotConfirmReleased() {
        LocalDate in = LocalDate.of(2026, 10, 15);
        LocalDate out = LocalDate.of(2026, 10, 16);
        BookingResponse hold = service.holdRoom(req(202, in, out, "+919800000021"));
        service.releaseHold(hold.bookingId(), hold.bookingToken());
        assertThrows(BookingValidationException.class,
                () -> service.confirmBooking(hold.bookingId(), ConfirmBookingRequest.payAtHotel(hold.bookingToken())));
    }

    @Test
    @DisplayName("released holds keep only a short retention TTL (no stay to protect)")
    void releasedBookingGetsShortTtl() {
        LocalDate in = LocalDate.of(2027, 8, 1);
        LocalDate out = LocalDate.of(2027, 8, 3);
        BookingResponse hold = service.holdRoom(req(203, in, out, "+919800000022"));
        service.releaseHold(hold.bookingId(), hold.bookingToken());
        long now = clock.instant().getEpochSecond();
        assertEquals(now + BookingService.RELEASED_RETENTION_SECONDS,
                repository.findBookingById(hold.bookingId()).orElseThrow().ttl());
    }

    // ---------------------------------------------------------------- 4. Idempotent confirm

    @Test
    @DisplayName("replaying a confirm with the same payment reference returns the confirmed booking (200), even after the token expired")
    void confirmIsIdempotent() {
        LocalDate in = LocalDate.of(2026, 10, 15);
        LocalDate out = LocalDate.of(2026, 10, 17);
        BookingResponse hold = service.holdRoom(req(204, in, out, "+919800000023"));
        ConfirmBookingRequest confirm = ConfirmBookingRequest.upi("424242424242", hold.bookingToken());

        BookingResponse first = service.confirmBooking(hold.bookingId(), confirm);
        BookingResponse replay = service.confirmBooking(hold.bookingId(), confirm);
        assertEquals(BookingStatus.CONFIRMED, replay.status());
        assertEquals(first.bookingId(), replay.bookingId());
        assertEquals(first.confirmedAt(), replay.confirmedAt(), "replay must not re-stamp confirmation time");
        assertEquals(first.totalAmount(), replay.totalAmount());

        // A client retrying much later (hold token long expired) still gets the same answer.
        clock.plusSeconds(3600);
        BookingResponse late = service.confirmBooking(hold.bookingId(), confirm);
        assertEquals(BookingStatus.CONFIRMED, late.status());
        assertEquals(first.confirmedAt(), late.confirmedAt());
    }

    @Test
    @DisplayName("confirming an already-confirmed booking with a DIFFERENT payment reference is rejected")
    void confirmWithDifferentReferenceRejected() {
        LocalDate in = LocalDate.of(2026, 10, 15);
        LocalDate out = LocalDate.of(2026, 10, 17);
        BookingResponse hold = service.holdRoom(req(205, in, out, "+919800000024"));
        service.confirmBooking(hold.bookingId(), ConfirmBookingRequest.upi("111111111111", hold.bookingToken()));

        BookingValidationException ex = assertThrows(BookingValidationException.class,
                () -> service.confirmBooking(hold.bookingId(), ConfirmBookingRequest.upi("222222222222", hold.bookingToken())));
        assertTrue(ex.getMessage().contains("different payment method or reference"), ex.getMessage());
        assertEquals("111111111111", repository.findBookingById(hold.bookingId()).orElseThrow().paymentReference());
    }

    // ---------------------------------------------------------------- 5. Timezone

    @Test
    @DisplayName("the Spring Clock bean is pinned to Asia/Kolkata")
    void clockBeanIsIst() {
        assertEquals(ZoneId.of("Asia/Kolkata"), new AppConfig().clock().getZone());
    }

    @Test
    @DisplayName("at 00:30 IST (19:00 UTC the previous day) 'today' is the IST date, not the UTC date")
    void todayIsTheIstDate() {
        // 2026-10-01T19:00:00Z == 2026-10-02 00:30 IST
        BookingConcurrencyTest.MutableClock lateNight = new BookingConcurrencyTest.MutableClock(
                Instant.parse("2026-10-01T19:00:00Z"), IST);
        BookingService istService = new BookingService(repository, lateNight, new BookingTokenService(""));

        // Booking for IST-today (Oct 2) works.
        assertDoesNotThrow(() -> istService.holdRoom(req(206, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3), "+919800000025")));
        // Oct 1 is already yesterday in India, even though it is still "today" in UTC.
        assertThrows(BookingValidationException.class,
                () -> istService.holdRoom(req(207, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), "+919800000026")));

        // Contrast: with a UTC clock the same instant would wrongly accept Oct 1.
        BookingConcurrencyTest.MutableClock utc = new BookingConcurrencyTest.MutableClock(
                Instant.parse("2026-10-01T19:00:00Z"), ZoneOffset.UTC);
        BookingService utcService = new BookingService(repository, utc, new BookingTokenService(""));
        assertDoesNotThrow(() -> utcService.holdRoom(req(207, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), "+919800000026")));
    }

    @Test
    @DisplayName("TTL uses the IST end of the check-out day, not UTC")
    void ttlUsesIstEndOfDay() {
        LocalDate d = LocalDate.of(2026, 10, 10);
        // 23:59:59 IST == 18:29:59 UTC
        long expected = Instant.parse("2026-10-10T18:29:59Z").getEpochSecond() + BookingService.RETENTION_TTL_SECONDS;
        assertEquals(expected, BookingService.retentionTtlFor(d));
    }

    // ================================================================ PHASE 2 helpers

    private BookingResponse holdFor(int room, LocalDate in, LocalDate out, String phone) {
        return service.holdRoom(req(room, in, out, phone));
    }

    private BookingResponse confirmPayAtHotel(int room, LocalDate in, LocalDate out, String phone) {
        BookingResponse hold = holdFor(room, in, out, phone);
        return service.confirmBooking(hold.bookingId(), ConfirmBookingRequest.payAtHotel(hold.bookingToken()));
    }

    private BookingResponse confirmUpi(int room, LocalDate in, LocalDate out, String phone, String utr) {
        BookingResponse hold = holdFor(room, in, out, phone);
        return service.confirmBooking(hold.bookingId(), ConfirmBookingRequest.upi(utr, hold.bookingToken()));
    }

    private long secondsUntil(Instant target) {
        return target.getEpochSecond() - clock.instant().getEpochSecond();
    }

    // ================================================================ 6. Pay at Hotel

    @Test
    @DisplayName("Pay at Hotel: CONFIRMED with paymentStatus DUE_AT_CHECKIN, nothing paid, full total due at the desk")
    void payAtHotelConfirmsWithPaymentDueAtCheckIn() {
        LocalDate in = LocalDate.of(2026, 10, 15);
        LocalDate out = LocalDate.of(2026, 10, 17);
        BookingResponse c = confirmPayAtHotel(201, in, out, "+919800000101");

        assertEquals(BookingStatus.CONFIRMED, c.status());
        assertEquals(PaymentMethod.PAY_AT_HOTEL, c.paymentMethod());
        assertEquals(PaymentStatus.DUE_AT_CHECKIN, c.paymentStatus());
        assertEquals(0, c.amountPaid().signum(), "nothing is paid online for pay-at-hotel");
        assertEquals(c.totalAmount(), c.balanceDue(), "the whole total is due at check-in");
        assertNull(c.paymentReference());
        assertNull(c.paidAt());

        Booking stored = repository.findBookingById(c.bookingId()).orElseThrow();
        assertEquals(PaymentStatus.DUE_AT_CHECKIN, stored.paymentStatus());
        assertEquals(PaymentMethod.PAY_AT_HOTEL, stored.paymentMethod());
        assertEquals(InventoryStatus.CONFIRMED,
                repository.findInventoriesForRoomAndDates(201, List.of(in)).get(0).status());
    }

    @Test
    @DisplayName("omitting paymentMethod defaults to PAY_AT_HOTEL (pay-at-hotel-only public flow)")
    void confirmDefaultsToPayAtHotelWhenMethodOmitted() {
        BookingResponse hold = holdFor(202, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16), "+919800000102");
        BookingResponse c = service.confirmBooking(hold.bookingId(),
                new ConfirmBookingRequest(null, null, hold.bookingToken()));
        assertEquals(BookingStatus.CONFIRMED, c.status());
        assertEquals(PaymentMethod.PAY_AT_HOTEL, c.paymentMethod());
        assertEquals(PaymentStatus.DUE_AT_CHECKIN, c.paymentStatus());
        assertEquals(0, c.amountPaid().signum());
    }

    @Test
    @DisplayName("pay-at-hotel replay is idempotent; replay with a different method is rejected")
    void payAtHotelReplayIsIdempotent() {
        BookingResponse hold = holdFor(203, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16), "+919800000103");
        ConfirmBookingRequest confirm = ConfirmBookingRequest.payAtHotel(hold.bookingToken());
        BookingResponse first = service.confirmBooking(hold.bookingId(), confirm);
        BookingResponse replay = service.confirmBooking(hold.bookingId(), confirm);
        assertEquals(first.confirmedAt(), replay.confirmedAt());
        assertEquals(PaymentStatus.DUE_AT_CHECKIN, replay.paymentStatus());
        assertNotNull(replay.manageToken(), "a replay hands the manage token back to a client that lost the first response");

        assertThrows(BookingValidationException.class, () -> service.confirmBooking(hold.bookingId(),
                ConfirmBookingRequest.upi("555555555555", hold.bookingToken())));
    }

    @Test
    @DisplayName("a confirm replay still needs proof of ownership (the signed hold token)")
    void confirmReplayNeedsToken() {
        BookingResponse hold = holdFor(205, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16), "+919800000104");
        service.confirmBooking(hold.bookingId(), ConfirmBookingRequest.payAtHotel(hold.bookingToken()));
        assertThrows(BookingValidationException.class, () -> service.confirmBooking(hold.bookingId(),
                ConfirmBookingRequest.payAtHotel(null)));
        assertThrows(BookingValidationException.class, () -> service.confirmBooking(hold.bookingId(),
                ConfirmBookingRequest.payAtHotel("garbage")));
    }

    // ================================================================ 7. UPI QR

    @Test
    @DisplayName("UPI: a 12-digit UTR confirms the booking as PAID_ONLINE and records the UTR and payment timestamp")
    void upiConfirmationRecordsUtrAndPaidOnline() {
        LocalDate in = LocalDate.of(2026, 10, 15);
        LocalDate out = LocalDate.of(2026, 10, 17);
        BookingResponse c = confirmUpi(206, in, out, "+919800000105", "402512345678");

        assertEquals(BookingStatus.CONFIRMED, c.status());
        assertEquals(PaymentMethod.UPI_QR, c.paymentMethod());
        assertEquals(PaymentStatus.PAID_ONLINE, c.paymentStatus());
        assertEquals("402512345678", c.paymentReference());
        assertEquals(c.totalAmount(), c.amountPaid());
        assertEquals(0, c.balanceDue().signum());
        assertEquals(clock.instant(), c.paidAt(), "paidAt is the moment the UTR was recorded");

        Booking stored = repository.findBookingById(c.bookingId()).orElseThrow();
        assertEquals("402512345678", stored.paymentReference());
        assertEquals(PaymentStatus.PAID_ONLINE, stored.paymentStatus());
        assertEquals(Long.valueOf(clock.instant().getEpochSecond()), stored.paidAt());
    }

    @Test
    @DisplayName("UPI: anything but exactly 12 letters/digits is rejected and the hold stays untouched")
    void upiRejectsMalformedUtr() {
        BookingResponse hold = holdFor(207, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16), "+919800000106");
        for (String bad : new String[]{null, "", "   ", "12345", "12345678901", "1234567890123", "1234 5678 9012", "12345678-901", "12345678901!"}) {
            BookingValidationException ex = assertThrows(BookingValidationException.class,
                    () -> service.confirmBooking(hold.bookingId(), ConfirmBookingRequest.upi(bad, hold.bookingToken())),
                    "UTR '" + bad + "' must be rejected");
            assertTrue(ex.getMessage().contains("12-character"), ex.getMessage());
        }
        assertEquals(BookingStatus.HOLD, repository.findBookingById(hold.bookingId()).orElseThrow().status());
        assertEquals(InventoryStatus.HOLD,
                repository.findInventoriesForRoomAndDates(207, List.of(LocalDate.of(2026, 10, 15))).get(0).status());
    }

    @Test
    @DisplayName("UPI: the same UTR cannot confirm two different bookings")
    void utrCannotBeReused() {
        confirmUpi(208, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16), "+919800000107", "777777777777");

        BookingResponse other = holdFor(209, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16), "+919800000108");
        BookingValidationException ex = assertThrows(BookingValidationException.class,
                () -> service.confirmBooking(other.bookingId(), ConfirmBookingRequest.upi("777777777777", other.bookingToken())));
        assertTrue(ex.getMessage().contains("already been used"), ex.getMessage());
        assertEquals(BookingStatus.HOLD, repository.findBookingById(other.bookingId()).orElseThrow().status());
    }

    @Test
    @DisplayName("UPI: confirm response carries a manage token that verifies; a hold token can never manage a booking")
    void manageTokenIsIssuedAndDistinctFromHoldToken() {
        BookingResponse hold = holdFor(210, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16), "+919800000109");
        BookingResponse c = service.confirmBooking(hold.bookingId(), ConfirmBookingRequest.upi("909090909090", hold.bookingToken()));

        assertNotNull(c.manageToken());
        long now = clock.instant().getEpochSecond();
        assertDoesNotThrow(() -> tokens.verifyManageToken(c.manageToken(), c.bookingId(), now));
        assertThrows(BookingForbiddenException.class, () -> tokens.verifyManageToken(hold.bookingToken(), c.bookingId(), now));
        assertThrows(BookingForbiddenException.class, () -> tokens.verifyManageToken(c.manageToken(), "another-booking", now));
        assertThrows(BookingForbiddenException.class, () -> tokens.verifyManageToken(null, c.bookingId(), now));
        // A hold token must not authorise a cancel either.
        assertThrows(BookingForbiddenException.class,
                () -> service.cancelBooking(c.bookingId(), null, hold.bookingToken()));
    }

    // ================================================================ 8. Extension payments

    @Test
    @DisplayName("extending a UPI-paid stay adds the extra amount to totalAmount and flips paymentStatus to DUE_AT_CHECKIN")
    void extensionAddsBalanceDue() {
        LocalDate in = LocalDate.of(2026, 10, 15);
        LocalDate out = LocalDate.of(2026, 10, 17);
        BookingResponse c = confirmUpi(101, in, out, "+919800000110", "101010101010");
        assertEquals(new BigDecimal("1260.00"), c.totalAmount());
        assertEquals(PaymentStatus.PAID_ONLINE, c.paymentStatus());

        BookingResponse ext = service.extendBooking(c.bookingId(), new ExtendBookingRequest(out.plusDays(2)), c.manageToken());
        assertEquals(new BigDecimal("2520.00"), ext.totalAmount());
        assertEquals(new BigDecimal("1260.00"), ext.amountPaid(), "what was already paid online is unchanged");
        assertEquals(new BigDecimal("1260.00"), ext.balanceDue());
        assertEquals(PaymentStatus.DUE_AT_CHECKIN, ext.paymentStatus());

        Booking stored = repository.findBookingById(c.bookingId()).orElseThrow();
        assertEquals(PaymentStatus.DUE_AT_CHECKIN, stored.paymentStatus());
        assertEquals(new BigDecimal("2520.00"), stored.totalAmount());
    }

    @Test
    @DisplayName("extend needs a valid manage token")
    void extendRequiresManageToken() {
        LocalDate in = LocalDate.of(2026, 10, 15);
        LocalDate out = LocalDate.of(2026, 10, 17);
        BookingResponse a = confirmPayAtHotel(102, in, out, "+919800000111");
        BookingResponse b = confirmPayAtHotel(103, in, out, "+919800000112");
        ExtendBookingRequest ext = new ExtendBookingRequest(out.plusDays(1));

        assertThrows(BookingForbiddenException.class, () -> service.extendBooking(a.bookingId(), ext, null));
        assertThrows(BookingForbiddenException.class, () -> service.extendBooking(a.bookingId(), ext, "garbage"));
        assertThrows(BookingForbiddenException.class, () -> service.extendBooking(a.bookingId(), ext, b.manageToken()));
        assertEquals(out, repository.findBookingById(a.bookingId()).orElseThrow().checkOutDate());
    }

    @Test
    @DisplayName("a stay that has already ended cannot be extended")
    void cannotExtendEndedStay() {
        LocalDate in = LocalDate.of(2026, 10, 2);
        LocalDate out = LocalDate.of(2026, 10, 3);
        BookingResponse c = confirmPayAtHotel(104, in, out, "+919800000113");
        clock.plusSeconds(3 * 86400L);    // 2026-10-04 IST
        BookingValidationException ex = assertThrows(BookingValidationException.class,
                () -> service.extendBooking(c.bookingId(), new ExtendBookingRequest(out.plusDays(1)), c.manageToken()));
        assertTrue(ex.getMessage().contains("already ended"), ex.getMessage());
    }

    @Test
    @DisplayName("pay balance: a UTR settles the extension amount, is idempotent, and cannot be reused elsewhere")
    void payBalanceSettlesExtension() {
        LocalDate in = LocalDate.of(2026, 10, 15);
        LocalDate out = LocalDate.of(2026, 10, 17);
        BookingResponse c = confirmUpi(105, in, out, "+919800000114", "202020202020");
        service.extendBooking(c.bookingId(), new ExtendBookingRequest(out.plusDays(2)), c.manageToken());

        BookingResponse paid = service.payBalance(c.bookingId(), new PayBalanceRequest("303030303030"), c.manageToken());
        assertEquals(PaymentStatus.PAID_ONLINE, paid.paymentStatus());
        assertEquals(paid.totalAmount(), paid.amountPaid());
        assertEquals(0, paid.balanceDue().signum());
        assertEquals("202020202020,303030303030", paid.paymentReference());

        // Replaying the same UTR is a no-op success; a nothing-due booking rejects a NEW UTR.
        assertEquals(PaymentStatus.PAID_ONLINE,
                service.payBalance(c.bookingId(), new PayBalanceRequest("303030303030"), c.manageToken()).paymentStatus());
        assertThrows(BookingValidationException.class,
                () -> service.payBalance(c.bookingId(), new PayBalanceRequest("404040404040"), c.manageToken()));

        // 303030303030 is now spent: another booking cannot claim it.
        BookingResponse other = confirmPayAtHotel(106, in, out, "+919800000115");
        BookingValidationException ex = assertThrows(BookingValidationException.class,
                () -> service.payBalance(other.bookingId(), new PayBalanceRequest("303030303030"), other.manageToken()));
        assertTrue(ex.getMessage().contains("already been used"), ex.getMessage());
        assertEquals(PaymentStatus.DUE_AT_CHECKIN, repository.findBookingById(other.bookingId()).orElseThrow().paymentStatus());
    }

    @Test
    @DisplayName("pay balance on a pay-at-hotel booking settles it online (method becomes UPI_QR) and needs the manage token")
    void payBalanceOnPayAtHotelBooking() {
        LocalDate in = LocalDate.of(2026, 10, 15);
        LocalDate out = LocalDate.of(2026, 10, 16);
        BookingResponse c = confirmPayAtHotel(107, in, out, "+919800000116");

        assertThrows(BookingForbiddenException.class,
                () -> service.payBalance(c.bookingId(), new PayBalanceRequest("606060606060"), null));
        assertThrows(BookingValidationException.class,
                () -> service.payBalance(c.bookingId(), new PayBalanceRequest("not-a-utr"), c.manageToken()));

        BookingResponse paid = service.payBalance(c.bookingId(), new PayBalanceRequest("606060606060"), c.manageToken());
        assertEquals(PaymentMethod.UPI_QR, paid.paymentMethod());
        assertEquals(PaymentStatus.PAID_ONLINE, paid.paymentStatus());
        assertEquals("606060606060", paid.paymentReference());
    }

    // ================================================================ 9. GST invoice data

    @Test
    @DisplayName("invoice data: tariff, extra-guest charges and 2.5% CGST + 2.5% SGST add up to the total")
    void gstBreakdownIsItemised() {
        // Room 101 (double bed, base occupancy 2): 3 guests => 1 extra guest at Rs.100/night. 2 nights.
        BookingResponse hold = service.holdRoom(new HoldBookingRequest(101, LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 17), 3, "Guest", "+919800000117", "g@example.com"));
        BookingResponse c = service.confirmBooking(hold.bookingId(), ConfirmBookingRequest.payAtHotel(hold.bookingToken()));

        assertEquals(new BigDecimal("1200.00"), c.roomTariffSubtotal());
        assertEquals(1, c.extraGuestCount());
        assertEquals(new BigDecimal("200.00"), c.extraGuestChargesSubtotal());
        assertEquals(new BigDecimal("1400.00"), c.subtotal());
        assertEquals(new BigDecimal("70.00"), c.gstAmount());
        assertEquals(new BigDecimal("35.00"), c.cgstAmount());
        assertEquals(new BigDecimal("35.00"), c.sgstAmount());
        assertEquals(c.gstAmount(), c.cgstAmount().add(c.sgstAmount()));
        assertEquals(new BigDecimal("1470.00"), c.totalAmount());
        assertEquals(c.subtotal(), c.roomTariffSubtotal().add(c.extraGuestChargesSubtotal()));
    }

    @Test
    @DisplayName("PaymentStatus.forBalance: fully paid => PAID_ONLINE, anything outstanding => DUE_AT_CHECKIN")
    void paymentStatusForBalance() {
        assertEquals(PaymentStatus.PAID_ONLINE, PaymentStatus.forBalance(new BigDecimal("100.00"), new BigDecimal("100.00")));
        assertEquals(PaymentStatus.DUE_AT_CHECKIN, PaymentStatus.forBalance(new BigDecimal("100.00"), new BigDecimal("40.00")));
        assertEquals(PaymentStatus.DUE_AT_CHECKIN, PaymentStatus.forBalance(new BigDecimal("100.00"), BigDecimal.ZERO));
    }

    // ================================================================ 10. Phone access

    @Test
    @DisplayName("access: the guest's phone (any formatting) returns a manage token; a wrong phone gets 403")
    void accessByPhone() {
        LocalDate in = LocalDate.of(2026, 10, 15);
        LocalDate out = LocalDate.of(2026, 10, 16);
        BookingResponse c = confirmPayAtHotel(108, in, out, "+919800000118");

        BookingResponse access = service.accessBooking(c.bookingId(), new AccessBookingRequest("98000 00118"));
        assertNotNull(access.manageToken());
        assertEquals(c.bookingId(), access.bookingId());
        assertEquals(BookingStatus.CANCELLED,
                service.cancelBooking(c.bookingId(), null, access.manageToken()).status(),
                "the access token works for cancel");

        assertThrows(BookingForbiddenException.class,
                () -> service.accessBooking(c.bookingId(), new AccessBookingRequest("+919800000999")));
        assertThrows(BookingNotFoundException.class,
                () -> service.accessBooking("no-such-id", new AccessBookingRequest("+919800000118")));
    }

    @Test
    @DisplayName("access is refused for a booking that is only a HOLD")
    void accessRefusedForHold() {
        BookingResponse hold = holdFor(109, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16), "+919800000119");
        assertThrows(BookingValidationException.class,
                () -> service.accessBooking(hold.bookingId(), new AccessBookingRequest("+919800000119")));
    }

    // ================================================================ 11. Cancellation

    @Test
    @DisplayName("free cancellation: booking becomes CANCELLED, inventory rows are deleted and the room can be re-booked at once")
    void freeCancellationReleasesInventory() {
        LocalDate in = LocalDate.of(2026, 10, 15);
        LocalDate out = LocalDate.of(2026, 10, 18);
        BookingResponse c = confirmUpi(110, in, out, "+919800000120", "121212121212");
        assertEquals(3, repository.findInventoriesForRoomAndDates(110, List.of(in, in.plusDays(1), in.plusDays(2))).size());
        assertTrue(c.cancellable());

        BookingResponse cancelled = service.cancelBooking(c.bookingId(), null, c.manageToken());

        assertEquals(BookingStatus.CANCELLED, cancelled.status());
        assertFalse(cancelled.lateCancellation());
        assertEquals(clock.instant(), cancelled.cancelledAt());
        assertFalse(cancelled.cancellable());
        assertEquals(c.amountPaid(), cancelled.refundableAmount(), "an on-time cancel of a UPI-paid booking is fully refundable");

        Booking stored = repository.findBookingById(c.bookingId()).orElseThrow();
        assertEquals(BookingStatus.CANCELLED, stored.status());
        assertFalse(stored.lateCancellation());
        assertTrue(repository.findInventoriesForRoomAndDates(110, List.of(in, in.plusDays(1), in.plusDays(2))).isEmpty(),
                "every inventory night must be unlocked");

        BookingResponse rebooked = holdFor(110, in, out, "+919800000121");
        assertEquals(BookingStatus.HOLD, rebooked.status());
    }

    @Test
    @DisplayName("cancel is idempotent: cancelling an already-cancelled booking returns it unchanged (no re-stamp, no error)")
    void cancelIsIdempotent() {
        LocalDate in = LocalDate.of(2026, 10, 15);
        LocalDate out = LocalDate.of(2026, 10, 16);
        BookingResponse c = confirmPayAtHotel(111, in, out, "+919800000122");

        BookingResponse first = service.cancelBooking(c.bookingId(), null, c.manageToken());
        clock.plusSeconds(3600);
        BookingResponse second = service.cancelBooking(c.bookingId(), null, c.manageToken());

        assertEquals(BookingStatus.CANCELLED, second.status());
        assertEquals(first.cancelledAt(), second.cancelledAt(), "the original cancellation time is kept");
    }

    @Test
    @DisplayName("idempotent cancel must not delete a room the guest no longer owns")
    void repeatedCancelLeavesNewOwnerAlone() {
        LocalDate in = LocalDate.of(2026, 10, 15);
        LocalDate out = LocalDate.of(2026, 10, 16);
        BookingResponse c = confirmPayAtHotel(112, in, out, "+919800000123");
        service.cancelBooking(c.bookingId(), null, c.manageToken());

        BookingResponse newOwner = holdFor(112, in, out, "+919800000124");
        service.cancelBooking(c.bookingId(), null, c.manageToken());   // repeat

        RoomInventory row = repository.findInventoriesForRoomAndDates(112, List.of(in)).get(0);
        assertEquals(newOwner.bookingId(), row.bookingId());
        assertEquals(InventoryStatus.HOLD, row.status());
    }

    @Test
    @DisplayName("cancel requires the manage token: missing, garbage or another booking's token => 403 and nothing changes")
    void cancelRequiresManageToken() {
        LocalDate in = LocalDate.of(2026, 10, 15);
        LocalDate out = LocalDate.of(2026, 10, 16);
        BookingResponse a = confirmPayAtHotel(113, in, out, "+919800000125");
        BookingResponse b = confirmPayAtHotel(114, in, out, "+919800000126");

        assertThrows(BookingForbiddenException.class, () -> service.cancelBooking(a.bookingId(), null, null));
        assertThrows(BookingForbiddenException.class, () -> service.cancelBooking(a.bookingId(), null, "garbage"));
        assertThrows(BookingForbiddenException.class, () -> service.cancelBooking(a.bookingId(), null, b.manageToken()));

        assertEquals(BookingStatus.CONFIRMED, repository.findBookingById(a.bookingId()).orElseThrow().status());
        assertEquals(1, repository.findInventoriesForRoomAndDates(113, List.of(in)).size());
    }

    @Test
    @DisplayName("late cancellation (inside 24h of check-in 12:00 IST) needs explicit acknowledgement and is flagged")
    void lateCancellationIsFlagged() {
        // Now = 2026-10-01 15:30 IST. Check-in 2026-10-02 12:00 IST -> free deadline was 2026-10-01 12:00 IST (passed).
        LocalDate in = LocalDate.of(2026, 10, 2);
        LocalDate out = LocalDate.of(2026, 10, 3);
        BookingResponse c = confirmUpi(115, in, out, "+919800000127", "131313131313");
        assertTrue(c.cancellable(), "still cancellable (late) until check-in time");

        BookingValidationException ex = assertThrows(BookingValidationException.class,
                () -> service.cancelBooking(c.bookingId(), null, c.manageToken()));
        assertTrue(ex.getMessage().contains("late cancellation"), ex.getMessage());
        assertThrows(BookingValidationException.class,
                () -> service.cancelBooking(c.bookingId(), new CancelBookingRequest(false), c.manageToken()));
        assertEquals(BookingStatus.CONFIRMED, repository.findBookingById(c.bookingId()).orElseThrow().status());

        BookingResponse cancelled = service.cancelBooking(c.bookingId(), new CancelBookingRequest(true), c.manageToken());
        assertEquals(BookingStatus.CANCELLED, cancelled.status());
        assertTrue(cancelled.lateCancellation());
        assertEquals(0, cancelled.refundableAmount().signum(), "a late cancellation is not auto-refundable");
        assertTrue(repository.findBookingById(c.bookingId()).orElseThrow().lateCancellation());
        assertTrue(repository.findInventoriesForRoomAndDates(115, List.of(in)).isEmpty());
    }

    @Test
    @DisplayName("policy boundary: exactly at check-in 12:00 IST minus 24h is still free; one second later is late")
    void freeCancellationBoundary() {
        LocalDate in = LocalDate.of(2026, 10, 5);
        LocalDate out = LocalDate.of(2026, 10, 6);
        BookingResponse a = confirmPayAtHotel(116, in, out, "+919800000128");
        BookingResponse b = confirmPayAtHotel(215, in, out, "+919800000129");

        Instant deadline = CancellationPolicy.freeCancellationDeadline(in);
        assertEquals(Instant.parse("2026-10-04T06:30:00Z"), deadline, "2026-10-04 12:00 IST");
        clock.plusSeconds(secondsUntil(deadline));

        BookingResponse free = service.cancelBooking(a.bookingId(), null, a.manageToken());
        assertEquals(BookingStatus.CANCELLED, free.status());
        assertFalse(free.lateCancellation());

        clock.plusSeconds(1);
        assertThrows(BookingValidationException.class, () -> service.cancelBooking(b.bookingId(), null, b.manageToken()));
        BookingResponse late = service.cancelBooking(b.bookingId(), new CancelBookingRequest(true), b.manageToken());
        assertTrue(late.lateCancellation());
    }

    @Test
    @DisplayName("once check-in time (12:00 IST) is reached the booking can no longer be cancelled online")
    void cannotCancelAfterCheckInTime() {
        LocalDate in = LocalDate.of(2026, 10, 3);
        LocalDate out = LocalDate.of(2026, 10, 4);
        BookingResponse c = confirmPayAtHotel(211, in, out, "+919800000130");

        Instant checkInAt = CancellationPolicy.checkInInstant(in);
        clock.plusSeconds(secondsUntil(checkInAt) - 1);
        assertTrue(service.getBooking(c.bookingId()).cancellable(), "one second before check-in is still cancellable (late)");

        clock.plusSeconds(1);
        assertFalse(service.getBooking(c.bookingId()).cancellable());
        BookingValidationException ex = assertThrows(BookingValidationException.class,
                () -> service.cancelBooking(c.bookingId(), new CancelBookingRequest(true), c.manageToken()));
        assertTrue(ex.getMessage().contains("can no longer be cancelled"), ex.getMessage());
        assertEquals(BookingStatus.CONFIRMED, repository.findBookingById(c.bookingId()).orElseThrow().status());
    }

    @Test
    @DisplayName("only CONFIRMED bookings can be cancelled; a cancelled booking cannot be extended, paid or re-confirmed")
    void cancelledBookingIsTerminal() {
        LocalDate in = LocalDate.of(2026, 10, 15);
        LocalDate out = LocalDate.of(2026, 10, 16);

        // A HOLD (even with a validly minted manage token) is not cancellable: it is released instead.
        BookingResponse hold = holdFor(212, in, out, "+919800000131");
        Booking holdBooking = repository.findBookingById(hold.bookingId()).orElseThrow();
        String manageForHold = tokens.generateManageToken(holdBooking, clock.instant().getEpochSecond());
        BookingValidationException ex = assertThrows(BookingValidationException.class,
                () -> service.cancelBooking(hold.bookingId(), null, manageForHold));
        assertTrue(ex.getMessage().contains("Only CONFIRMED"), ex.getMessage());

        BookingResponse c = confirmPayAtHotel(213, in, out, "+919800000132");
        service.cancelBooking(c.bookingId(), null, c.manageToken());
        assertThrows(BookingValidationException.class,
                () -> service.extendBooking(c.bookingId(), new ExtendBookingRequest(out.plusDays(1)), c.manageToken()));
        assertThrows(BookingValidationException.class,
                () -> service.payBalance(c.bookingId(), new PayBalanceRequest("818181818181"), c.manageToken()));
        assertThrows(BookingValidationException.class,
                () -> service.confirmBooking(c.bookingId(), ConfirmBookingRequest.payAtHotel(hold.bookingToken())));
    }

    @Test
    @DisplayName("cancelling a stay that was extended frees every night, including the extension nights")
    void cancelFreesExtendedNights() {
        LocalDate in = LocalDate.of(2026, 10, 15);
        LocalDate out = LocalDate.of(2026, 10, 16);
        BookingResponse c = confirmPayAtHotel(214, in, out, "+919800000133");
        service.extendBooking(c.bookingId(), new ExtendBookingRequest(out.plusDays(2)), c.manageToken());
        assertEquals(3, repository.findInventoriesForRoomAndDates(214, List.of(in, in.plusDays(1), in.plusDays(2))).size());

        service.cancelBooking(c.bookingId(), null, c.manageToken());
        assertTrue(repository.findInventoriesForRoomAndDates(214, List.of(in, in.plusDays(1), in.plusDays(2))).isEmpty());
    }
}

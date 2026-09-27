package com.hotel;

import com.hotel.controller.BookingController;
import com.hotel.controller.StaffController;
import com.hotel.dto.*;
import com.hotel.exception.StaffUnauthorizedException;
import com.hotel.model.Booking;
import com.hotel.notification.EmailGateway;
import com.hotel.notification.NotificationService;
import com.hotel.security.BookingTokenService;
import com.hotel.security.StaffAuthService;
import com.hotel.service.BookingService;
import com.hotel.service.StaffService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/** Phase 4: staff PIN authentication, the token guard on every /api/staff endpoint, and 401 mapping. */
class StaffAuthTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String SIGNING_KEY = "unit-test-signing-key-that-is-long-enough-123";

    private BookingConcurrencyTest.InMemoryBookingRepository repository;
    private BookingConcurrencyTest.MutableClock clock;

    @BeforeEach
    void setUp() {
        repository = new BookingConcurrencyTest.InMemoryBookingRepository();
        clock = new BookingConcurrencyTest.MutableClock(Instant.parse("2026-10-01T10:00:00Z"), IST);
    }

    private StaffAuthService auth(String pin, int hours) {
        return new StaffAuthService(pin, SIGNING_KEY, hours, clock);
    }

    @Test
    @DisplayName("the default staff PIN is 1439 when HOTEL_STAFF_PIN is not configured")
    void defaultPinIs1439() {
        StaffAuthService a = auth("", 10);
        String token = a.login("1439");
        assertDoesNotThrow(() -> a.requireStaff(token, null));
        assertThrows(StaffUnauthorizedException.class, () -> a.login("0000"));
    }

    @Test
    @DisplayName("a configured PIN replaces the default (1439 no longer works)")
    void configuredPinWins() {
        StaffAuthService a = auth("482913", 10);
        assertDoesNotThrow(() -> a.requireStaff(a.login("482913"), null));
        assertThrows(StaffUnauthorizedException.class, () -> a.login("1439"));
    }

    @Test
    @DisplayName("wrong, empty and null PINs are rejected with 401-type errors")
    void wrongPinRejected() {
        StaffAuthService a = auth("482913", 10);
        assertThrows(StaffUnauthorizedException.class, () -> a.login("482914"));
        assertThrows(StaffUnauthorizedException.class, () -> a.login(""));
        assertThrows(StaffUnauthorizedException.class, () -> a.login(null));
        assertThrows(StaffUnauthorizedException.class, () -> a.login("4829130"));
    }

    @Test
    @DisplayName("a PIN shorter than 4 characters is refused at start-up")
    void tooShortPinRefused() {
        assertThrows(IllegalStateException.class, () -> auth("123", 10));
    }

    @Test
    @DisplayName("the token is accepted via X-Staff-Token and via Authorization: Bearer")
    void tokenAcceptedInEitherHeader() {
        StaffAuthService a = auth("482913", 10);
        String token = a.login("482913");
        assertDoesNotThrow(() -> a.requireStaff(token, null));
        assertDoesNotThrow(() -> a.requireStaff(null, "Bearer " + token));
        assertDoesNotThrow(() -> a.requireStaff("", "bearer " + token));
        assertThrows(StaffUnauthorizedException.class, () -> a.requireStaff(null, token), "Bearer prefix is required");
    }

    @Test
    @DisplayName("missing or garbage tokens are rejected")
    void missingOrGarbageToken() {
        StaffAuthService a = auth("482913", 10);
        assertThrows(StaffUnauthorizedException.class, () -> a.requireStaff(null, null));
        assertThrows(StaffUnauthorizedException.class, () -> a.requireStaff("  ", ""));
        assertThrows(StaffUnauthorizedException.class, () -> a.requireStaff("not-a-jws", null));
        assertThrows(StaffUnauthorizedException.class, () -> a.requireStaff("a.b.c", null));
    }

    @Test
    @DisplayName("a token signed with a different secret is rejected")
    void forgedTokenRejected() {
        StaffAuthService other = new StaffAuthService("482913", "some-other-signing-secret-of-good-length-99", 10, clock);
        String forged = other.login("482913");
        assertThrows(StaffUnauthorizedException.class, () -> auth("482913", 10).requireStaff(forged, null));
    }

    @Test
    @DisplayName("a tampered token (payload altered) is rejected")
    void tamperedTokenRejected() {
        StaffAuthService a = auth("482913", 10);
        String token = a.login("482913");
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA" + "." + parts[2];
        assertThrows(StaffUnauthorizedException.class, () -> a.requireStaff(tampered, null));
    }

    @Test
    @DisplayName("guest hold and manage tokens can never act as a staff token")
    void guestTokensAreNotStaffTokens() {
        BookingTokenService bookingTokens = new BookingTokenService(SIGNING_KEY);
        BookingService bookings = new BookingService(repository, clock, bookingTokens);
        BookingResponse hold = bookings.holdRoom(new HoldBookingRequest(
                101, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3), 2, "Guest One", "9800000001", "g@example.com"));
        BookingResponse confirmed = bookings.confirmBooking(hold.bookingId(), ConfirmBookingRequest.payAtHotel(hold.bookingToken()));

        StaffAuthService a = auth("482913", 10);
        assertThrows(StaffUnauthorizedException.class, () -> a.requireStaff(hold.bookingToken(), null));
        assertThrows(StaffUnauthorizedException.class, () -> a.requireStaff(confirmed.manageToken(), null));
    }

    @Test
    @DisplayName("the session lasts the configured hours (10h) and expires after")
    void tokenExpires() {
        StaffAuthService a = auth("482913", 10);
        String token = a.login("482913");
        clock.plusSeconds(10 * 3600L - 60);
        assertDoesNotThrow(() -> a.requireStaff(token, null), "still valid just before 10h");
        clock.plusSeconds(120);
        StaffUnauthorizedException ex = assertThrows(StaffUnauthorizedException.class, () -> a.requireStaff(token, null));
        assertTrue(ex.getMessage().contains("expired"), ex.getMessage());
    }

    @Test
    @DisplayName("token lifetime is clamped to the 8-12 hour window")
    void lifetimeClamped() {
        assertEquals(8 * 3600L, auth("482913", 1).tokenLifetimeSeconds());
        assertEquals(10 * 3600L, auth("482913", 10).tokenLifetimeSeconds());
        assertEquals(12 * 3600L, auth("482913", 100).tokenLifetimeSeconds());
    }

    @Test
    @DisplayName("changing the PIN signs every existing session out")
    void changingPinInvalidatesSessions() {
        String token = auth("482913", 10).login("482913");
        StaffAuthService rotated = auth("777888", 10);
        StaffUnauthorizedException ex = assertThrows(StaffUnauthorizedException.class, () -> rotated.requireStaff(token, null));
        assertTrue(ex.getMessage().contains("PIN"), ex.getMessage());
    }

    @Test
    @DisplayName("every /api/staff endpoint except login answers 401 without a staff token")
    void everyStaffEndpointIsGuarded() {
        StaffAuthService a = auth("482913", 10);
        StaffController controller = new StaffController(a, new StaffService(repository, clock),
                new NotificationService("", noopGateway()));
        String id = "00000000-0000-0000-0000-000000000000";

        assertThrows(StaffUnauthorizedException.class, () -> controller.dashboard(null, null));
        assertThrows(StaffUnauthorizedException.class, () -> controller.checkIn(id, null, null));
        assertThrows(StaffUnauthorizedException.class, () -> controller.checkOut(id, null, null));
        assertThrows(StaffUnauthorizedException.class, () -> controller.verifyPayment(id, null, null, null));
        assertThrows(StaffUnauthorizedException.class, () -> controller.blockRoom(101, null, null,
                new BlockRoomRequest(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6))));
        assertThrows(StaffUnauthorizedException.class, () -> controller.unblockRoom(101, null, null, null));
        assertThrows(StaffUnauthorizedException.class, () -> controller.walkIn(null, null,
                new WalkInRequest(101, null, LocalDate.of(2026, 10, 2), 2, "Walk In", "9800000002", null, null, null)));

        // ...and with a valid token the same controller works
        String token = a.login("482913");
        assertEquals(200, controller.dashboard(token, null).getStatusCode().value());
    }

    @Test
    @DisplayName("login endpoint returns a token + expiry; wrong PIN is a 401 error")
    void loginEndpoint() {
        StaffController controller = new StaffController(auth("482913", 10), new StaffService(repository, clock),
                new NotificationService("", noopGateway()));
        ResponseEntity<StaffLoginResponse> ok = controller.login(new StaffLoginRequest("482913"));
        assertEquals(200, ok.getStatusCode().value());
        assertNotNull(ok.getBody().token());
        assertEquals(10 * 3600L, ok.getBody().expiresInSeconds());
        assertEquals(clock.instant().plusSeconds(10 * 3600L), ok.getBody().expiresAt());
        assertThrows(StaffUnauthorizedException.class, () -> controller.login(new StaffLoginRequest("000000")));
    }

    @Test
    @DisplayName("a failing e-mail gateway never fails the guest's booking confirmation")
    void confirmSucceedsWhenEmailFails() {
        BookingService bookings = new BookingService(repository, clock, new BookingTokenService(SIGNING_KEY));
        EmailGateway broken = (from, to, subject, html, text) -> { throw new RuntimeException("SES is down"); };
        BookingController controller = new BookingController(bookings, new NotificationService("hotel@example.com", broken));

        BookingResponse hold = bookings.holdRoom(new HoldBookingRequest(
                102, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3), 2, "Guest Two", "9800000003", "g2@example.com"));
        ResponseEntity<BookingResponse> r = controller.confirmBooking(hold.bookingId(), hold.bookingToken(),
                ConfirmBookingRequest.payAtHotel(hold.bookingToken()));
        assertEquals(200, r.getStatusCode().value());
        assertEquals("CONFIRMED", r.getBody().status().name());
        Booking stored = repository.findBookingById(hold.bookingId()).orElseThrow();
        assertEquals("CONFIRMED", stored.status().name(), "the booking transaction stays committed");
    }

    static EmailGateway noopGateway() {
        return (from, to, subject, html, text) -> { };
    }
}

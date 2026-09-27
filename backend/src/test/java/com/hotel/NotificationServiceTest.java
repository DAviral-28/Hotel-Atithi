package com.hotel;

import com.hotel.dto.BookingResponse;
import com.hotel.dto.ConfirmBookingRequest;
import com.hotel.dto.HoldBookingRequest;
import com.hotel.dto.StaffBookingView;
import com.hotel.dto.WalkInRequest;
import com.hotel.notification.EmailGateway;
import com.hotel.notification.EmailMessage;
import com.hotel.notification.NotificationService;
import com.hotel.security.BookingTokenService;
import com.hotel.service.BookingService;
import com.hotel.service.StaffService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Phase 4: the optional, zero-cost confirmation e-mail (AWS SES when configured, otherwise log-only). */
class NotificationServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Records what would have been sent through SES. */
    private static final class RecordingGateway implements EmailGateway {
        final List<String[]> sent = new ArrayList<>();
        RuntimeException failWith;

        @Override
        public void send(String from, String to, String subject, String html, String text) {
            if (failWith != null) {
                throw failWith;
            }
            sent.add(new String[]{from, to, subject, html, text});
        }
    }

    private BookingConcurrencyTest.InMemoryBookingRepository repository;
    private BookingService bookings;
    private RecordingGateway gateway;

    @BeforeEach
    void setUp() {
        repository = new BookingConcurrencyTest.InMemoryBookingRepository();
        BookingConcurrencyTest.MutableClock clock =
                new BookingConcurrencyTest.MutableClock(Instant.parse("2026-10-01T10:00:00Z"), IST);
        bookings = new BookingService(repository, clock, new BookingTokenService(""));
        gateway = new RecordingGateway();
    }

    private BookingResponse confirmed(String name, String email, boolean upi) {
        BookingResponse h = bookings.holdRoom(new HoldBookingRequest(
                113, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 7), 2, name, "9800000009", email));
        return bookings.confirmBooking(h.bookingId(), upi
                ? ConfirmBookingRequest.upi("UTR123456789", h.bookingToken())
                : ConfirmBookingRequest.payAtHotel(h.bookingToken()));
    }

    @Test
    @DisplayName("without AWS_SES_SENDER_EMAIL nothing is sent and nothing fails")
    void disabledWhenSenderNotConfigured() {
        NotificationService n = new NotificationService("", gateway);
        assertFalse(n.isEmailEnabled());
        assertFalse(n.sendBookingConfirmation(confirmed("Ravi Kumar", "ravi@example.com", false)));
        assertTrue(gateway.sent.isEmpty());
        assertFalse(new NotificationService(null, gateway).isEmailEnabled());
        assertFalse(new NotificationService("   ", gateway).isEmailEnabled());
    }

    @Test
    @DisplayName("with a sender configured the confirmation is handed to the gateway from that sender to the guest")
    void sendsWhenConfigured() {
        NotificationService n = new NotificationService("bookings@hotelatithi.example", gateway);
        assertTrue(n.isEmailEnabled());
        BookingResponse b = confirmed("Ravi Kumar", "ravi@example.com", false);
        assertTrue(n.sendBookingConfirmation(b));
        assertEquals(1, gateway.sent.size());
        String[] mail = gateway.sent.get(0);
        assertEquals("bookings@hotelatithi.example", mail[0]);
        assertEquals("ravi@example.com", mail[1]);
        assertTrue(mail[2].contains("Room 113"), mail[2]);
    }

    @Test
    @DisplayName("the HTML e-mail carries booking ID, room, 12:00 PM IST check-in/out, payment status, address and phone")
    void htmlContainsEverythingTheGuestNeeds() {
        NotificationService n = new NotificationService("bookings@hotelatithi.example", gateway);
        BookingResponse b = confirmed("Ravi Kumar", "ravi@example.com", false);
        EmailMessage m = n.buildConfirmationEmail(b);

        for (String body : new String[]{m.htmlBody(), m.textBody()}) {
            assertTrue(body.contains(b.bookingId()), "booking id");
            assertTrue(body.contains("113"), "room");
            assertTrue(body.contains("Mon, 5 Oct 2026") || body.contains("Mon, 5 Oct 2026, 12:00 PM IST"), "check-in date");
            assertTrue(body.contains("Wed, 7 Oct 2026"), "check-out date");
            assertTrue(body.contains("12:00 PM IST"), "check-in/out time");
            assertTrue(body.contains("₹2,310.00 due at check-in"), "payment status (2 nights AC + GST): " + body);
            assertTrue(body.contains("Near Railway Station"), "directions");
            assertTrue(body.contains("+91 94523 14397"), "reception phone");
        }
        assertTrue(m.htmlBody().startsWith("<!DOCTYPE html>"));
        assertTrue(m.htmlBody().contains("Hotel Atithi"));
        assertEquals("ravi@example.com", m.to());
    }

    @Test
    @DisplayName("a UPI-paid booking says 'Paid online (UPI)', a desk-paid walk-in says 'Paid at the front desk'")
    void paymentLineVariants() {
        NotificationService n = new NotificationService("bookings@hotelatithi.example", gateway);
        assertTrue(n.buildConfirmationEmail(confirmed("Asha Rao", "asha@example.com", true)).textBody().contains("Payment: Paid online (UPI)"));

        StaffService staff = new StaffService(repository,
                new BookingConcurrencyTest.MutableClock(Instant.parse("2026-10-01T10:00:00Z"), IST));
        StaffBookingView walkIn = staff.walkIn(new WalkInRequest(101, null, LocalDate.of(2026, 10, 2), 2,
                "Walk In", "9800000010", "walk@example.com", new java.math.BigDecimal("630.00"), null));
        assertTrue(n.sendBookingConfirmation(walkIn));
        assertTrue(gateway.sent.get(0)[4].contains("Payment: Paid at the front desk"), gateway.sent.get(0)[4]);
    }

    @Test
    @DisplayName("guest-supplied text is HTML-escaped, so a name can never inject markup into the e-mail")
    void htmlIsEscaped() {
        NotificationService n = new NotificationService("bookings@hotelatithi.example", gateway);
        BookingResponse b = confirmed("Ravi Kumar", "ravi@example.com", false);
        BookingResponse hostile = new BookingResponse(b.bookingId(), b.roomNumber(), b.checkInDate(), b.checkOutDate(),
                b.nights(), "<img src=x onerror=alert(1)> & \"Co\"", b.guestPhone(), b.guestEmail(), b.guestCount(),
                b.baseRatePerNight(), b.extraGuestRatePerNight(), b.subtotal(), b.gstAmount(), b.totalAmount(),
                b.status(), b.createdAt(), b.expiresAt(), b.remainingHoldSeconds(), b.paymentReference(), b.confirmedAt(),
                b.checkoutTime(), b.bookingToken(), b.roomTariffSubtotal(), b.extraGuestCount(), b.extraGuestChargesSubtotal(),
                b.cgstAmount(), b.sgstAmount(), b.paymentMethod(), b.paymentStatus(), b.amountPaid(), b.balanceDue(),
                b.paidAt(), b.cancelledAt(), b.lateCancellation(), b.cancellable(), b.freeCancellationDeadline(),
                b.refundableAmount(), b.manageToken());
        String html = n.buildConfirmationEmail(hostile).htmlBody();
        assertFalse(html.contains("<img"), html);
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt; &amp; &quot;Co&quot;"), html);
    }

    @Test
    @DisplayName("a gateway failure (SES down, unverified sender...) is swallowed: no exception, returns false")
    void gatewayFailureNeverPropagates() {
        NotificationService n = new NotificationService("bookings@hotelatithi.example", gateway);
        gateway.failWith = new IllegalStateException("MessageRejected: Email address is not verified");
        BookingResponse b = confirmed("Ravi Kumar", "ravi@example.com", false);
        assertDoesNotThrow(() -> assertFalse(n.sendBookingConfirmation(b)));
        // ...and a later retry (SES recovered) is still attempted, because the failed one was not remembered
        gateway.failWith = null;
        assertTrue(n.sendBookingConfirmation(b));
    }

    @Test
    @DisplayName("no e-mail address, or a malformed one, means no send attempt")
    void skipsWhenGuestHasNoUsableEmail() {
        NotificationService n = new NotificationService("bookings@hotelatithi.example", gateway);
        StaffService staff = new StaffService(repository,
                new BookingConcurrencyTest.MutableClock(Instant.parse("2026-10-01T10:00:00Z"), IST));
        StaffBookingView noEmail = staff.walkIn(new WalkInRequest(102, null, LocalDate.of(2026, 10, 2), 2,
                "No Mail", "9800000011", null, null, null));
        assertFalse(n.sendBookingConfirmation(noEmail));
        assertFalse(n.sendBookingConfirmation((BookingResponse) null));
        assertTrue(gateway.sent.isEmpty());
    }

    @Test
    @DisplayName("an idempotent confirm replay does not e-mail the guest twice")
    void noDuplicateEmailOnReplay() {
        NotificationService n = new NotificationService("bookings@hotelatithi.example", gateway);
        BookingResponse b = confirmed("Ravi Kumar", "ravi@example.com", false);
        assertTrue(n.sendBookingConfirmation(b));
        assertFalse(n.sendBookingConfirmation(b));
        assertEquals(1, gateway.sent.size());
    }
}

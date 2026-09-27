package com.hotel.notification;

import com.hotel.dto.BookingResponse;
import com.hotel.dto.StaffBookingView;
import com.hotel.model.PaymentMethod;
import com.hotel.model.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Zero-cost guest notifications. The booking confirmation e-mail is sent through AWS SES (free tier:
 * 3,000 messages/month from Lambda) ONLY when {@code AWS_SES_SENDER_EMAIL} is configured; otherwise the
 * message is logged and nothing else happens. Sending is best-effort: every failure is caught and
 * logged so a mail problem can never fail or roll back a booking.
 *
 * <p>Note: while an SES account is in the "sandbox" SES only delivers to verified addresses; ask AWS
 * for production access (free) to e-mail arbitrary guests. The WhatsApp share link on the confirmation
 * slip needs no setup at all.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    static final String HOTEL_NAME = "Hotel Atithi";
    static final String HOTEL_ADDRESS = "Station Road, Gular Naka, Banda, Uttar Pradesh 210001 (Near Railway Station)";
    static final String HOTEL_PHONE_DISPLAY = "+91 94523 14397";
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@<>\"]{1,64}@[^\\s@<>\"]+\\.[^\\s@<>\"]{2,}$");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH);
    private static final int MAX_REMEMBERED = 5000;

    private final String senderEmail;
    private final EmailGateway gateway;
    /** Booking IDs already e-mailed by this instance, so an idempotent confirm replay does not send twice. */
    private final Set<String> sent = ConcurrentHashMap.newKeySet();

    @Autowired
    public NotificationService(
            @Value("${AWS_SES_SENDER_EMAIL:${hotel.notifications.ses-sender-email:}}") String senderEmail,
            EmailGateway gateway
    ) {
        this.senderEmail = senderEmail == null ? "" : senderEmail.trim();
        this.gateway = gateway;
    }

    /** True when SES sending is configured. */
    public boolean isEmailEnabled() {
        return !senderEmail.isEmpty();
    }

    /**
     * Sends the confirmation e-mail for a CONFIRMED / walk-in booking, if SES is configured and the guest
     * gave an e-mail address. Never throws. Returns true only when an e-mail was handed to the gateway.
     */
    public boolean sendBookingConfirmation(BookingResponse booking) {
        return sendBookingConfirmation(booking == null ? null : ConfirmationDetails.from(booking));
    }

    /** Same as {@link #sendBookingConfirmation(BookingResponse)} for a front-desk walk-in. */
    public boolean sendBookingConfirmation(StaffBookingView booking) {
        return sendBookingConfirmation(booking == null ? null : ConfirmationDetails.from(booking));
    }

    public boolean sendBookingConfirmation(ConfirmationDetails booking) {
        try {
            if (booking == null) {
                return false;
            }
            String to = booking.guestEmail();
            if (to == null || !EMAIL.matcher(to.trim()).matches()) {
                log.info("Booking {}: no valid guest e-mail, confirmation e-mail skipped", booking.bookingId());
                return false;
            }
            if (!isEmailEnabled()) {
                log.info("Booking {}: AWS_SES_SENDER_EMAIL is not set, confirmation e-mail not sent (would go to a guest address)",
                        booking.bookingId());
                return false;
            }
            if (sent.contains(booking.bookingId())) {
                return false;
            }
            EmailMessage message = buildConfirmationEmail(booking);
            gateway.send(senderEmail, message.to(), message.subject(), message.htmlBody(), message.textBody());
            if (sent.size() >= MAX_REMEMBERED) {
                sent.clear();
            }
            sent.add(booking.bookingId());
            log.info("Booking {}: confirmation e-mail handed to SES", booking.bookingId());
            return true;
        } catch (Exception e) {
            log.warn("Booking {}: confirmation e-mail failed and was skipped: {}",
                    booking == null ? "?" : booking.bookingId(), e.toString());
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Rendering (pure functions, unit-tested)
    // ------------------------------------------------------------------------------------------

    /** Payment line shown to the guest, e.g. "Paid online (UPI)" or "₹1,260.00 due at check-in". */
    static String paymentLine(ConfirmationDetails b) {
        if (b.paymentStatus() == PaymentStatus.PAID_ONLINE) {
            return b.paymentMethod() == PaymentMethod.UPI_QR ? "Paid online (UPI)" : "Paid online";
        }
        if (b.paymentStatus() == PaymentStatus.PAID_AT_DESK) {
            return "Paid at the front desk";
        }
        BigDecimal due = b.balanceDue() == null ? BigDecimal.ZERO : b.balanceDue();
        return "₹" + money(due) + " due at check-in";
    }

    static String money(BigDecimal amount) {
        BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
        return String.format(Locale.ENGLISH, "%,.2f", rounded);
    }

    static String date(LocalDate d) {
        return DATE.format(d);
    }

    public EmailMessage buildConfirmationEmail(BookingResponse booking) {
        return buildConfirmationEmail(ConfirmationDetails.from(booking));
    }

    public EmailMessage buildConfirmationEmail(ConfirmationDetails b) {
        String subject = HOTEL_NAME + " - Booking confirmed (Room " + b.roomNumber() + ", " + date(b.checkInDate()) + ")";
        String payment = paymentLine(b);

        String text = HOTEL_NAME + " - Booking Confirmation\n\n"
                + "Dear " + b.guestName() + ",\n"
                + "Your booking is confirmed.\n\n"
                + "Booking ID: " + b.bookingId() + "\n"
                + "Room: " + b.roomNumber() + "\n"
                + "Check-in: " + date(b.checkInDate()) + ", 12:00 PM IST\n"
                + "Check-out: " + date(b.checkOutDate()) + ", 12:00 PM IST\n"
                + "Guests: " + b.guestCount() + "\n"
                + "Total: ₹" + money(b.totalAmount()) + " (incl. GST)\n"
                + "Payment: " + payment + "\n\n"
                + HOTEL_ADDRESS + "\n"
                + "Reception: " + HOTEL_PHONE_DISPLAY + "\n";

        String html = "<!DOCTYPE html><html><body style=\"margin:0;padding:0;background:#faf7f2;font-family:Arial,Helvetica,sans-serif;color:#292524\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td align=\"center\" style=\"padding:24px 12px\">"
                + "<table role=\"presentation\" width=\"560\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:560px;background:#ffffff;border:1px solid #e7e5e4;border-radius:12px\">"
                + "<tr><td style=\"background:#b45309;color:#ffffff;padding:20px 24px;border-radius:12px 12px 0 0\">"
                + "<div style=\"font-size:20px;font-weight:bold\">" + esc(HOTEL_NAME) + "</div>"
                + "<div style=\"font-size:13px;opacity:.9\">Booking Confirmation</div></td></tr>"
                + "<tr><td style=\"padding:24px\">"
                + "<p style=\"margin:0 0 12px;font-size:15px\">Dear " + esc(b.guestName()) + ",</p>"
                + "<p style=\"margin:0 0 16px;font-size:15px\">Your stay is confirmed. We look forward to welcoming you.</p>"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"6\" cellspacing=\"0\" style=\"font-size:14px;border-top:1px solid #e7e5e4\">"
                + row("Booking ID", b.bookingId())
                + row("Room", String.valueOf(b.roomNumber()))
                + row("Check-in", date(b.checkInDate()) + ", 12:00 PM IST")
                + row("Check-out", date(b.checkOutDate()) + ", 12:00 PM IST")
                + row("Guests", String.valueOf(b.guestCount()))
                + row("Total (incl. GST)", "₹" + money(b.totalAmount()))
                + row("Payment", payment)
                + "</table>"
                + "<p style=\"margin:20px 0 4px;font-size:14px\"><strong>Directions</strong><br>" + esc(HOTEL_ADDRESS) + "</p>"
                + "<p style=\"margin:8px 0 0;font-size:14px\"><strong>Reception</strong><br>" + esc(HOTEL_PHONE_DISPLAY) + "</p>"
                + "</td></tr></table></td></tr></table></body></html>";

        return new EmailMessage(b.guestEmail().trim(), subject, html, text);
    }

    private static String row(String label, String value) {
        return "<tr><td style=\"color:#78716c;border-bottom:1px solid #f5f5f4;width:40%\">" + esc(label)
                + "</td><td style=\"border-bottom:1px solid #f5f5f4;font-weight:bold\">" + esc(value) + "</td></tr>";
    }

    /** Minimal HTML escaping: guest-supplied text (name) must never inject markup into the e-mail. */
    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}

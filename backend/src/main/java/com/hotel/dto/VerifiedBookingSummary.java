package com.hotel.dto;

import com.hotel.model.Booking;
import com.hotel.model.BookingStatus;
import com.hotel.model.CancellationPolicy;
import com.hotel.model.PaymentMethod;
import com.hotel.model.PaymentStatus;
import com.hotel.util.InputSanitizer;
import com.hotel.util.PhoneNormalizer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Result of the two-factor lookup (Booking ID + last 4 digits / full phone). Richer than the
 * phone-only summary, but still masked and still without any token: to see full details or to
 * cancel / extend / pay, the guest proves the FULL phone number through {@code POST /{id}/access}.
 */
public record VerifiedBookingSummary(
        String bookingId,
        BookingStatus status,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        int nights,
        String roomLabel,
        int guestCount,
        String guestNameMasked,
        String guestPhoneMasked,
        String guestEmailMasked,
        BigDecimal totalAmount,
        BigDecimal amountPaid,
        BigDecimal balanceDue,
        PaymentMethod paymentMethod,
        PaymentStatus paymentStatus,
        boolean cancellable,
        Instant freeCancellationDeadline,
        boolean lateCancellation
) {
    public static VerifiedBookingSummary from(Booking b, long nowEpochSeconds) {
        boolean confirmed = b.status() == BookingStatus.CONFIRMED;
        Instant deadline = (confirmed || b.status() == BookingStatus.CANCELLED)
                ? CancellationPolicy.freeCancellationDeadline(b.checkInDate()) : null;
        return new VerifiedBookingSummary(
                b.bookingId(),
                b.status(),
                b.checkInDate(),
                b.checkOutDate(),
                (int) (b.checkOutDate().toEpochDay() - b.checkInDate().toEpochDay()),
                BookingSummaryResponse.roomLabel(b.roomNumber()),
                b.guestCount(),
                InputSanitizer.maskName(b.guestName()),
                PhoneNormalizer.mask(b.guestPhone()),
                InputSanitizer.maskEmail(b.guestEmail()),
                b.totalAmount(),
                b.amountPaid(),
                b.balanceDue(),
                b.paymentMethod(),
                b.paymentStatus(),
                confirmed && !CancellationPolicy.stayStarted(b.checkInDate(), nowEpochSeconds),
                deadline,
                b.lateCancellation());
    }
}

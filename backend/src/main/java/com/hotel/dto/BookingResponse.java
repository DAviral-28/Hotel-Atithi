package com.hotel.dto;

import com.hotel.config.RoomDirectoryConfig;
import com.hotel.model.Booking;
import com.hotel.model.BookingStatus;
import com.hotel.model.CancellationPolicy;
import com.hotel.model.PaymentMethod;
import com.hotel.model.PaymentStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/**
 * API view of a booking, including everything the GST tax invoice needs (tariff split, CGST/SGST,
 * extra-guest charges, payment method/status) and the cancellation-policy state.
 */
public record BookingResponse(
        String bookingId,
        int roomNumber,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        int nights,
        String guestName,
        String guestPhone,
        String guestEmail,
        int guestCount,
        BigDecimal baseRatePerNight,
        BigDecimal extraGuestRatePerNight,
        BigDecimal subtotal,
        BigDecimal gstAmount,
        BigDecimal totalAmount,
        BookingStatus status,
        Instant createdAt,
        Instant expiresAt,
        Long remainingHoldSeconds,
        String paymentReference,
        Instant confirmedAt,
        String checkoutTime,
        String bookingToken,
        // ---- Phase 2: invoice breakdown ----
        BigDecimal roomTariffSubtotal,
        int extraGuestCount,
        BigDecimal extraGuestChargesSubtotal,
        BigDecimal cgstAmount,
        BigDecimal sgstAmount,
        // ---- Phase 2: payment ----
        PaymentMethod paymentMethod,
        PaymentStatus paymentStatus,
        BigDecimal amountPaid,
        BigDecimal balanceDue,
        Instant paidAt,
        // ---- Phase 2: cancellation ----
        Instant cancelledAt,
        boolean lateCancellation,
        boolean cancellable,
        Instant freeCancellationDeadline,
        BigDecimal refundableAmount,
        /** Present only on confirm / phone-verified access responses; authorises cancel, extend and pay. */
        String manageToken
) {
    public static BookingResponse from(Booking booking, long nowEpochSeconds) {
        return from(booking, nowEpochSeconds, null, null);
    }

    public static BookingResponse from(Booking booking, long nowEpochSeconds, String bookingToken) {
        return from(booking, nowEpochSeconds, bookingToken, null);
    }

    public static BookingResponse from(Booking booking, long nowEpochSeconds, String bookingToken, String manageToken) {
        int nights = (int) (booking.checkOutDate().toEpochDay() - booking.checkInDate().toEpochDay());
        Long remainingSeconds = null;
        if (booking.status() == BookingStatus.HOLD) {
            remainingSeconds = Math.max(0, booking.expiresAt() - nowEpochSeconds);
        }

        BigDecimal nightsBd = BigDecimal.valueOf(nights);
        BigDecimal roomTariff = booking.baseRatePerNight().multiply(nightsBd);
        BigDecimal extraCharges = booking.extraGuestRatePerNight().multiply(nightsBd);
        int extraGuests = RoomDirectoryConfig.findByRoomNumber(booking.roomNumber())
                .map(r -> Math.max(0, booking.guestCount() - r.baseOccupancy()))
                .orElse(0);

        // GST is split evenly between the state and central components. SGST takes the remainder so
        // that CGST + SGST always equals gstAmount to the paisa.
        BigDecimal cgst = booking.gstAmount().divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        BigDecimal sgst = booking.gstAmount().subtract(cgst);

        boolean confirmed = booking.status() == BookingStatus.CONFIRMED;
        boolean cancellable = confirmed && !CancellationPolicy.stayStarted(booking.checkInDate(), nowEpochSeconds);
        Instant freeDeadline = (confirmed || booking.status() == BookingStatus.CANCELLED)
                ? CancellationPolicy.freeCancellationDeadline(booking.checkInDate()) : null;

        BigDecimal refundable = null;
        if (booking.status() == BookingStatus.CANCELLED) {
            refundable = booking.lateCancellation() ? BigDecimal.ZERO : booking.amountPaid();
        }

        return new BookingResponse(
                booking.bookingId(),
                booking.roomNumber(),
                booking.checkInDate(),
                booking.checkOutDate(),
                nights,
                booking.guestName(),
                booking.guestPhone(),
                booking.guestEmail(),
                booking.guestCount(),
                booking.baseRatePerNight(),
                booking.extraGuestRatePerNight(),
                booking.subtotal(),
                booking.gstAmount(),
                booking.totalAmount(),
                booking.status(),
                Instant.ofEpochSecond(booking.createdAt()),
                Instant.ofEpochSecond(booking.expiresAt()),
                remainingSeconds,
                booking.paymentReference(),
                booking.confirmedAt() != null ? Instant.ofEpochSecond(booking.confirmedAt()) : null,
                booking.checkoutTime(),
                bookingToken,
                roomTariff,
                extraGuests,
                extraCharges,
                cgst,
                sgst,
                booking.paymentMethod(),
                booking.paymentStatus(),
                booking.amountPaid(),
                booking.balanceDue(),
                booking.paidAt() != null ? Instant.ofEpochSecond(booking.paidAt()) : null,
                booking.cancelledAt() != null ? Instant.ofEpochSecond(booking.cancelledAt()) : null,
                booking.lateCancellation(),
                cancellable,
                freeDeadline,
                refundable,
                manageToken
        );
    }
}

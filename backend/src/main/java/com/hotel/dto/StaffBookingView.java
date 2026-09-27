package com.hotel.dto;

import com.hotel.model.Booking;
import com.hotel.model.BookingStatus;
import com.hotel.model.PaymentMethod;
import com.hotel.model.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A booking as the front desk sees it: full guest name / phone / e-mail (staff are authenticated),
 * payment reconciliation state, and the desk timestamps. Never contains a hold or manage token.
 */
public record StaffBookingView(
        String bookingId,
        int roomNumber,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        int nights,
        String guestName,
        String guestPhone,
        String guestEmail,
        int guestCount,
        BookingStatus status,
        BigDecimal totalAmount,
        BigDecimal amountPaid,
        BigDecimal balanceDue,
        PaymentMethod paymentMethod,
        PaymentStatus paymentStatus,
        String paymentReference,
        /** True once staff checked the online payment against the UPI statement / collected the balance. */
        boolean paymentVerified,
        Instant checkedInAt,
        Instant checkedOutAt
) {
    public static StaffBookingView from(Booking b) {
        return new StaffBookingView(
                b.bookingId(), b.roomNumber(), b.checkInDate(), b.checkOutDate(),
                (int) (b.checkOutDate().toEpochDay() - b.checkInDate().toEpochDay()),
                b.guestName(), b.guestPhone(), b.guestEmail(), b.guestCount(), b.status(),
                b.totalAmount(), b.amountPaid(), b.balanceDue(),
                b.paymentMethod(), b.paymentStatus(), b.paymentReference(),
                b.paymentVerifiedAt() != null,
                b.checkedInAt() == null ? null : Instant.ofEpochSecond(b.checkedInAt()),
                b.checkedOutAt() == null ? null : Instant.ofEpochSecond(b.checkedOutAt()));
    }
}

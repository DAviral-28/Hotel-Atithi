package com.hotel.notification;

import com.hotel.dto.BookingResponse;
import com.hotel.dto.StaffBookingView;
import com.hotel.model.PaymentMethod;
import com.hotel.model.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The facts a confirmation message needs, whichever flow (online booking or front-desk walk-in) produced them. */
public record ConfirmationDetails(
        String bookingId,
        String guestName,
        String guestEmail,
        int roomNumber,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        int guestCount,
        BigDecimal totalAmount,
        PaymentMethod paymentMethod,
        PaymentStatus paymentStatus,
        BigDecimal balanceDue
) {
    public static ConfirmationDetails from(BookingResponse b) {
        return new ConfirmationDetails(b.bookingId(), b.guestName(), b.guestEmail(), b.roomNumber(),
                b.checkInDate(), b.checkOutDate(), b.guestCount(), b.totalAmount(),
                b.paymentMethod(), b.paymentStatus(), b.balanceDue());
    }

    public static ConfirmationDetails from(StaffBookingView b) {
        return new ConfirmationDetails(b.bookingId(), b.guestName(), b.guestEmail(), b.roomNumber(),
                b.checkInDate(), b.checkOutDate(), b.guestCount(), b.totalAmount(),
                b.paymentMethod(), b.paymentStatus(), b.balanceDue());
    }
}

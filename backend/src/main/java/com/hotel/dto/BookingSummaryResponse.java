package com.hotel.dto;

import com.hotel.config.RoomDirectoryConfig;
import com.hotel.model.Booking;
import com.hotel.model.BookingStatus;
import com.hotel.util.InputSanitizer;

import java.time.LocalDate;

/**
 * What a phone number ALONE may learn about a booking: nothing that identifies the guest or lets
 * anyone act on it. No full booking ID (only a hint the guest can compare with their confirmation),
 * no tokens, no contact details, no room number, no amounts.
 */
public record BookingSummaryResponse(
        String bookingIdHint,
        BookingStatus status,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        String roomLabel,
        String guestNameMasked
) {
    public static BookingSummaryResponse from(Booking b) {
        return new BookingSummaryResponse(
                idHint(b.bookingId()),
                b.status(),
                b.checkInDate(),
                b.checkOutDate(),
                roomLabel(b.roomNumber()),
                InputSanitizer.maskName(b.guestName()));
    }

    /** "3a7b..." - enough to recognise, not enough to use. */
    static String idHint(String bookingId) {
        if (bookingId == null || bookingId.length() <= 4) {
            return "****";
        }
        return bookingId.substring(0, 4) + "...";
    }

    /** "Air Conditioned Room, Double Bed" - the category only, never the room number. */
    static String roomLabel(int roomNumber) {
        return RoomDirectoryConfig.findByRoomNumber(roomNumber)
                .map(r -> r.type().getDisplayName() + ", " + r.bedType().getDisplayName())
                .orElse("Room");
    }
}

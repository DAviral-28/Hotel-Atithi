package com.hotel.dto;

/**
 * Optional body of POST /api/bookings/{id}/cancel. {@code acknowledgeLate} must be true to cancel
 * after the free-cancellation deadline (the guest has seen and accepted the late-cancellation terms).
 */
public record CancelBookingRequest(boolean acknowledgeLate) {
}

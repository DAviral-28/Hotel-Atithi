package com.hotel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Two-factor lookup: something the guest holds (the Booking ID from their confirmation) plus
 * something they know (the last 4 digits of the phone, or the full number).
 */
public record LookupBookingRequest(
        @NotBlank(message = "Booking ID is required")
        @Size(max = 64, message = "Booking ID is too long")
        @Pattern(regexp = "^[A-Za-z0-9-]*$", message = "Booking ID may contain only letters, digits and hyphens")
        String bookingId,

        @NotBlank(message = "Phone number (or its last 4 digits) is required")
        @Size(max = 20, message = "Phone number is too long")
        String phone
) {
}

package com.hotel.dto;

import com.hotel.util.PhoneNormalizer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Proof of ownership for a returning guest: the FULL phone number the booking was made with (any common spelling). */
public record AccessBookingRequest(
        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^[6-9]\\d{9}$", message = "Enter the full 10-digit mobile number used for the booking")
        String guestPhone
) {
    public AccessBookingRequest {
        guestPhone = PhoneNormalizer.canonicalOrTrimmed(guestPhone);
    }
}

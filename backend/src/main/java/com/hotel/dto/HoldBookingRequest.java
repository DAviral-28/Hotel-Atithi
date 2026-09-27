package com.hotel.dto;

import com.hotel.util.InputSanitizer;
import com.hotel.util.PhoneNormalizer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Request to hold a room. Free text is sanitised and the phone canonicalised in the compact
 * constructor (so "+91 94523-14397" is accepted and stored as 9452314397); Bean Validation then runs
 * on the cleaned values.
 */
public record HoldBookingRequest(
        @NotNull(message = "roomNumber is required")
        Integer roomNumber,

        @NotNull(message = "checkInDate is required")
        LocalDate checkInDate,

        @NotNull(message = "checkOutDate is required")
        LocalDate checkOutDate,

        @Min(value = 1, message = "guestCount must be at least 1")
        @Max(value = 10, message = "guestCount cannot be more than 10")
        int guestCount,

        @NotBlank(message = "Guest name is required")
        @Size(min = 2, max = 80, message = "Guest name must be 2 to 80 characters")
        @Pattern(regexp = "^[\\p{L}\\p{M}][\\p{L}\\p{M} .'’-]*$",
                message = "Guest name may contain only letters, spaces, dots, apostrophes and hyphens")
        String guestName,

        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^[6-9]\\d{9}$", message = "Enter a valid 10-digit Indian mobile number (starting with 6, 7, 8 or 9)")
        String guestPhone,

        @NotBlank(message = "Email is required")
        @Email(message = "Enter a valid email address")
        @Size(max = 120, message = "Email must be at most 120 characters")
        String guestEmail
) {
    public HoldBookingRequest {
        guestName = InputSanitizer.clean(guestName);
        guestPhone = PhoneNormalizer.canonicalOrTrimmed(guestPhone);
        guestEmail = guestEmail == null ? null : guestEmail.trim();
    }
}

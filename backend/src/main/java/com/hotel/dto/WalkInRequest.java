package com.hotel.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.hotel.util.InputSanitizer;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Front-desk walk-in booking. No public hold / rate-limit rules apply. {@code checkInDate} defaults to
 * today; {@code checkInNow} (default true) marks the guest CHECKED_IN straight away when they arrive today.
 * {@code amountCollected} is what was taken at the desk (0 = everything due at check-out).
 */
public record WalkInRequest(
        @NotNull(message = "roomNumber is required") Integer roomNumber,
        LocalDate checkInDate,
        @NotNull(message = "checkOutDate is required") LocalDate checkOutDate,
        @Min(value = 1, message = "guestCount must be at least 1")
        @Max(value = 10, message = "guestCount cannot be more than 10") int guestCount,
        @NotBlank(message = "Guest name is required")
        @Size(min = 2, max = 80, message = "Guest name must be 2 to 80 characters") String guestName,
        @NotBlank(message = "Phone number is required") String guestPhone,
        @Size(max = 120, message = "Email must be at most 120 characters") String guestEmail,
        BigDecimal amountCollected,
        Boolean checkInNow
) {
    public WalkInRequest {
        guestName = InputSanitizer.clean(guestName);
        guestEmail = guestEmail == null || guestEmail.isBlank() ? null : guestEmail.trim();
    }

    public boolean checkInNowOrDefault() {
        return checkInNow == null || checkInNow;
    }
}

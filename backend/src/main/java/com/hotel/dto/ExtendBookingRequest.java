package com.hotel.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record ExtendBookingRequest(
        @NotNull(message = "newCheckOutDate is required")
        LocalDate newCheckOutDate
) {}

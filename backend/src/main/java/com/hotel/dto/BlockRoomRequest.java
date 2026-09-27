package com.hotel.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Takes a room out of order. Both dates are INCLUSIVE nights (block 10th..12th = nights of the 10th, 11th and 12th). */
public record BlockRoomRequest(
        @NotNull(message = "startDate is required") LocalDate startDate,
        @NotNull(message = "endDate is required") LocalDate endDate
) {}

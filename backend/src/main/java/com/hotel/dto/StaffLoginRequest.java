package com.hotel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StaffLoginRequest(
        @NotBlank(message = "PIN is required")
        @Size(max = 64, message = "PIN is too long")
        String pin
) {}

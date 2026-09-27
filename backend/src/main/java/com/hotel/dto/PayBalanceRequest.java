package com.hotel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Records a UPI payment of the booking's outstanding balance (e.g. after a stay extension). */
public record PayBalanceRequest(
        @NotBlank(message = "UTR / transaction reference is required")
        @Pattern(regexp = "^[A-Za-z0-9]{12}$", message = "UTR must be exactly 12 letters or digits")
        String paymentReference
) {
    public PayBalanceRequest {
        paymentReference = paymentReference == null ? null : paymentReference.trim();
    }
}

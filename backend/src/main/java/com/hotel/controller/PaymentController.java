package com.hotel.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public payment settings the booking page needs to build a UPI deep link / QR. Nothing secret:
 * a UPI ID is meant to be shown to payers. Configured through HOTEL_UPI_VPA / HOTEL_UPI_PAYEE_NAME.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final String upiVpa;
    private final String payeeName;

    public PaymentController(
            @Value("${hotel.payment.upi-vpa:}") String upiVpa,
            @Value("${hotel.payment.upi-payee-name:Hotel Atithi}") String payeeName
    ) {
        this.upiVpa = upiVpa == null ? "" : upiVpa.trim();
        this.payeeName = payeeName;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
                "upiEnabled", !upiVpa.isEmpty(),
                "upiVpa", upiVpa,
                "payeeName", payeeName,
                "currency", "INR"
        );
    }
}

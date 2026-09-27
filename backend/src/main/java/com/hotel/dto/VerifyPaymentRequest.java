package com.hotel.dto;

import java.math.BigDecimal;

/**
 * Body of {@code POST /api/staff/bookings/{id}/verify-payment} (the body itself is optional).
 * {@code amountCollected} = money the guest just handed over at the desk (cash / UPI on the desk phone).
 * Omitted: the whole outstanding balance is collected; if nothing is due, an online UPI payment is
 * simply marked as verified against the bank statement.
 */
public record VerifyPaymentRequest(BigDecimal amountCollected) {}

package com.hotel.model;

import java.math.BigDecimal;

public enum PaymentStatus {
    /** Nothing chosen yet (booking is only a HOLD). */
    PENDING,
    /** The full amount was paid online (UPI); a UTR is recorded. */
    PAID_ONLINE,
    /** A balance remains to be paid at the front desk at check-in. */
    DUE_AT_CHECKIN,
    /** Phase 4: staff collected the balance (cash / UPI) at the front desk. */
    PAID_AT_DESK;

    /** Status for a CONFIRMED booking given its total and what has been paid online so far. */
    public static PaymentStatus forBalance(BigDecimal totalAmount, BigDecimal amountPaid) {
        return amountPaid.compareTo(totalAmount) >= 0 ? PAID_ONLINE : DUE_AT_CHECKIN;
    }
}

package com.hotel.model;

public enum BookingStatus {
    HOLD,
    CONFIRMED,
    /** A HOLD the guest explicitly gave up (or edited) before confirming; inventory was rolled back. */
    RELEASED,
    CANCELLED,
    COMPLETED,
    /** Phase 4: the guest has arrived and staff checked them in at the front desk. */
    CHECKED_IN,
    /** Phase 4: staff checked the guest out; any unused nights were released. */
    CHECKED_OUT
}

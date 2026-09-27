package com.hotel.model;

public enum InventoryStatus {
    AVAILABLE,
    HOLD,
    CONFIRMED,
    /** Phase 4: room taken out of order by staff (repairs, deep clean). Never bookable by the public. */
    MAINTENANCE
}

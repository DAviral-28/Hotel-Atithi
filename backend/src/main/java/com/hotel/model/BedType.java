package com.hotel.model;

public enum BedType {
    SINGLE("Single Bed", 1),
    DOUBLE("Double Bed", 2);

    private final String displayName;
    private final int standardCapacity;

    BedType(String displayName, int standardCapacity) {
        this.displayName = displayName;
        this.standardCapacity = standardCapacity;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getStandardCapacity() {
        return standardCapacity;
    }
}

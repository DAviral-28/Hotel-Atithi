package com.hotel.model;

public enum RoomType {
    DELUXE("Deluxe Room", "Affordable comfort with standard amenities"),
    SUPER_DELUXE("Super Deluxe Room", "Enhanced spacious room with upgraded amenities"),
    AC("Air Conditioned Room", "Fully climate-controlled room with premium comfort");

    private final String displayName;
    private final String description;

    RoomType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}

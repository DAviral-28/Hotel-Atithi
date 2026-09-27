package com.hotel.model;

import java.time.LocalDate;

/**
 * Represents daily inventory state for a single room on a specific date.
 * DynamoDB:
 * PK = ROOM#<RoomNumber>
 * SK = INVENTORY#<YYYY-MM-DD>
 */
public record RoomInventory(
        int roomNumber,
        LocalDate date,
        InventoryStatus status,
        String bookingId,
        long lockExpiresAt,
        long version,
        long ttl
) {
    public static String buildPk(int roomNumber) {
        return "ROOM#" + roomNumber;
    }

    public static String buildSk(LocalDate date) {
        return "INVENTORY#" + date.toString();
    }

    /**
     * GSI2 ("by date") keys: one partition per calendar date, one item per room. Lets availability
     * search read every room's lock state for a night with a single Query instead of one Query per room.
     */
    public static String buildGsi2Pk(LocalDate date) {
        return "DATE#" + date.toString();
    }

    public static String buildGsi2Sk(int roomNumber) {
        return "ROOM#" + roomNumber;
    }

    public boolean isCurrentlyLocked(long nowEpochSeconds) {
        if (status == InventoryStatus.CONFIRMED || status == InventoryStatus.MAINTENANCE) {
            return true;
        }
        if (status == InventoryStatus.HOLD && lockExpiresAt > nowEpochSeconds) {
            return true;
        }
        return false;
    }
}

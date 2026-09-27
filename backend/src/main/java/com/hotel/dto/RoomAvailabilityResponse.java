package com.hotel.dto;

import com.hotel.model.BedType;
import com.hotel.model.RoomType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record RoomAvailabilityResponse(
        LocalDate checkInDate,
        LocalDate checkOutDate,
        int totalAvailableRooms,
        String checkoutTime,
        List<AvailableRoomDto> availableRooms
) {
    public record AvailableRoomDto(
            int roomNumber,
            int floor,
            RoomType type,
            BedType bedType,
            BigDecimal baseRate,
            BigDecimal extraPersonRate,
            int baseOccupancy,
            int maxOccupancy
    ) {}
}

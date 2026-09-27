package com.hotel;

import com.hotel.config.RoomDirectoryConfig;
import com.hotel.model.BedType;
import com.hotel.model.Room;
import com.hotel.model.RoomType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RoomDirectoryTest {

    @Test
    @DisplayName("Verify total room count is exactly 32")
    void testTotalRoomCount() {
        List<Room> rooms = RoomDirectoryConfig.getStaticRooms();
        assertEquals(32, rooms.size(), "Hotel must have exactly 32 rooms");
    }

    @Test
    @DisplayName("Verify floor distribution: 16 rooms on Floor 1 and 16 rooms on Floor 2")
    void testFloorDistribution() {
        List<Room> rooms = RoomDirectoryConfig.getStaticRooms();
        Map<Integer, Long> countByFloor = rooms.stream()
                .collect(Collectors.groupingBy(Room::floor, Collectors.counting()));

        assertEquals(16L, countByFloor.get(1), "Floor 1 must have exactly 16 rooms");
        assertEquals(16L, countByFloor.get(2), "Floor 2 must have exactly 16 rooms");
    }

    @Test
    @DisplayName("Verify Floor 1 room specifications (101-116)")
    void testFloorOneSpecifications() {
        // Rooms 101-108: Deluxe, ₹600 + 5% GST, ₹100 extra/person. 104 Single, others Double
        for (int i = 1; i <= 8; i++) {
            int roomNo = 100 + i;
            Room room = RoomDirectoryConfig.findByRoomNumber(roomNo).orElseThrow();
            assertEquals(RoomType.DELUXE, room.type());
            assertEquals(new BigDecimal("600.00"), room.baseRate());
            assertEquals(new BigDecimal("100.00"), room.extraPersonRate());
            assertEquals(new BigDecimal("5.00"), room.taxRatePercent());

            if (roomNo == 104) {
                assertEquals(BedType.SINGLE, room.bedType(), "Room 104 must be Single bed");
                assertEquals(1, room.baseOccupancy());
            } else {
                assertEquals(BedType.DOUBLE, room.bedType(), "Room " + roomNo + " must be Double bed");
                assertEquals(2, room.baseOccupancy());
            }
        }

        // Rooms 109, 114-116: Super Deluxe, ₹700 + 5% GST
        int[] superDeluxeRooms = {109, 114, 115, 116};
        for (int roomNo : superDeluxeRooms) {
            Room room = RoomDirectoryConfig.findByRoomNumber(roomNo).orElseThrow();
            assertEquals(RoomType.SUPER_DELUXE, room.type());
            assertEquals(BedType.DOUBLE, room.bedType());
            assertEquals(new BigDecimal("700.00"), room.baseRate());
            assertEquals(new BigDecimal("5.00"), room.taxRatePercent());
        }

        // Rooms 110-113: AC, ₹1100 + 5% GST, ₹200 extra/person
        for (int roomNo = 110; roomNo <= 113; roomNo++) {
            Room room = RoomDirectoryConfig.findByRoomNumber(roomNo).orElseThrow();
            assertEquals(RoomType.AC, room.type());
            assertEquals(BedType.DOUBLE, room.bedType());
            assertEquals(new BigDecimal("1100.00"), room.baseRate());
            assertEquals(new BigDecimal("200.00"), room.extraPersonRate());
            assertEquals(new BigDecimal("5.00"), room.taxRatePercent());
        }
    }

    @Test
    @DisplayName("Verify Floor 2 is an exact mirror of Floor 1 (201-216) with 204 Single")
    void testFloorTwoMirrorSpecifications() {
        for (int i = 1; i <= 16; i++) {
            int floor1RoomNo = 100 + i;
            int floor2RoomNo = 200 + i;

            Room r1 = RoomDirectoryConfig.findByRoomNumber(floor1RoomNo).orElseThrow();
            Room r2 = RoomDirectoryConfig.findByRoomNumber(floor2RoomNo).orElseThrow();

            assertEquals(r1.type(), r2.type(), "Mismatch in room type between " + floor1RoomNo + " and " + floor2RoomNo);
            assertEquals(r1.bedType(), r2.bedType(), "Mismatch in bed type between " + floor1RoomNo + " and " + floor2RoomNo);
            assertEquals(r1.baseRate(), r2.baseRate(), "Mismatch in base rate between " + floor1RoomNo + " and " + floor2RoomNo);
            assertEquals(r1.extraPersonRate(), r2.extraPersonRate(), "Mismatch in extra person rate between " + floor1RoomNo + " and " + floor2RoomNo);
            assertEquals(r1.taxRatePercent(), r2.taxRatePercent(), "Mismatch in tax rate between " + floor1RoomNo + " and " + floor2RoomNo);
            assertEquals(r1.baseOccupancy(), r2.baseOccupancy());
            assertEquals(r1.maxOccupancy(), r2.maxOccupancy());
        }

        // Specifically assert Room 204 is Single
        Room room204 = RoomDirectoryConfig.findByRoomNumber(204).orElseThrow();
        assertEquals(BedType.SINGLE, room204.bedType(), "Room 204 must be Single bed");
    }

    @Test
    @DisplayName("Verify standard checkout time is 12:00 PM")
    void testStandardCheckoutTime() {
        assertEquals("12:00 PM", RoomDirectoryConfig.STANDARD_CHECKOUT_TIME);
    }

    @Test
    @DisplayName("Verify tariff calculation with extra person and 5% GST")
    void testTariffCalculation() {
        // Room 101: Deluxe, Double (Base 2), Rate ₹600, Extra ₹100.
        // 3 guests, 2 nights:
        // Room rate/night = 600 + (1 * 100) = 700
        // Subtotal = 700 * 2 = 1400
        // GST (5%) = 70.00
        // Total = 1470.00
        Room room101 = RoomDirectoryConfig.findByRoomNumber(101).orElseThrow();
        Room.TariffCalculation calc = room101.calculateTariff(3, 2);

        assertEquals(new BigDecimal("600.00"), calc.baseRatePerNight());
        assertEquals(1, calc.extraGuestsCount());
        assertEquals(new BigDecimal("100.00"), calc.extraGuestChargePerNight());
        assertEquals(new BigDecimal("1400.00"), calc.subtotal());
        assertEquals(new BigDecimal("70.00"), calc.gstAmount());
        assertEquals(new BigDecimal("1470.00"), calc.totalAmount());
        assertEquals("12:00 PM", calc.standardCheckoutTime());
    }
}

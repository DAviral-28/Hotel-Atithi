package com.hotel.config;

import com.hotel.model.BedType;
import com.hotel.model.Room;
import com.hotel.model.RoomType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Constant configuration seeding the exact 32-room hotel directory.
 *
 * Directory constraints:
 * - Floor 1:
 *   - 101-108: Deluxe, 104 Single, others Double; ₹600 + 5% GST, ₹100 extra/person.
 *   - 109, 114-116: Super Deluxe; ₹700 + 5% GST (Double bed).
 *   - 110-113: AC; ₹1100 + 5% GST, ₹200 extra/person (Double bed).
 * - Floor 2: Exact mirror (201-216) with 204 Single.
 * - Standard Checkout: 12:00 PM.
 * - Total Rooms: 32 (16 on Floor 1, 16 on Floor 2).
 */
@Configuration
public class RoomDirectoryConfig {

    public static final String STANDARD_CHECKOUT_TIME = "12:00 PM";
    public static final BigDecimal GST_PERCENT = new BigDecimal("5.00");

    // Rates
    public static final BigDecimal RATE_DELUXE = new BigDecimal("600.00");
    public static final BigDecimal EXTRA_DELUXE = new BigDecimal("100.00");

    public static final BigDecimal RATE_SUPER_DELUXE = new BigDecimal("700.00");
    public static final BigDecimal EXTRA_SUPER_DELUXE = new BigDecimal("100.00");

    public static final BigDecimal RATE_AC = new BigDecimal("1100.00");
    public static final BigDecimal EXTRA_AC = new BigDecimal("200.00");

    private static final List<Room> ALL_ROOMS = Collections.unmodifiableList(initializeRooms());
    private static final Map<Integer, Room> ROOM_MAP = Collections.unmodifiableMap(
            ALL_ROOMS.stream().collect(Collectors.toMap(Room::roomNumber, r -> r))
    );

    private static List<Room> initializeRooms() {
        List<Room> rooms = new ArrayList<>(32);

        // Floor 1 (Rooms 101 to 116)
        buildFloorRooms(1, rooms);

        // Floor 2 (Rooms 201 to 216 - exact mirror)
        buildFloorRooms(2, rooms);

        if (rooms.size() != 32) {
            throw(new IllegalStateException("Room directory must contain exactly 32 rooms, found: " + rooms.size()));
        }

        return rooms;
    }

    private static void buildFloorRooms(int floor, List<Room> rooms) {
        int basePrefix = floor * 100;

        // Rooms 1 to 8: Deluxe (₹600 + 5% GST, ₹100 extra/person)
        // Room 4 is Single bed, others are Double bed
        for (int i = 1; i <= 8; i++) {
            int roomNo = basePrefix + i;
            BedType bedType = (i == 4) ? BedType.SINGLE : BedType.DOUBLE;
            int baseOccupancy = (bedType == BedType.SINGLE) ? 1 : 2;
            int maxOccupancy = (bedType == BedType.SINGLE) ? 2 : 3;

            rooms.add(new Room(
                    roomNo,
                    floor,
                    RoomType.DELUXE,
                    bedType,
                    RATE_DELUXE,
                    EXTRA_DELUXE,
                    GST_PERCENT,
                    baseOccupancy,
                    maxOccupancy
            ));
        }

        // Room 9: Super Deluxe (₹700 + 5% GST)
        rooms.add(new Room(
                basePrefix + 9,
                floor,
                RoomType.SUPER_DELUXE,
                BedType.DOUBLE,
                RATE_SUPER_DELUXE,
                EXTRA_SUPER_DELUXE,
                GST_PERCENT,
                2,
                3
        ));

        // Rooms 10 to 13: AC (₹1100 + 5% GST, ₹200 extra/person)
        for (int i = 10; i <= 13; i++) {
            rooms.add(new Room(
                    basePrefix + i,
                    floor,
                    RoomType.AC,
                    BedType.DOUBLE,
                    RATE_AC,
                    EXTRA_AC,
                    GST_PERCENT,
                    2,
                    3
            ));
        }

        // Rooms 14 to 16: Super Deluxe (₹700 + 5% GST)
        for (int i = 14; i <= 16; i++) {
            rooms.add(new Room(
                    basePrefix + i,
                    floor,
                    RoomType.SUPER_DELUXE,
                    BedType.DOUBLE,
                    RATE_SUPER_DELUXE,
                    EXTRA_SUPER_DELUXE,
                    GST_PERCENT,
                    2,
                    3
            ));
        }
    }

    @Bean
    public List<Room> allRooms() {
        return ALL_ROOMS;
    }

    @Bean
    public Map<Integer, Room> roomLookupMap() {
        return ROOM_MAP;
    }

    public static List<Room> getStaticRooms() {
        return ALL_ROOMS;
    }

    public static Optional<Room> findByRoomNumber(int roomNumber) {
        return Optional.ofNullable(ROOM_MAP.get(roomNumber));
    }
}

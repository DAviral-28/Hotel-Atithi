package com.hotel.service;

import com.hotel.config.RoomDirectoryConfig;
import com.hotel.model.Room;
import com.hotel.model.RoomType;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class RoomService {

    private final List<Room> allRooms;
    private final Map<Integer, Room> roomLookupMap;

    public RoomService(List<Room> allRooms, Map<Integer, Room> roomLookupMap) {
        this.allRooms = allRooms;
        this.roomLookupMap = roomLookupMap;
    }

    public List<Room> getAllRooms(Optional<Integer> floor, Optional<RoomType> type) {
        return allRooms.stream()
                .filter(r -> floor.map(f -> r.floor() == f).orElse(true))
                .filter(r -> type.map(t -> r.type() == t).orElse(true))
                .toList();
    }

    public Optional<Room> getRoomByNumber(int roomNumber) {
        return Optional.ofNullable(roomLookupMap.get(roomNumber));
    }

    public Map<String, Object> getDirectorySummary() {
        Map<RoomType, Long> countsByType = allRooms.stream()
                .collect(Collectors.groupingBy(Room::type, Collectors.counting()));

        Map<Integer, Long> countsByFloor = allRooms.stream()
                .collect(Collectors.groupingBy(Room::floor, Collectors.counting()));

        return Map.of(
                "totalRooms", allRooms.size(),
                "standardCheckoutTime", RoomDirectoryConfig.STANDARD_CHECKOUT_TIME,
                "gstRatePercent", RoomDirectoryConfig.GST_PERCENT,
                "roomCountsByType", countsByType,
                "roomCountsByFloor", countsByFloor
        );
    }
}

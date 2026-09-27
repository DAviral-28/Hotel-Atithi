package com.hotel.controller;

import com.hotel.model.Room;
import com.hotel.model.RoomType;
import com.hotel.service.RoomService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @GetMapping
    public ResponseEntity<List<Room>> getRooms(
            @RequestParam(required = false) Integer floor,
            @RequestParam(required = false) RoomType type
    ) {
        return ResponseEntity.ok(roomService.getAllRooms(Optional.ofNullable(floor), Optional.ofNullable(type)));
    }

    @GetMapping("/{roomNumber}")
    public ResponseEntity<Room> getRoom(@PathVariable int roomNumber) {
        return roomService.getRoomByNumber(roomNumber)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{roomNumber}/tariff")
    public ResponseEntity<?> calculateTariff(
            @PathVariable int roomNumber,
            @RequestParam(defaultValue = "1") int guests,
            @RequestParam(defaultValue = "1") int nights
    ) {
        return roomService.getRoomByNumber(roomNumber)
                .<ResponseEntity<?>>map(room -> {
                    try {
                        return ResponseEntity.ok(room.calculateTariff(guests, nights));
                    } catch (IllegalArgumentException ex) {
                        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        return ResponseEntity.ok(roomService.getDirectorySummary());
    }
}

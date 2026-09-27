package com.hotel.dto;

import java.time.LocalDate;

public record RoomBlockResponse(int roomNumber, LocalDate startDate, LocalDate endDate, int nights, String message) {}

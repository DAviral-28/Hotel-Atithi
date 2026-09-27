package com.hotel.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    @Value("${hotel.aws.region:ap-south-1}")
    private String awsRegion;

    @Value("${hotel.aws.table-name:HotelBookings}")
    private String tableName;

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "hotel-booking-backend",
                "awsRegion", awsRegion,
                "dynamoTable", tableName,
                "timestamp", Instant.now().toString()
        ));
    }
}

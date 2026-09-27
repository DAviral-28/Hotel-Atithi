package com.hotel.controller;

import com.hotel.dto.*;
import com.hotel.notification.NotificationService;
import com.hotel.security.StaffAuthService;
import com.hotel.service.StaffService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Front-desk console API. Everything except {@code /login} needs a staff session token
 * ({@code X-Staff-Token} or {@code Authorization: Bearer}); a missing / bad / expired token is a 401.
 * Guest booking tokens do not work here.
 */
@RestController
@RequestMapping("/api/staff")
public class StaffController {

    private final StaffAuthService auth;
    private final StaffService staff;
    private final NotificationService notifications;

    public StaffController(StaffAuthService auth, StaffService staff, NotificationService notifications) {
        this.auth = auth;
        this.staff = staff;
        this.notifications = notifications;
    }

    @PostMapping("/login")
    public ResponseEntity<StaffLoginResponse> login(@Valid @RequestBody StaffLoginRequest request) {
        String token = auth.login(request.pin());
        long lifetime = auth.tokenLifetimeSeconds();
        return ResponseEntity.ok(new StaffLoginResponse(token,
                Instant.ofEpochSecond(auth.expiryFromNowEpochSeconds()), lifetime));
    }

    @GetMapping("/dashboard")
    public ResponseEntity<StaffDashboardResponse> dashboard(
            @RequestHeader(value = "X-Staff-Token", required = false) String staffToken,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        auth.requireStaff(staffToken, authorization);
        return ResponseEntity.ok(staff.dashboard());
    }

    @PostMapping("/bookings/{bookingId}/check-in")
    public ResponseEntity<StaffBookingView> checkIn(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Staff-Token", required = false) String staffToken,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        auth.requireStaff(staffToken, authorization);
        return ResponseEntity.ok(staff.checkIn(bookingId));
    }

    @PostMapping("/bookings/{bookingId}/check-out")
    public ResponseEntity<StaffBookingView> checkOut(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Staff-Token", required = false) String staffToken,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        auth.requireStaff(staffToken, authorization);
        return ResponseEntity.ok(staff.checkOut(bookingId));
    }

    @PostMapping("/bookings/{bookingId}/verify-payment")
    public ResponseEntity<StaffBookingView> verifyPayment(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Staff-Token", required = false) String staffToken,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) VerifyPaymentRequest request) {
        auth.requireStaff(staffToken, authorization);
        return ResponseEntity.ok(staff.verifyPayment(bookingId, request));
    }

    @PostMapping("/rooms/{roomNumber}/block")
    public ResponseEntity<RoomBlockResponse> blockRoom(
            @PathVariable int roomNumber,
            @RequestHeader(value = "X-Staff-Token", required = false) String staffToken,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody BlockRoomRequest request) {
        auth.requireStaff(staffToken, authorization);
        return ResponseEntity.ok(staff.blockRoom(roomNumber, request));
    }

    @PostMapping("/rooms/{roomNumber}/unblock")
    public ResponseEntity<RoomBlockResponse> unblockRoom(
            @PathVariable int roomNumber,
            @RequestHeader(value = "X-Staff-Token", required = false) String staffToken,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) UnblockRoomRequest request) {
        auth.requireStaff(staffToken, authorization);
        return ResponseEntity.ok(staff.unblockRoom(roomNumber, request));
    }

    @PostMapping("/walkin")
    public ResponseEntity<StaffBookingView> walkIn(
            @RequestHeader(value = "X-Staff-Token", required = false) String staffToken,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody WalkInRequest request) {
        auth.requireStaff(staffToken, authorization);
        StaffBookingView created = staff.walkIn(request);
        notifications.sendBookingConfirmation(created);     // best effort; never fails the walk-in
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}

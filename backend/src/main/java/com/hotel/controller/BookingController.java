package com.hotel.controller;

import com.hotel.dto.*;
import com.hotel.model.RoomType;
import com.hotel.notification.NotificationService;
import com.hotel.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;

    private final NotificationService notificationService;

    public BookingController(BookingService bookingService, NotificationService notificationService) {
        this.bookingService = bookingService;
        this.notificationService = notificationService;
    }

    @PostMapping("/hold")
    public ResponseEntity<BookingResponse> holdRoom(@Valid @RequestBody HoldBookingRequest request) {
        BookingResponse response = bookingService.holdRoom(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{bookingId}/confirm")
    public ResponseEntity<BookingResponse> confirmBooking(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Booking-Token", required = false) String headerToken,
            @Valid @RequestBody ConfirmBookingRequest request
    ) {
        BookingResponse response = bookingService.confirmBooking(bookingId, request, headerToken);
        // Optional zero-cost e-mail (AWS SES free tier). No-op unless AWS_SES_SENDER_EMAIL is set; never throws.
        notificationService.sendBookingConfirmation(response);
        return ResponseEntity.ok(response);
    }

    /**
     * Releases a HOLD before it expires (guest cancelled, or is editing their details and will
     * re-hold). Requires the signed hold token in X-Booking-Token while the hold is still live.
     * Idempotent.
     */
    @DeleteMapping("/{bookingId}/hold")
    public ResponseEntity<BookingResponse> releaseHold(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Booking-Token", required = false) String headerToken
    ) {
        return ResponseEntity.ok(bookingService.releaseHold(bookingId, headerToken));
    }

    /** Extends a stay. Needs the manage token (X-Booking-Token) returned by confirm / access. */
    @PostMapping("/{bookingId}/extend")
    public ResponseEntity<BookingResponse> extendBooking(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Booking-Token", required = false) String manageToken,
            @Valid @RequestBody ExtendBookingRequest request
    ) {
        return ResponseEntity.ok(bookingService.extendBooking(bookingId, request, manageToken));
    }

    /** Records a UPI payment (12-digit UTR) for the outstanding balance of a confirmed booking. */
    @PostMapping("/{bookingId}/pay")
    public ResponseEntity<BookingResponse> payBalance(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Booking-Token", required = false) String manageToken,
            @Valid @RequestBody PayBalanceRequest request
    ) {
        return ResponseEntity.ok(bookingService.payBalance(bookingId, request, manageToken));
    }

    /**
     * Cancels a confirmed booking (manage token required). Idempotent: cancelling an already-cancelled
     * booking returns 200 with the cancelled booking. The body is optional; send
     * {"acknowledgeLate": true} to cancel after the free-cancellation deadline.
     */
    @PostMapping("/{bookingId}/cancel")
    public ResponseEntity<BookingResponse> cancelBooking(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Booking-Token", required = false) String manageToken,
            @RequestBody(required = false) CancelBookingRequest request
    ) {
        return ResponseEntity.ok(bookingService.cancelBooking(bookingId, request, manageToken));
    }

    /**
     * Phone-number verification: the guest proves the FULL phone number for this booking and receives the
     * booking plus a fresh manage token. This is the only way (besides confirming) to obtain a manage token.
     */
    @PostMapping("/{bookingId}/access")
    public ResponseEntity<BookingResponse> accessBooking(
            @PathVariable String bookingId,
            @Valid @RequestBody AccessBookingRequest request
    ) {
        return ResponseEntity.ok(bookingService.accessBooking(bookingId, request));
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<BookingResponse> getBooking(@PathVariable String bookingId) {
        return ResponseEntity.ok(bookingService.getBooking(bookingId));
    }

    @GetMapping("/availability")
    public ResponseEntity<RoomAvailabilityResponse> getAvailability(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkInDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOutDate,
            @RequestParam(required = false) RoomType roomType
    ) {
        return ResponseEntity.ok(bookingService.getAvailability(checkInDate, checkOutDate, Optional.ofNullable(roomType)));
    }

    /**
     * Phone-only lookup. Returns masked, unprivileged summaries only (partial booking ID, status, dates,
     * room category, masked guest name) - never tokens, e-mail or full names. A phone number alone must
     * not open a booking; see {@link #verifiedLookup} and {@link #accessBooking}.
     */
    @GetMapping("/lookup")
    public ResponseEntity<List<BookingSummaryResponse>> lookupByGuestPhone(@RequestParam String phone) {
        return ResponseEntity.ok(bookingService.lookupSummariesByPhone(phone));
    }

    /** Two-factor lookup: Booking ID + last 4 digits of the phone (or the full number). Masked result, no tokens. */
    @PostMapping("/lookup")
    public ResponseEntity<VerifiedBookingSummary> verifiedLookup(@Valid @RequestBody LookupBookingRequest request) {
        return ResponseEntity.ok(bookingService.lookupVerified(request));
    }
}

package com.hotel.dto;

import com.hotel.model.RoomType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Everything the staff console shows on one screen. All "today" values use Asia/Kolkata. */
public record StaffDashboardResponse(
        LocalDate date,
        Stats stats,
        List<StaffBookingView> arrivals,
        List<StaffBookingView> departures,
        List<StaffBookingView> inHouse,
        /** UPI payments the guest declared (UTR) that nobody has reconciled yet. */
        List<StaffBookingView> unverifiedPayments,
        List<RoomStatusView> rooms
) {
    public record Stats(
            int totalRooms,
            int roomsOccupied,
            int roomsReserved,
            int roomsAvailable,
            int roomsMaintenance,
            int arrivalsToday,
            int departuresToday,
            /** Live bookings (arriving today or in-house) that still owe money at the desk. */
            int pendingDeskDues,
            BigDecimal pendingDeskDuesAmount,
            int unverifiedPayments
    ) {}

    public enum RoomLiveStatus { AVAILABLE, RESERVED, IN_HOUSE, MAINTENANCE }

    public record RoomStatusView(
            int roomNumber,
            int floor,
            RoomType type,
            RoomLiveStatus status,
            String bookingId,
            String guestName,
            LocalDate checkInDate,
            LocalDate checkOutDate
    ) {}
}

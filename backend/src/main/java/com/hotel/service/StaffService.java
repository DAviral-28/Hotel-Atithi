package com.hotel.service;

import com.hotel.config.RoomDirectoryConfig;
import com.hotel.dto.*;
import com.hotel.dto.StaffDashboardResponse.RoomLiveStatus;
import com.hotel.dto.StaffDashboardResponse.RoomStatusView;
import com.hotel.dto.StaffDashboardResponse.Stats;
import com.hotel.exception.BookingNotFoundException;
import com.hotel.exception.BookingValidationException;
import com.hotel.model.*;
import com.hotel.repository.BookingRepository;
import com.hotel.util.InputSanitizer;
import com.hotel.util.PhoneNormalizer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.hotel.service.BookingService.HOTEL_ZONE;
import static com.hotel.service.BookingService.generateDateRange;

/**
 * Front-desk operations (Phase 4). Authorisation happens in the controller ({@code StaffAuthService});
 * this class holds the rules: who can be checked in / out, how payments are reconciled, how rooms are
 * taken out of order, and how walk-ins bypass the public hold rules.
 *
 * <p>All "today" decisions use the injected {@link Clock} (Asia/Kolkata).
 */
@Service
public class StaffService {

    /** Marker stored in the {@code bookingId} attribute of MAINTENANCE inventory rows. */
    public static final String MAINTENANCE_MARKER = "MAINTENANCE";

    /** A block longer than this is almost certainly a typo. */
    public static final int MAX_BLOCK_NIGHTS = 90;

    /** How far back the dashboard looks for guests who should have left already (overdue departures). */
    static final int DASHBOARD_LOOKBACK_DAYS = 3;

    private static final long MAINTENANCE_RETENTION_SECONDS = 86400L * 7L;
    private static final Pattern BOOKING_ID_PATTERN = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private final BookingRepository repository;
    private final Clock clock;

    public StaffService(BookingRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------
    // Dashboard
    // ------------------------------------------------------------------------------------------

    public StaffDashboardResponse dashboard() {
        LocalDate today = LocalDate.now(clock);
        long now = Instant.now(clock).getEpochSecond();

        // Today's inventory rows drive the room grid; the lookback also finds guests who are overdue to leave.
        List<LocalDate> dates = new ArrayList<>();
        for (int i = DASHBOARD_LOOKBACK_DAYS; i >= 0; i--) {
            dates.add(today.minusDays(i));
        }
        List<RoomInventory> inventory = repository.findInventoriesForDates(dates);

        Set<String> bookingIds = new LinkedHashSet<>();
        for (RoomInventory inv : inventory) {
            if (inv.bookingId() != null && !inv.bookingId().isEmpty() && !MAINTENANCE_MARKER.equals(inv.bookingId())) {
                bookingIds.add(inv.bookingId());
            }
        }
        List<String> pendingIds = repository.findPendingReconciliationIds();
        bookingIds.addAll(pendingIds);

        Map<String, Booking> byId = new HashMap<>();
        for (Booking b : repository.findBookingsByIds(bookingIds)) {
            byId.put(b.bookingId(), b);
        }

        List<Booking> live = byId.values().stream().filter(Booking::isLive).toList();

        List<Booking> arrivals = live.stream()
                .filter(b -> b.status() == BookingStatus.CONFIRMED
                        && !b.checkInDate().isAfter(today) && b.checkOutDate().isAfter(today))
                .sorted(Comparator.comparingInt(Booking::roomNumber)).toList();
        List<Booking> inHouse = live.stream()
                .filter(b -> b.status() == BookingStatus.CHECKED_IN)
                .sorted(Comparator.comparingInt(Booking::roomNumber)).toList();
        List<Booking> departures = inHouse.stream()
                .filter(b -> !b.checkOutDate().isAfter(today))
                .toList();
        List<Booking> unverified = pendingIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .filter(b -> b.isLive() && b.paymentMethod() == PaymentMethod.UPI_QR && b.paymentVerifiedAt() == null)
                .sorted(Comparator.comparing(Booking::checkInDate))
                .toList();

        List<RoomStatusView> rooms = buildRoomGrid(today, now, inventory, byId, inHouse);

        int occupied = count(rooms, RoomLiveStatus.IN_HOUSE);
        int reserved = count(rooms, RoomLiveStatus.RESERVED);
        int maintenance = count(rooms, RoomLiveStatus.MAINTENANCE);
        int available = count(rooms, RoomLiveStatus.AVAILABLE);

        List<Booking> owing = new ArrayList<>();
        for (Booking b : arrivals) {
            if (b.checkInDate().equals(today) && b.balanceDue().signum() > 0) {
                owing.add(b);
            }
        }
        for (Booking b : inHouse) {
            if (b.balanceDue().signum() > 0) {
                owing.add(b);
            }
        }
        BigDecimal dues = owing.stream().map(Booking::balanceDue).reduce(BigDecimal.ZERO, BigDecimal::add);

        int arrivalsToday = (int) arrivals.stream().filter(b -> b.checkInDate().equals(today)).count();
        Stats stats = new Stats(rooms.size(), occupied, reserved, available, maintenance,
                arrivalsToday, departures.size(), owing.size(), dues, unverified.size());

        return new StaffDashboardResponse(today, stats,
                arrivals.stream().map(StaffBookingView::from).toList(),
                departures.stream().map(StaffBookingView::from).toList(),
                inHouse.stream().map(StaffBookingView::from).toList(),
                unverified.stream().map(StaffBookingView::from).toList(),
                rooms);
    }

    private List<RoomStatusView> buildRoomGrid(LocalDate today, long now, List<RoomInventory> inventory,
                                               Map<String, Booking> byId, List<Booking> inHouse) {
        Map<Integer, RoomInventory> todayRow = new HashMap<>();
        for (RoomInventory inv : inventory) {
            if (inv.date().equals(today)) {
                todayRow.put(inv.roomNumber(), inv);
            }
        }
        // A guest who is checked in but leaves today has no row for tonight, yet still occupies the room.
        Map<Integer, Booking> occupant = new HashMap<>();
        for (Booking b : inHouse) {
            if (!b.checkInDate().isAfter(today)) {
                occupant.put(b.roomNumber(), b);
            }
        }

        List<RoomStatusView> rooms = new ArrayList<>();
        for (Room room : RoomDirectoryConfig.getStaticRooms()) {
            RoomLiveStatus status = RoomLiveStatus.AVAILABLE;
            Booking booking = null;

            Booking inHouseGuest = occupant.get(room.roomNumber());
            RoomInventory row = todayRow.get(room.roomNumber());
            if (inHouseGuest != null) {
                status = RoomLiveStatus.IN_HOUSE;
                booking = inHouseGuest;
            } else if (row != null && row.isCurrentlyLocked(now)) {
                if (row.status() == InventoryStatus.MAINTENANCE) {
                    status = RoomLiveStatus.MAINTENANCE;
                } else {
                    status = RoomLiveStatus.RESERVED;          // CONFIRMED (not arrived yet) or a live online HOLD
                    booking = byId.get(row.bookingId());
                }
            }
            rooms.add(new RoomStatusView(room.roomNumber(), room.floor(), room.type(), status,
                    booking == null ? null : booking.bookingId(),
                    booking == null ? null : booking.guestName(),
                    booking == null ? null : booking.checkInDate(),
                    booking == null ? null : booking.checkOutDate()));
        }
        rooms.sort(Comparator.comparingInt(RoomStatusView::roomNumber));
        return rooms;
    }

    private static int count(List<RoomStatusView> rooms, RoomLiveStatus status) {
        return (int) rooms.stream().filter(r -> r.status() == status).count();
    }

    // ------------------------------------------------------------------------------------------
    // Check-in / check-out
    // ------------------------------------------------------------------------------------------

    /** CONFIRMED -> CHECKED_IN. Idempotent. Not before the check-in date, not after the stay ended. */
    public StaffBookingView checkIn(String bookingId) {
        Booking booking = load(bookingId);
        if (booking.status() == BookingStatus.CHECKED_IN) {
            return StaffBookingView.from(booking);
        }
        if (booking.status() != BookingStatus.CONFIRMED) {
            throw new BookingValidationException("Only CONFIRMED bookings can be checked in; this one is " + booking.status() + ".");
        }
        LocalDate today = LocalDate.now(clock);
        if (today.isBefore(booking.checkInDate())) {
            throw new BookingValidationException("Too early: this booking's check-in date is " + booking.checkInDate()
                    + ". Cancel and re-book, or use a walk-in for an earlier arrival.");
        }
        if (!booking.checkOutDate().isAfter(today)) {
            throw new BookingValidationException("This stay ended on " + booking.checkOutDate() + " and can no longer be checked in.");
        }
        long now = Instant.now(clock).getEpochSecond();
        Booking updated = booking.toBuilder().status(BookingStatus.CHECKED_IN).checkedInAt(now).build();
        try {
            repository.checkIn(updated);
        } catch (BookingValidationException ex) {
            Booking current = load(bookingId);
            if (current.status() == BookingStatus.CHECKED_IN) {
                return StaffBookingView.from(current);      // a concurrent check-in won: still success
            }
            throw ex;
        }
        return StaffBookingView.from(updated);
    }

    /**
     * CHECKED_IN -> CHECKED_OUT. Idempotent. Nights that were booked but not used (leaving early) are
     * released so the room can be sold again immediately. An outstanding balance does NOT block
     * check-out (the desk decides), but the returned view shows {@code balanceDue}.
     */
    public StaffBookingView checkOut(String bookingId) {
        Booking booking = load(bookingId);
        if (booking.status() == BookingStatus.CHECKED_OUT) {
            return StaffBookingView.from(booking);
        }
        if (booking.status() != BookingStatus.CHECKED_IN) {
            throw new BookingValidationException("Only guests who are checked in can be checked out; this booking is " + booking.status() + ".");
        }
        LocalDate today = LocalDate.now(clock);
        long now = Instant.now(clock).getEpochSecond();

        List<RoomInventory> unused = new ArrayList<>();
        if (booking.checkOutDate().isAfter(today)) {
            for (LocalDate night : generateDateRange(today, booking.checkOutDate())) {
                unused.add(new RoomInventory(booking.roomNumber(), night, InventoryStatus.AVAILABLE,
                        booking.bookingId(), 0L, 0L, booking.ttl()));
            }
        }
        Booking updated = booking.toBuilder().status(BookingStatus.CHECKED_OUT).checkedOutAt(now).build();
        try {
            repository.checkOut(updated, unused, now);
        } catch (BookingValidationException ex) {
            Booking current = load(bookingId);
            if (current.status() == BookingStatus.CHECKED_OUT) {
                return StaffBookingView.from(current);
            }
            throw ex;
        }
        return StaffBookingView.from(updated);
    }

    // ------------------------------------------------------------------------------------------
    // Payments
    // ------------------------------------------------------------------------------------------

    /**
     * Reconciles payment for a live booking. With an outstanding balance the desk collects it (all of it,
     * or {@code amountCollected}); with nothing due, an online UPI payment is marked verified. Always
     * stamps {@code paymentVerifiedAt}, which clears the booking from the dashboard's "unverified" list.
     * Idempotent when there is nothing left to do.
     */
    public StaffBookingView verifyPayment(String bookingId, VerifyPaymentRequest req) {
        Booking booking = load(bookingId);
        if (!booking.isLive()) {
            throw new BookingValidationException("Payments can only be verified for confirmed or in-house bookings; this one is " + booking.status() + ".");
        }
        BigDecimal balance = booking.balanceDue();
        BigDecimal requested = req == null ? null : req.amountCollected();
        if (requested != null && requested.signum() < 0) {
            throw new BookingValidationException("Amount collected cannot be negative.");
        }
        BigDecimal collect = requested != null ? requested : balance;
        if (collect.compareTo(balance) > 0) {
            throw new BookingValidationException("Amount collected (" + collect.toPlainString()
                    + ") is more than the balance due (" + balance.toPlainString() + ").");
        }

        boolean alreadyVerified = booking.paymentVerifiedAt() != null;
        if (collect.signum() == 0 && alreadyVerified) {
            return StaffBookingView.from(booking);                     // nothing left to do
        }
        if (collect.signum() == 0 && booking.paymentMethod() != PaymentMethod.UPI_QR && balance.signum() > 0) {
            throw new BookingValidationException("Nothing to verify: enter the amount collected at the desk.");
        }

        long now = Instant.now(clock).getEpochSecond();
        BigDecimal previouslyPaid = booking.amountPaid();
        BigDecimal newPaid = previouslyPaid.add(collect);
        PaymentStatus status = newPaid.compareTo(booking.totalAmount()) >= 0
                ? (collect.signum() > 0 ? PaymentStatus.PAID_AT_DESK : PaymentStatus.PAID_ONLINE)
                : PaymentStatus.DUE_AT_CHECKIN;
        Booking updated = booking.toBuilder()
                .amountPaid(newPaid)
                .paymentStatus(status)
                .paymentVerifiedAt(now)
                .paidAt(collect.signum() > 0 ? Long.valueOf(now) : booking.paidAt())
                .build();
        try {
            repository.recordStaffPayment(updated, previouslyPaid);
        } catch (BookingValidationException ex) {
            Booking current = load(bookingId);
            if (current.paymentVerifiedAt() != null && current.amountPaid().compareTo(newPaid) == 0) {
                return StaffBookingView.from(current);                 // a concurrent verify won
            }
            throw ex;
        }
        return StaffBookingView.from(updated);
    }

    // ------------------------------------------------------------------------------------------
    // Maintenance blocking
    // ------------------------------------------------------------------------------------------

    /**
     * Takes a room out of order for the inclusive nights startDate..endDate. The public cannot hold or
     * book those nights (availability treats MAINTENANCE as locked). Refuses (409) when any night already
     * has a booking or live hold: move or cancel the guest first.
     */
    public RoomBlockResponse blockRoom(int roomNumber, BlockRoomRequest req) {
        requireRoom(roomNumber);
        LocalDate today = LocalDate.now(clock);
        if (req == null || req.startDate() == null || req.endDate() == null) {
            throw new BookingValidationException("startDate and endDate are required.");
        }
        if (req.startDate().isBefore(today)) {
            throw new BookingValidationException("A block cannot start in the past (" + req.startDate() + ").");
        }
        if (req.endDate().isBefore(req.startDate())) {
            throw new BookingValidationException("endDate must be on or after startDate.");
        }
        LocalDate exclusiveEnd = req.endDate().plusDays(1);
        int nights = (int) (exclusiveEnd.toEpochDay() - req.startDate().toEpochDay());
        if (nights > MAX_BLOCK_NIGHTS) {
            throw new BookingValidationException("A maintenance block can cover at most " + MAX_BLOCK_NIGHTS
                    + " nights (you selected " + nights + ").");
        }
        long now = Instant.now(clock).getEpochSecond();
        long ttl = exclusiveEnd.atStartOfDay(HOTEL_ZONE).toEpochSecond() + MAINTENANCE_RETENTION_SECONDS;

        List<RoomInventory> rows = generateDateRange(req.startDate(), exclusiveEnd).stream()
                .map(d -> new RoomInventory(roomNumber, d, InventoryStatus.MAINTENANCE, MAINTENANCE_MARKER, 0L, 1L, ttl))
                .toList();
        repository.blockRoom(rows, now);
        return new RoomBlockResponse(roomNumber, req.startDate(), req.endDate(), nights,
                "Room " + roomNumber + " is out of order for " + nights + (nights == 1 ? " night." : " nights."));
    }

    /**
     * Returns a room to service: deletes its MAINTENANCE nights in the range (default: today onwards).
     * Nights that carry a guest booking are never touched. Idempotent (0 nights released is a success).
     */
    public RoomBlockResponse unblockRoom(int roomNumber, UnblockRoomRequest req) {
        requireRoom(roomNumber);
        LocalDate today = LocalDate.now(clock);
        LocalDate start = req != null && req.startDate() != null ? req.startDate() : today;
        LocalDate end = req != null && req.endDate() != null ? req.endDate() : today.plusDays(BookingService.MAX_ADVANCE_DAYS);
        if (end.isBefore(start)) {
            throw new BookingValidationException("endDate must be on or after startDate.");
        }
        if (end.toEpochDay() - start.toEpochDay() > BookingService.MAX_ADVANCE_DAYS + 30) {
            throw new BookingValidationException("Choose a date range of at most " + (BookingService.MAX_ADVANCE_DAYS + 30) + " days.");
        }
        List<LocalDate> blocked = repository.findInventoriesForRoomAndDates(roomNumber, generateDateRange(start, end.plusDays(1)))
                .stream()
                .filter(inv -> inv.status() == InventoryStatus.MAINTENANCE)
                .map(RoomInventory::date)
                .sorted()
                .collect(Collectors.toList());
        int released = blocked.isEmpty() ? 0 : repository.unblockRoom(roomNumber, blocked);
        return new RoomBlockResponse(roomNumber, start, end, released,
                released == 0 ? "Room " + roomNumber + " had no maintenance nights in that range."
                        : "Room " + roomNumber + " is back in service (" + released + (released == 1 ? " night" : " nights") + " released).");
    }

    // ------------------------------------------------------------------------------------------
    // Walk-in
    // ------------------------------------------------------------------------------------------

    /**
     * Creates a booking directly at the desk: no 10-minute hold, no public rate limit, no token. The room
     * must be free for every night (409 otherwise). By default the guest is checked in immediately.
     */
    public StaffBookingView walkIn(WalkInRequest req) {
        Room room = requireRoom(req.roomNumber() == null ? -1 : req.roomNumber());
        String phone = PhoneNormalizer.require(req.guestPhone());
        String name = InputSanitizer.clean(req.guestName());
        if (name == null || name.length() < 2) {
            throw new BookingValidationException("Guest name must be at least 2 characters.");
        }

        LocalDate today = LocalDate.now(clock);
        LocalDate checkIn = req.checkInDate() == null ? today : req.checkInDate();
        BookingService.validateStayDates(checkIn, req.checkOutDate(), today);
        if (req.guestCount() < 1 || req.guestCount() > room.maxOccupancy()) {
            throw new BookingValidationException("Guest count must be between 1 and " + room.maxOccupancy() + " for room " + room.roomNumber());
        }

        int nights = (int) (req.checkOutDate().toEpochDay() - checkIn.toEpochDay());
        Room.TariffCalculation tariff = room.calculateTariff(req.guestCount(), nights);

        BigDecimal collected = req.amountCollected() == null ? BigDecimal.ZERO : req.amountCollected();
        if (collected.signum() < 0) {
            throw new BookingValidationException("Amount collected cannot be negative.");
        }
        if (collected.compareTo(tariff.totalAmount()) > 0) {
            throw new BookingValidationException("Amount collected (" + collected.toPlainString()
                    + ") is more than the total (" + tariff.totalAmount().toPlainString() + ").");
        }

        long now = Instant.now(clock).getEpochSecond();
        long ttl = BookingService.retentionTtlFor(req.checkOutDate());
        boolean checkInNow = req.checkInNowOrDefault() && checkIn.equals(today);
        String bookingId = UUID.randomUUID().toString();

        PaymentStatus payStatus = collected.compareTo(tariff.totalAmount()) >= 0 ? PaymentStatus.PAID_AT_DESK
                : PaymentStatus.DUE_AT_CHECKIN;
        Booking booking = new Booking(
                bookingId, room.roomNumber(), checkIn, req.checkOutDate(),
                name, phone, req.guestEmail(), req.guestCount(),
                tariff.baseRatePerNight(), tariff.extraGuestChargePerNight(),
                tariff.subtotal(), tariff.gstAmount(), tariff.totalAmount(),
                checkInNow ? BookingStatus.CHECKED_IN : BookingStatus.CONFIRMED,
                now, now, null, now, RoomDirectoryConfig.STANDARD_CHECKOUT_TIME, ttl,
                PaymentMethod.PAY_AT_HOTEL, payStatus, collected,
                collected.signum() > 0 ? Long.valueOf(now) : null,
                null, false,
                checkInNow ? Long.valueOf(now) : null,
                null,
                collected.signum() > 0 ? Long.valueOf(now) : null);

        List<RoomInventory> rows = generateDateRange(checkIn, req.checkOutDate()).stream()
                .map(d -> new RoomInventory(room.roomNumber(), d, InventoryStatus.CONFIRMED, bookingId, 0L, 1L, ttl))
                .toList();
        repository.createDirectBooking(booking, rows, now);
        return StaffBookingView.from(booking);
    }

    // ------------------------------------------------------------------------------------------

    private Booking load(String bookingId) {
        String id = bookingId == null ? "" : bookingId.trim();
        if (!BOOKING_ID_PATTERN.matcher(id).matches()) {
            throw new BookingNotFoundException("Booking " + bookingId + " not found");
        }
        return repository.findBookingById(id)
                .orElseThrow(() -> new BookingNotFoundException("Booking " + id + " not found"));
    }

    private static Room requireRoom(int roomNumber) {
        return RoomDirectoryConfig.findByRoomNumber(roomNumber)
                .orElseThrow(() -> new BookingValidationException("Room " + roomNumber + " does not exist in the hotel directory."));
    }
}

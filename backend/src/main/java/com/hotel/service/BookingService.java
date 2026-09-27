package com.hotel.service;

import com.hotel.config.AppConfig;
import com.hotel.config.RoomDirectoryConfig;
import com.hotel.dto.*;
import com.hotel.exception.BookingForbiddenException;
import com.hotel.exception.BookingNotFoundException;
import com.hotel.exception.BookingValidationException;
import com.hotel.model.*;
import com.hotel.repository.BookingRepository;
import com.hotel.util.InputSanitizer;
import com.hotel.util.PhoneNormalizer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class BookingService {

    /** UPI transaction reference (UTR / RRN): exactly 12 letters or digits (stored upper-case so "ab.." and "AB.." are one UTR). */
    private static final Pattern UTR_PATTERN = Pattern.compile("[A-Za-z0-9]{12}");

    /** Booking IDs are UUIDs; anything else cannot exist, so it is rejected without a database read. */
    private static final Pattern BOOKING_ID_PATTERN = Pattern.compile("[A-Za-z0-9-]{8,64}");

    public static final long HOLD_DURATION_SECONDS = 600L; // 10 minutes

    /** How long a booking (and its inventory rows) is kept AFTER the stay's check-out day, for audit/invoice lookups. */
    public static final long RETENTION_TTL_SECONDS = 86400L * 90L; // 90 days

    /** A released (abandoned) hold has no stay to protect; keep its record only briefly. */
    public static final long RELEASED_RETENTION_SECONDS = 86400L * 7L; // 7 days

    /**
     * DynamoDB TransactWriteItems allows at most 100 actions: 1 booking item + one inventory item
     * per night. 30 nights (31 items) is well inside that limit and is the business cap anyway.
     */
    public static final int MAX_STAY_NIGHTS = 30;

    /** Guests may reserve at most this many days ahead of today (IST). */
    public static final int MAX_ADVANCE_DAYS = 365;

    /** Time zone all date decisions are made in (see AppConfig). */
    public static final ZoneId HOTEL_ZONE = AppConfig.HOTEL_ZONE;

    private final BookingRepository bookingRepository;
    private final Clock clock;
    private final com.hotel.security.BookingTokenService bookingTokenService;

    public BookingService(
            BookingRepository bookingRepository,
            Clock clock,
            com.hotel.security.BookingTokenService bookingTokenService
    ) {
        this.bookingRepository = bookingRepository;
        this.clock = clock;
        this.bookingTokenService = bookingTokenService;
    }

    public BookingResponse holdRoom(HoldBookingRequest req) {
        Room room = RoomDirectoryConfig.findByRoomNumber(req.roomNumber())
                .orElseThrow(() -> new BookingValidationException("Room " + req.roomNumber() + " does not exist in hotel directory"));

        // Reject a malformed phone before any DynamoDB or hashing work; store ONE canonical spelling.
        String guestPhone = PhoneNormalizer.require(req.guestPhone());
        String guestName = InputSanitizer.clean(req.guestName());
        String guestEmail = req.guestEmail() == null ? null : InputSanitizer.clean(req.guestEmail());

        LocalDate today = LocalDate.now(clock);
        validateStayDates(req.checkInDate(), req.checkOutDate(), today);
        if (req.guestCount() < 1 || req.guestCount() > room.maxOccupancy()) {
            throw new BookingValidationException("Guest count must be between 1 and " + room.maxOccupancy() + " for room " + req.roomNumber());
        }

        int nights = (int) (req.checkOutDate().toEpochDay() - req.checkInDate().toEpochDay());
        Room.TariffCalculation tariff = room.calculateTariff(req.guestCount(), nights);

        long nowEpochSeconds = Instant.now(clock).getEpochSecond();
        // lockExpiresAt is the 10-minute hold window and is evaluated by the conditional writes.
        // ttl is a completely separate concern: when DynamoDB may physically delete the rows. It must
        // be anchored to the END OF THE STAY, otherwise a stay booked far ahead would be purged (and the
        // room silently re-sold) before the guest ever arrives.
        long lockExpiresAt = nowEpochSeconds + HOLD_DURATION_SECONDS;
        long ttl = retentionTtlFor(req.checkOutDate());

        String bookingId = UUID.randomUUID().toString();

        Booking booking = new Booking(
                bookingId,
                req.roomNumber(),
                req.checkInDate(),
                req.checkOutDate(),
                guestName,
                guestPhone,
                guestEmail,
                req.guestCount(),
                tariff.baseRatePerNight(),
                tariff.extraGuestChargePerNight(),
                tariff.subtotal(),
                tariff.gstAmount(),
                tariff.totalAmount(),
                BookingStatus.HOLD,
                nowEpochSeconds,
                lockExpiresAt,
                null,
                null,
                RoomDirectoryConfig.STANDARD_CHECKOUT_TIME,
                ttl
        );

        List<LocalDate> stayDates = generateDateRange(req.checkInDate(), req.checkOutDate());
        List<RoomInventory> inventories = stayDates.stream()
                .map(date -> new RoomInventory(
                        req.roomNumber(),
                        date,
                        InventoryStatus.HOLD,
                        bookingId,
                        lockExpiresAt,
                        1L,
                        ttl
                ))
                .toList();

        bookingRepository.holdRoom(booking, inventories, nowEpochSeconds);
        String bookingToken = bookingTokenService.generateHoldToken(booking);
        return BookingResponse.from(booking, nowEpochSeconds, bookingToken);
    }

    public BookingResponse confirmBooking(String bookingId, ConfirmBookingRequest req) {
        return confirmBooking(bookingId, req, req.bookingToken());
    }

    /**
     * Confirms a HOLD. There is no payment gateway (zero-cost design), so there are two ways to pay:
     * <ul>
     *   <li>{@link PaymentMethod#PAY_AT_HOTEL}: booking becomes CONFIRMED with paymentStatus
     *       DUE_AT_CHECKIN; the whole total is collected at the desk.</li>
     *   <li>{@link PaymentMethod#UPI_QR}: the guest paid the total to the hotel's UPI ID and supplies the
     *       12-digit UTR. Booking becomes CONFIRMED with PAID_ONLINE, the UTR and a timestamp are stored.
     *       The UTR is self-declared, so it is claimed uniquely and must be checked by staff against the
     *       UPI statement.</li>
     * </ul>
     * Returns a manage token that authorises later cancel / extend / pay actions.
     */
    public BookingResponse confirmBooking(String bookingId, ConfirmBookingRequest req, String headerToken) {
        PaymentMethod method = req.paymentMethod();
        if (method == null) {
            throw new BookingValidationException("paymentMethod is required (PAY_AT_HOTEL or UPI_QR)");
        }
        String reference = normalizeUtr(method, req.paymentReference());

        Booking booking = bookingRepository.findBookingById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("Booking " + bookingId + " not found"));

        String tokenToVerify = (headerToken != null && !headerToken.trim().isEmpty())
                ? headerToken.trim()
                : (req.bookingToken() != null ? req.bookingToken().trim() : null);

        // Idempotent retry: the first confirm may have committed while its response was lost in
        // transit. Replaying the SAME method/reference just returns the booking as it now stands.
        if (booking.status() == BookingStatus.CONFIRMED) {
            return replayConfirmedBooking(booking, method, reference, tokenToVerify);
        }

        if (booking.status() != BookingStatus.HOLD) {
            throw new BookingValidationException("Only bookings in HOLD status can be confirmed; current status: " + booking.status());
        }

        long nowEpochSeconds = Instant.now(clock).getEpochSecond();
        if (booking.isHoldExpired(nowEpochSeconds)) {
            throw new BookingValidationException("Hold on booking " + bookingId + " has expired. Please create a new booking.");
        }

        // Verify RFC 7515 JWS token signature and validate claims against stored booking
        bookingTokenService.verifyAndValidateToken(tokenToVerify, booking, nowEpochSeconds);

        Booking.Builder confirming = booking.toBuilder()
                .status(BookingStatus.CONFIRMED)
                .confirmedAt(nowEpochSeconds)
                .paymentMethod(method);
        if (method == PaymentMethod.UPI_QR) {
            confirming.paymentReference(reference)
                    .amountPaid(booking.totalAmount())
                    .paymentStatus(PaymentStatus.PAID_ONLINE)
                    .paidAt(nowEpochSeconds);
        } else {
            confirming.paymentReference(null)
                    .amountPaid(BigDecimal.ZERO)
                    .paymentStatus(PaymentStatus.DUE_AT_CHECKIN)
                    .paidAt(null);
        }
        Booking confirmed = confirming.build();

        List<RoomInventory> updatedInventories = generateDateRange(booking.checkInDate(), booking.checkOutDate()).stream()
                .map(date -> new RoomInventory(
                        booking.roomNumber(),
                        date,
                        InventoryStatus.CONFIRMED,
                        booking.bookingId(),
                        0L,
                        2L,
                        booking.ttl()
                ))
                .toList();

        try {
            bookingRepository.confirmBooking(confirmed, updatedInventories, nowEpochSeconds);
        } catch (BookingValidationException ex) {
            // Two identical confirm requests can race: both read HOLD, one commits, the other's
            // conditional write fails. If the winner used our method/reference, this is a replay.
            Optional<Booking> current = bookingRepository.findBookingById(bookingId);
            if (current.isPresent() && current.get().status() == BookingStatus.CONFIRMED) {
                return replayConfirmedBooking(current.get(), method, reference, tokenToVerify);
            }
            throw ex;
        }
        return BookingResponse.from(confirmed, nowEpochSeconds, null,
                bookingTokenService.generateManageToken(confirmed, nowEpochSeconds));
    }

    private BookingResponse replayConfirmedBooking(Booking confirmed, PaymentMethod method, String reference, String holdToken) {
        boolean sameMethod = confirmed.paymentMethod() == method;
        boolean sameReference = method == PaymentMethod.PAY_AT_HOTEL
                || Objects.equals(firstReference(confirmed.paymentReference()), reference);
        if (!sameMethod || !sameReference) {
            throw new BookingValidationException("Booking " + confirmed.bookingId()
                    + " is already confirmed with a different payment method or reference");
        }
        long now = Instant.now(clock).getEpochSecond();
        // Proof of ownership without expiry: the hold token is long expired by the time a retry arrives.
        bookingTokenService.verifyAndValidateToken(holdToken, confirmed, now, true);
        return BookingResponse.from(confirmed, now, null, bookingTokenService.generateManageToken(confirmed, now));
    }

    private static String firstReference(String paymentReference) {
        if (paymentReference == null) {
            return null;
        }
        int comma = paymentReference.indexOf(',');
        return comma < 0 ? paymentReference : paymentReference.substring(0, comma);
    }

    /** A UPI UTR is exactly 12 digits. Pay-at-hotel takes no reference. */
    static String normalizeUtr(PaymentMethod method, String raw) {
        if (method == PaymentMethod.PAY_AT_HOTEL) {
            return null;
        }
        String utr = raw == null ? "" : raw.trim();
        if (!UTR_PATTERN.matcher(utr).matches()) {
            throw new BookingValidationException("Enter the 12-character UTR / UPI reference number (letters and digits) shown in your UPI app after paying.");
        }
        return utr.toUpperCase(Locale.ROOT);
    }

    /**
     * Records a UPI payment for the outstanding balance of a CONFIRMED booking — typically the extra
     * amount of a stay extension, or a pay-at-hotel booking the guest decides to settle early. Needs the
     * manage token. Idempotent for the same UTR.
     */
    public BookingResponse payBalance(String bookingId, PayBalanceRequest req, String manageToken) {
        long now = Instant.now(clock).getEpochSecond();
        bookingTokenService.verifyManageToken(manageToken, bookingId, now);

        Booking booking = bookingRepository.findBookingById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("Booking " + bookingId + " not found"));

        String utr = normalizeUtr(PaymentMethod.UPI_QR, req.paymentReference());

        if (booking.paymentReference() != null && Arrays.asList(booking.paymentReference().split(",")).contains(utr)) {
            return BookingResponse.from(booking, now);   // replay of a payment already recorded
        }
        if (!booking.isLive()) {
            throw new BookingValidationException("Payments can only be recorded for CONFIRMED or in-house bookings; current status: " + booking.status());
        }
        if (LocalDate.now(clock).isAfter(booking.checkOutDate())) {
            throw new BookingValidationException("This stay has ended; any balance must be settled with the front desk.");
        }
        if (booking.balanceDue().signum() <= 0) {
            throw new BookingValidationException("Nothing is due on this booking.");
        }

        BigDecimal previouslyPaid = booking.amountPaid();
        BigDecimal newPaid = booking.totalAmount();   // the guest pays the full outstanding balance
        Booking updated = booking.toBuilder()
                .amountPaid(newPaid)
                .paymentStatus(PaymentStatus.forBalance(booking.totalAmount(), newPaid))
                .paymentMethod(previouslyPaid.signum() == 0 ? PaymentMethod.UPI_QR : booking.paymentMethod())
                .paymentReference(booking.paymentReference() == null ? utr : booking.paymentReference() + "," + utr)
                .paidAt(now)
                .build();

        try {
            bookingRepository.recordPayment(updated, previouslyPaid, now);
        } catch (BookingValidationException ex) {
            Optional<Booking> current = bookingRepository.findBookingById(bookingId);
            if (current.isPresent() && current.get().paymentReference() != null
                    && Arrays.asList(current.get().paymentReference().split(",")).contains(utr)) {
                return BookingResponse.from(current.get(), now);
            }
            throw ex;
        }
        return BookingResponse.from(updated, now);
    }

    /**
     * Proves ownership of a booking by its guest phone number and returns it together with a fresh
     * manage token (the way back in after the confirmation screen is gone).
     */
    public BookingResponse accessBooking(String bookingId, AccessBookingRequest req) {
        // Bad input is a 400 before any database read; a wrong-but-valid phone is a 403 below.
        String suppliedPhone = PhoneNormalizer.require(req.guestPhone());
        Booking booking = findBookingForVerification(bookingId);

        if (!phoneMatches(booking.guestPhone(), suppliedPhone)) {
            throw new BookingForbiddenException("That phone number does not match this booking.");
        }
        if (!booking.isLive() && booking.status() != BookingStatus.CANCELLED && booking.status() != BookingStatus.CHECKED_OUT) {
            throw new BookingValidationException("Only confirmed, in-house, checked-out or cancelled bookings can be opened; current status: " + booking.status());
        }
        long now = Instant.now(clock).getEpochSecond();
        return BookingResponse.from(booking, now, null, bookingTokenService.generateManageToken(booking, now));
    }

    /**
     * Two-factor lookup: Booking ID + last 4 digits of the phone (or the full number). Returns a MASKED
     * summary and never a token or full contact details. A wrong ID, a wrong phone and an unknown
     * booking all give the same "not found" answer so the endpoint cannot be used to probe which
     * booking IDs exist.
     */
    public VerifiedBookingSummary lookupVerified(LookupBookingRequest req) {
        String proof = req.phone() == null ? "" : req.phone().trim();
        boolean last4 = PhoneNormalizer.isLast4(proof);
        String fullPhone = last4 ? null : PhoneNormalizer.require(proof);   // 400 for malformed input

        String bookingId = req.bookingId() == null ? "" : req.bookingId().trim();
        Optional<Booking> found = BOOKING_ID_PATTERN.matcher(bookingId).matches()
                ? bookingRepository.findBookingById(bookingId) : Optional.empty();

        boolean verified = found.isPresent() && (last4
                ? constantTimeEquals(PhoneNormalizer.last4(PhoneNormalizer.forStored(found.get().guestPhone())), proof)
                : phoneMatches(found.get().guestPhone(), fullPhone));
        if (!verified) {
            throw new BookingNotFoundException("No booking matches that Booking ID and phone number.");
        }
        return VerifiedBookingSummary.from(found.get(), Instant.now(clock).getEpochSecond());
    }

    private Booking findBookingForVerification(String bookingId) {
        String id = bookingId == null ? "" : bookingId.trim();
        if (!BOOKING_ID_PATTERN.matcher(id).matches()) {
            throw new BookingNotFoundException("Booking " + bookingId + " not found");
        }
        return bookingRepository.findBookingById(id)
                .orElseThrow(() -> new BookingNotFoundException("Booking " + id + " not found"));
    }

    /** Compares a stored phone (any historical spelling) with a canonical one, in constant time. */
    private static boolean phoneMatches(String storedPhone, String canonicalPhone) {
        return constantTimeEquals(PhoneNormalizer.forStored(storedPhone), canonicalPhone);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Cancels a CONFIRMED booking. Requires the manage token. Free until 24h before check-in
     * (12:00 PM IST); after that it is allowed only with {@code acknowledgeLate=true} and the booking
     * is flagged {@code lateCancellation}. Not possible once check-in time has been reached.
     * Inventory rows are deleted and the booking set to CANCELLED in one transaction.
     * Idempotent: an already-CANCELLED booking is returned unchanged with no error.
     */
    public BookingResponse cancelBooking(String bookingId, CancelBookingRequest req, String manageToken) {
        long now = Instant.now(clock).getEpochSecond();
        bookingTokenService.verifyManageToken(manageToken, bookingId, now);

        Booking booking = bookingRepository.findBookingById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("Booking " + bookingId + " not found"));

        if (booking.status() == BookingStatus.CANCELLED) {
            return BookingResponse.from(booking, now);
        }
        if (booking.status() != BookingStatus.CONFIRMED) {
            throw new BookingValidationException("Only CONFIRMED bookings can be cancelled; current status: " + booking.status());
        }
        if (CancellationPolicy.stayStarted(booking.checkInDate(), now)) {
            throw new BookingValidationException("Check-in time has passed, so this booking can no longer be cancelled online. Please contact the front desk.");
        }

        boolean late = CancellationPolicy.isLate(booking.checkInDate(), now);
        if (late && (req == null || !req.acknowledgeLate())) {
            throw new BookingValidationException("Free cancellation ended on "
                    + CancellationPolicy.freeCancellationDeadline(booking.checkInDate()).atZone(HOTEL_ZONE).toLocalDateTime()
                    + " IST (24 hours before check-in). Cancelling now is a late cancellation and any prepaid amount may not be refunded. "
                    + "Confirm again to proceed.");
        }

        Booking cancelled = booking.toBuilder()
                .status(BookingStatus.CANCELLED)
                .cancelledAt(now)
                .lateCancellation(late)
                .ttl(Math.min(booking.ttl(), now + RETENTION_TTL_SECONDS))
                .build();

        List<RoomInventory> inventories = generateDateRange(booking.checkInDate(), booking.checkOutDate()).stream()
                .map(date -> new RoomInventory(booking.roomNumber(), date, InventoryStatus.AVAILABLE,
                        booking.bookingId(), 0L, 0L, cancelled.ttl()))
                .toList();

        try {
            bookingRepository.cancelBooking(cancelled, inventories, now);
        } catch (BookingValidationException ex) {
            Optional<Booking> current = bookingRepository.findBookingById(bookingId);
            if (current.isPresent() && current.get().status() == BookingStatus.CANCELLED) {
                return BookingResponse.from(current.get(), now);   // a concurrent cancel won: still success
            }
            throw ex;
        }
        return BookingResponse.from(cancelled, now);
    }

    /**
     * Releases a HOLD the guest no longer wants (or is about to re-create after editing details).
     * Frees the inventory immediately instead of making the room wait out the 10-minute window,
     * and marks the booking RELEASED. Idempotent: releasing an already-released booking succeeds.
     *
     * <p>A live hold requires its signed booking token (proof the caller is the one who created it).
     * An already-expired hold needs no proof: it is dead anyway and only its record is tidied.
     */
    public BookingResponse releaseHold(String bookingId, String bookingToken) {
        Booking booking = bookingRepository.findBookingById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("Booking " + bookingId + " not found"));

        long nowEpochSeconds = Instant.now(clock).getEpochSecond();

        if (booking.status() == BookingStatus.RELEASED) {
            return BookingResponse.from(booking, nowEpochSeconds);
        }
        if (booking.status() != BookingStatus.HOLD) {
            throw new BookingValidationException(
                    "Only bookings in HOLD status can be released; current status: " + booking.status());
        }

        if (!booking.isHoldExpired(nowEpochSeconds)) {
            bookingTokenService.verifyAndValidateToken(
                    bookingToken == null ? null : bookingToken.trim(), booking, nowEpochSeconds);
        }

        Booking released = booking
                .withStatus(BookingStatus.RELEASED)
                .withTtl(nowEpochSeconds + RELEASED_RETENTION_SECONDS);

        List<RoomInventory> inventories = generateDateRange(booking.checkInDate(), booking.checkOutDate()).stream()
                .map(date -> new RoomInventory(
                        booking.roomNumber(),
                        date,
                        InventoryStatus.AVAILABLE,
                        booking.bookingId(),
                        0L,
                        0L,
                        released.ttl()
                ))
                .toList();

        bookingRepository.releaseHold(released, inventories, nowEpochSeconds);
        return BookingResponse.from(released, nowEpochSeconds);
    }

    /**
     * Extends a CONFIRMED stay (manage token required). The additional amount is ADDED to totalAmount
     * and paymentStatus is recomputed, so it becomes DUE_AT_CHECKIN until it is paid at the desk or
     * online via {@link #payBalance}.
     */
    public BookingResponse extendBooking(String bookingId, ExtendBookingRequest req, String manageToken) {
        long nowEpochSeconds = Instant.now(clock).getEpochSecond();
        bookingTokenService.verifyManageToken(manageToken, bookingId, nowEpochSeconds);

        Booking booking = bookingRepository.findBookingById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("Booking " + bookingId + " not found"));

        if (!booking.isLive()) {
            throw new BookingValidationException("Only CONFIRMED or in-house bookings can be extended; current status: " + booking.status());
        }
        if (LocalDate.now(clock).isAfter(booking.checkOutDate())) {
            throw new BookingValidationException("This stay has already ended and cannot be extended.");
        }

        if (!req.newCheckOutDate().isAfter(booking.checkOutDate())) {
            throw new BookingValidationException("New check-out date (" + req.newCheckOutDate() + ") must be after current check-out date (" + booking.checkOutDate() + ")");
        }

        Room room = RoomDirectoryConfig.findByRoomNumber(booking.roomNumber())
                .orElseThrow(() -> new BookingValidationException("Room " + booking.roomNumber() + " not found"));

        int totalNightsAfterExtension = (int) (req.newCheckOutDate().toEpochDay() - booking.checkInDate().toEpochDay());
        if (totalNightsAfterExtension > MAX_STAY_NIGHTS) {
            throw new BookingValidationException("A single stay cannot exceed " + MAX_STAY_NIGHTS
                    + " nights. This extension would make it " + totalNightsAfterExtension
                    + " nights; please contact the front desk for longer stays.");
        }

        int additionalNights = (int) (req.newCheckOutDate().toEpochDay() - booking.checkOutDate().toEpochDay());
        Room.TariffCalculation extraTariff = room.calculateTariff(booking.guestCount(), additionalNights);

        BigDecimal updatedTotal = booking.totalAmount().add(extraTariff.totalAmount());
        // The stay now ends later, so the retention clock must move with it.
        long extendedTtl = retentionTtlFor(req.newCheckOutDate());

        Booking updatedBooking = booking.toBuilder()
                .checkOutDate(req.newCheckOutDate())
                .subtotal(booking.subtotal().add(extraTariff.subtotal()))
                .gstAmount(booking.gstAmount().add(extraTariff.gstAmount()))
                .totalAmount(updatedTotal)
                .paymentStatus(PaymentStatus.forBalance(updatedTotal, booking.amountPaid()))
                .ttl(extendedTtl)
                .build();

        List<RoomInventory> extensionInventories = generateDateRange(booking.checkOutDate(), req.newCheckOutDate()).stream()
                .map(date -> new RoomInventory(
                        booking.roomNumber(),
                        date,
                        InventoryStatus.CONFIRMED,
                        booking.bookingId(),
                        0L,
                        1L,
                        extendedTtl
                ))
                .toList();

        bookingRepository.extendBooking(updatedBooking, extensionInventories, nowEpochSeconds);
        return BookingResponse.from(updatedBooking, nowEpochSeconds);
    }

    /**
     * Unauthenticated read by booking ID. Returns the booking's state (status, dates, amounts) with the
     * guest's name / phone / e-mail masked, and NEVER a hold or manage token or a payment reference.
     * Full details require {@link #accessBooking} (booking ID + full phone).
     */
    public BookingResponse getBooking(String bookingId) {
        long nowEpochSeconds = Instant.now(clock).getEpochSecond();
        Booking b = findBookingForVerification(bookingId);
        Booking masked = b.toBuilder()
                .guestName(InputSanitizer.maskName(b.guestName()))
                .guestPhone(PhoneNormalizer.mask(b.guestPhone()))
                .guestEmail(InputSanitizer.maskEmail(b.guestEmail()))
                .paymentReference(null)
                .build();
        return BookingResponse.from(masked, nowEpochSeconds, null, null);
    }

    /**
     * Phone-only lookup. A phone number is not a secret (it is printed on cards and shared freely), so
     * this returns ONLY unprivileged, masked summaries: partial booking ID, status, dates, room
     * category and a masked guest name. No tokens, no e-mail, no room number, no amounts. To act on a
     * booking the guest must additionally prove the full number with the booking ID
     * ({@link #accessBooking}) or use the two-factor {@link #lookupVerified}.
     */
    public List<BookingSummaryResponse> lookupSummariesByPhone(String rawPhone) {
        String phone = PhoneNormalizer.require(rawPhone);   // 400 before hashing / DynamoDB
        return bookingRepository.findBookingsByGuestPhone(phone).stream()
                .filter(b -> b.status() != BookingStatus.RELEASED)
                .map(BookingSummaryResponse::from)
                .toList();
    }

    public RoomAvailabilityResponse getAvailability(LocalDate checkInDate, LocalDate checkOutDate, Optional<RoomType> roomType) {
        validateStayDates(checkInDate, checkOutDate, LocalDate.now(clock));

        List<LocalDate> dates = generateDateRange(checkInDate, checkOutDate);
        long nowEpochSeconds = Instant.now(clock).getEpochSecond();

        List<RoomInventory> lockedInventories = bookingRepository.findInventoriesForDates(dates).stream()
                .filter(inv -> inv.isCurrentlyLocked(nowEpochSeconds))
                .toList();

        Set<Integer> lockedRoomNumbers = lockedInventories.stream()
                .map(RoomInventory::roomNumber)
                .collect(Collectors.toSet());

        List<RoomAvailabilityResponse.AvailableRoomDto> availableRooms = RoomDirectoryConfig.getStaticRooms().stream()
                .filter(r -> !lockedRoomNumbers.contains(r.roomNumber()))
                .filter(r -> roomType.map(t -> r.type() == t).orElse(true))
                .map(r -> new RoomAvailabilityResponse.AvailableRoomDto(
                        r.roomNumber(),
                        r.floor(),
                        r.type(),
                        r.bedType(),
                        r.baseRate(),
                        r.extraPersonRate(),
                        r.baseOccupancy(),
                        r.maxOccupancy()
                ))
                .toList();

        return new RoomAvailabilityResponse(
                checkInDate,
                checkOutDate,
                availableRooms.size(),
                RoomDirectoryConfig.STANDARD_CHECKOUT_TIME,
                availableRooms
        );
    }

    /**
     * Epoch second at which DynamoDB may delete a booking's rows: the end of the check-out day
     * (23:59:59 IST) plus the audit-retention period.
     */
    public static long retentionTtlFor(LocalDate checkOutDate) {
        return checkOutDate.atTime(23, 59, 59).atZone(HOTEL_ZONE).toEpochSecond() + RETENTION_TTL_SECONDS;
    }

    /** Date rules shared by hold and availability search. Messages are shown to guests, so keep them plain. */
    static void validateStayDates(LocalDate checkIn, LocalDate checkOut, LocalDate today) {
        if (checkIn.isBefore(today)) {
            throw new BookingValidationException("Check-in date cannot be in the past: " + checkIn);
        }
        if (!checkOut.isAfter(checkIn)) {
            throw new BookingValidationException("Check-out date must be after check-in date");
        }
        LocalDate latestCheckIn = today.plusDays(MAX_ADVANCE_DAYS);
        if (checkIn.isAfter(latestCheckIn)) {
            throw new BookingValidationException("Bookings can be made at most " + MAX_ADVANCE_DAYS
                    + " days in advance (latest check-in " + latestCheckIn + ").");
        }
        long nights = checkOut.toEpochDay() - checkIn.toEpochDay();
        if (nights > MAX_STAY_NIGHTS) {
            throw new BookingValidationException("A single stay cannot exceed " + MAX_STAY_NIGHTS
                    + " nights (you selected " + nights + "). Please choose a shorter stay or call the front desk.");
        }
    }

    public static List<LocalDate> generateDateRange(LocalDate startDateInclusive, LocalDate endDateExclusive) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate curr = startDateInclusive;
        while (curr.isBefore(endDateExclusive)) {
            dates.add(curr);
            curr = curr.plusDays(1);
        }
        return dates;
    }
}

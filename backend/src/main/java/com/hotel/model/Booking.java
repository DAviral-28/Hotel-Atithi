package com.hotel.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Represents a hotel booking record.
 * DynamoDB:
 * PK = BOOKING#<BookingId>
 * SK = DETAILS
 * GSI1PK = GUEST#<hmac-sha256(pepper, canonical 10-digit guestPhone)> — a blind index; see
 *          PiiEncryptionService.blindIndex (rows written before Phase 3 hold a plain SHA-256 and are
 *          migrated lazily). Never build this key from a raw phone
 *          number (that's why there is no buildGsi1Pk helper here — the hash has to be
 *          computed by whoever holds the PiiEncryptionService, not by this plain model).
 * GSI1SK = BOOKING#<checkInDate>
 *
 * Payment model (no payment gateway — both methods are zero fee):
 * - paymentMethod  : PAY_AT_HOTEL or UPI_QR (null while only a HOLD).
 * - amountPaid     : money the guest has paid ONLINE so far (0 for pay-at-hotel).
 * - balanceDue()   : totalAmount - amountPaid, to be collected at the front desk.
 * - paymentStatus  : PENDING (hold) / PAID_ONLINE (balance is zero) / DUE_AT_CHECKIN (balance remains).
 * - paymentReference: UPI UTR(s), comma-separated when a stay extension was paid separately.
 */
public record Booking(
        String bookingId,
        int roomNumber,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        String guestName,
        String guestPhone,
        String guestEmail,
        int guestCount,
        BigDecimal baseRatePerNight,
        BigDecimal extraGuestRatePerNight,
        BigDecimal subtotal,
        BigDecimal gstAmount,
        BigDecimal totalAmount,
        BookingStatus status,
        long createdAt,
        long expiresAt,
        String paymentReference,
        Long confirmedAt,
        String checkoutTime,
        long ttl,
        PaymentMethod paymentMethod,
        PaymentStatus paymentStatus,
        BigDecimal amountPaid,
        Long paidAt,
        Long cancelledAt,
        boolean lateCancellation,
        // ---- Phase 4 (front desk): all null until staff act on the booking ----
        Long checkedInAt,
        Long checkedOutAt,
        /** Set when staff reconciled the payment (UPI UTR checked against the statement, or cash/UPI collected at the desk). */
        Long paymentVerifiedAt
) {
    public Booking {
        if (paymentStatus == null) {
            paymentStatus = PaymentStatus.PENDING;
        }
        if (amountPaid == null) {
            amountPaid = BigDecimal.ZERO;
        }
    }

    /** Pre-Phase-4 shape (26 fields): no front-desk timestamps yet. Kept so existing callers and tests compile unchanged. */
    public Booking(
            String bookingId, int roomNumber, LocalDate checkInDate, LocalDate checkOutDate,
            String guestName, String guestPhone, String guestEmail, int guestCount,
            BigDecimal baseRatePerNight, BigDecimal extraGuestRatePerNight, BigDecimal subtotal,
            BigDecimal gstAmount, BigDecimal totalAmount, BookingStatus status, long createdAt,
            long expiresAt, String paymentReference, Long confirmedAt, String checkoutTime, long ttl,
            PaymentMethod paymentMethod, PaymentStatus paymentStatus, BigDecimal amountPaid, Long paidAt,
            Long cancelledAt, boolean lateCancellation
    ) {
        this(bookingId, roomNumber, checkInDate, checkOutDate, guestName, guestPhone, guestEmail, guestCount,
                baseRatePerNight, extraGuestRatePerNight, subtotal, gstAmount, totalAmount, status, createdAt,
                expiresAt, paymentReference, confirmedAt, checkoutTime, ttl, paymentMethod, paymentStatus,
                amountPaid, paidAt, cancelledAt, lateCancellation, null, null, null);
    }

    /** Convenience constructor for a booking with no payment/cancellation data yet (a fresh HOLD). */
    public Booking(
            String bookingId, int roomNumber, LocalDate checkInDate, LocalDate checkOutDate,
            String guestName, String guestPhone, String guestEmail, int guestCount,
            BigDecimal baseRatePerNight, BigDecimal extraGuestRatePerNight, BigDecimal subtotal,
            BigDecimal gstAmount, BigDecimal totalAmount, BookingStatus status, long createdAt,
            long expiresAt, String paymentReference, Long confirmedAt, String checkoutTime, long ttl
    ) {
        this(bookingId, roomNumber, checkInDate, checkOutDate, guestName, guestPhone, guestEmail, guestCount,
                baseRatePerNight, extraGuestRatePerNight, subtotal, gstAmount, totalAmount, status, createdAt,
                expiresAt, paymentReference, confirmedAt, checkoutTime, ttl,
                null, PaymentStatus.PENDING, BigDecimal.ZERO, null, null, false, null, null, null);
    }

    public static String buildPk(String bookingId) {
        return "BOOKING#" + bookingId;
    }

    public static String buildSk() {
        return "DETAILS";
    }

    public static String buildGsi1Sk(LocalDate checkInDate) {
        return "BOOKING#" + checkInDate.toString();
    }

    /** Amount still to be collected at the front desk (never negative). */
    public BigDecimal balanceDue() {
        return totalAmount.subtract(amountPaid).max(BigDecimal.ZERO);
    }

    /** True while the guest still holds a room (confirmed or in-house) - the states staff can act on. */
    public boolean isLive() {
        return status == BookingStatus.CONFIRMED || status == BookingStatus.CHECKED_IN;
    }

    public boolean isHoldExpired(long nowEpochSeconds) {
        return status == BookingStatus.HOLD && nowEpochSeconds >= expiresAt;
    }

    public Booking withStatus(BookingStatus newStatus) {
        return toBuilder().status(newStatus).build();
    }

    public Booking withTtl(long newTtl) {
        return toBuilder().ttl(newTtl).build();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Mutable copy-builder so state transitions don't have to repeat a 26-argument constructor call. */
    public static final class Builder {
        private String bookingId;
        private int roomNumber;
        private LocalDate checkInDate;
        private LocalDate checkOutDate;
        private String guestName;
        private String guestPhone;
        private String guestEmail;
        private int guestCount;
        private BigDecimal baseRatePerNight;
        private BigDecimal extraGuestRatePerNight;
        private BigDecimal subtotal;
        private BigDecimal gstAmount;
        private BigDecimal totalAmount;
        private BookingStatus status;
        private long createdAt;
        private long expiresAt;
        private String paymentReference;
        private Long confirmedAt;
        private String checkoutTime;
        private long ttl;
        private PaymentMethod paymentMethod;
        private PaymentStatus paymentStatus;
        private BigDecimal amountPaid;
        private Long paidAt;
        private Long cancelledAt;
        private boolean lateCancellation;
        private Long checkedInAt;
        private Long checkedOutAt;
        private Long paymentVerifiedAt;

        private Builder(Booking b) {
            this.bookingId = b.bookingId;
            this.roomNumber = b.roomNumber;
            this.checkInDate = b.checkInDate;
            this.checkOutDate = b.checkOutDate;
            this.guestName = b.guestName;
            this.guestPhone = b.guestPhone;
            this.guestEmail = b.guestEmail;
            this.guestCount = b.guestCount;
            this.baseRatePerNight = b.baseRatePerNight;
            this.extraGuestRatePerNight = b.extraGuestRatePerNight;
            this.subtotal = b.subtotal;
            this.gstAmount = b.gstAmount;
            this.totalAmount = b.totalAmount;
            this.status = b.status;
            this.createdAt = b.createdAt;
            this.expiresAt = b.expiresAt;
            this.paymentReference = b.paymentReference;
            this.confirmedAt = b.confirmedAt;
            this.checkoutTime = b.checkoutTime;
            this.ttl = b.ttl;
            this.paymentMethod = b.paymentMethod;
            this.paymentStatus = b.paymentStatus;
            this.amountPaid = b.amountPaid;
            this.paidAt = b.paidAt;
            this.cancelledAt = b.cancelledAt;
            this.lateCancellation = b.lateCancellation;
            this.checkedInAt = b.checkedInAt;
            this.checkedOutAt = b.checkedOutAt;
            this.paymentVerifiedAt = b.paymentVerifiedAt;
        }

        public Builder checkOutDate(LocalDate v) { this.checkOutDate = v; return this; }
        public Builder guestName(String v) { this.guestName = v; return this; }
        public Builder guestPhone(String v) { this.guestPhone = v; return this; }
        public Builder guestEmail(String v) { this.guestEmail = v; return this; }
        public Builder subtotal(BigDecimal v) { this.subtotal = v; return this; }
        public Builder gstAmount(BigDecimal v) { this.gstAmount = v; return this; }
        public Builder totalAmount(BigDecimal v) { this.totalAmount = v; return this; }
        public Builder status(BookingStatus v) { this.status = v; return this; }
        public Builder paymentReference(String v) { this.paymentReference = v; return this; }
        public Builder confirmedAt(Long v) { this.confirmedAt = v; return this; }
        public Builder ttl(long v) { this.ttl = v; return this; }
        public Builder paymentMethod(PaymentMethod v) { this.paymentMethod = v; return this; }
        public Builder paymentStatus(PaymentStatus v) { this.paymentStatus = v; return this; }
        public Builder amountPaid(BigDecimal v) { this.amountPaid = v; return this; }
        public Builder paidAt(Long v) { this.paidAt = v; return this; }
        public Builder cancelledAt(Long v) { this.cancelledAt = v; return this; }
        public Builder lateCancellation(boolean v) { this.lateCancellation = v; return this; }
        public Builder checkedInAt(Long v) { this.checkedInAt = v; return this; }
        public Builder checkedOutAt(Long v) { this.checkedOutAt = v; return this; }
        public Builder paymentVerifiedAt(Long v) { this.paymentVerifiedAt = v; return this; }
        public Builder checkInDate(LocalDate v) { this.checkInDate = v; return this; }
        public Builder createdAt(long v) { this.createdAt = v; return this; }
        public Builder expiresAt(long v) { this.expiresAt = v; return this; }

        public Booking build() {
            return new Booking(bookingId, roomNumber, checkInDate, checkOutDate, guestName, guestPhone, guestEmail,
                    guestCount, baseRatePerNight, extraGuestRatePerNight, subtotal, gstAmount, totalAmount, status,
                    createdAt, expiresAt, paymentReference, confirmedAt, checkoutTime, ttl, paymentMethod,
                    paymentStatus, amountPaid, paidAt, cancelledAt, lateCancellation, checkedInAt, checkedOutAt,
                    paymentVerifiedAt);
        }
    }
}

package com.hotel;

import com.hotel.dto.BookingResponse;
import com.hotel.dto.ConfirmBookingRequest;
import com.hotel.dto.HoldBookingRequest;
import com.hotel.exception.BookingValidationException;
import com.hotel.model.Booking;
import com.hotel.model.BookingStatus;
import com.hotel.security.BookingTokenService;
import com.hotel.security.PiiEncryptionService;
import com.hotel.service.BookingService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class SecurityEncryptionTest {

    private PiiEncryptionService piiEncryptionService;
    private BookingTokenService bookingTokenService;
    private BookingConcurrencyTest.InMemoryBookingRepository repository;
    private BookingConcurrencyTest.MutableClock testClock;
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        piiEncryptionService = new PiiEncryptionService("test-secret-encryption-key-32b!");
        bookingTokenService = new BookingTokenService("test-signing-key-for-jws-hmac256-32bytes!");
        repository = new BookingConcurrencyTest.InMemoryBookingRepository();
        Instant initialInstant = Instant.parse("2026-10-01T10:00:00Z");
        testClock = new BookingConcurrencyTest.MutableClock(initialInstant, ZoneOffset.UTC);
        bookingService = new BookingService(repository, testClock, bookingTokenService);
    }

    @Test
    @DisplayName("1. RFC 7516 JWE Roundtrip: encrypts PII into AES-256-GCM compact format and decrypts cleanly")
    void testJweRoundtrip() {
        String originalPhone = "+919876543210";
        String originalEmail = "vip.guest@luxuryhotel.in";

        String encryptedPhone = piiEncryptionService.encryptPii(originalPhone);
        String encryptedEmail = piiEncryptionService.encryptPii(originalEmail);

        assertNotNull(encryptedPhone);
        assertNotNull(encryptedEmail);

        // Compact JWE has exactly 5 dot-separated parts: header.encryptedKey.iv.ciphertext.tag
        assertEquals(5, encryptedPhone.split("\\.").length, "Encrypted phone must be a 5-part compact JWE");
        assertEquals(5, encryptedEmail.split("\\.").length, "Encrypted email must be a 5-part compact JWE");

        // Cleartext must not appear anywhere in the ciphertext
        assertFalse(encryptedPhone.contains(originalPhone), "Ciphertext must not contain cleartext phone");
        assertFalse(encryptedEmail.contains(originalEmail), "Ciphertext must not contain cleartext email");

        // Decryption restores exact plaintext
        String decryptedPhone = piiEncryptionService.decryptPii(encryptedPhone);
        String decryptedEmail = piiEncryptionService.decryptPii(encryptedEmail);

        assertEquals(originalPhone, decryptedPhone);
        assertEquals(originalEmail, decryptedEmail);
    }

    @Test
    @DisplayName("2. RFC 7516 JWE Tampering: altering ciphertext or auth tag fails decryption")
    void testJweTamperingRejection() {
        String sensitiveData = "Confidential Guest Notes";
        String jwe = piiEncryptionService.encryptPii(sensitiveData);

        String[] parts = jwe.split("\\.");
        assertEquals(5, parts.length);

        // Tamper with the ciphertext (part 4)
        char modifiedChar = parts[3].charAt(0) == 'A' ? 'B' : 'A';
        String tamperedCiphertext = modifiedChar + parts[3].substring(1);
        String tamperedJwe = String.join(".", parts[0], parts[1], parts[2], tamperedCiphertext, parts[4]);

        assertThrows(SecurityException.class, () -> piiEncryptionService.decryptPii(tamperedJwe),
                "Tampered JWE ciphertext must fail authentication tag verification");
    }

    @Test
    @DisplayName("3. Deterministic Blind Indexing: produces consistent SHA-256 hex hashes for GSI lookups")
    void testDeterministicBlindIndexing() {
        String phone1 = "+919876543210";
        String phone2 = "+919876543211";

        String hash1a = piiEncryptionService.hashDeterministic(phone1);
        String hash1b = piiEncryptionService.hashDeterministic(phone1);
        String hash2 = piiEncryptionService.hashDeterministic(phone2);

        assertNotNull(hash1a);
        assertEquals(64, hash1a.length(), "SHA-256 hex string must be 64 characters");
        assertEquals(hash1a, hash1b, "Deterministic hash must produce identical output for identical input");
        assertNotEquals(hash1a, hash2, "Distinct phone numbers must yield distinct blind index hashes");
    }

    @Test
    @DisplayName("4. RFC 7515 JWS Token Signing & Verification: creates tamper-proof tokens")
    void testJwsTokenSigningAndVerification() {
        Booking booking = new Booking(
                "BOOK-999", 101,
                LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 7),
                "Alice", "+919876543210", "alice@example.com",
                2, new BigDecimal("600.00"), new BigDecimal("0.00"),
                new BigDecimal("1200.00"), new BigDecimal("60.00"), new BigDecimal("1260.00"),
                BookingStatus.HOLD,
                Instant.parse("2026-10-01T10:00:00Z").getEpochSecond(),
                Instant.parse("2026-10-01T10:10:00Z").getEpochSecond(),
                null, null, "12:00 PM", 1700000000L
        );

        String token = bookingTokenService.generateHoldToken(booking);
        assertNotNull(token);

        // Compact JWS has 3 parts: header.payload.signature
        assertEquals(3, token.split("\\.").length, "JWS token must have 3 parts");

        // Verify with matching booking
        long now = Instant.parse("2026-10-01T10:05:00Z").getEpochSecond();
        assertDoesNotThrow(() -> bookingTokenService.verifyAndValidateToken(token, booking, now));
    }

    @Test
    @DisplayName("5. RFC 7515 JWS Tampering: altering token payload causes verification failure")
    void testJwsTamperingRejection() {
        Booking booking = new Booking(
                "BOOK-100", 101,
                LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 7),
                "Bob", "+919876543211", "bob@example.com",
                2, new BigDecimal("600.00"), new BigDecimal("0.00"),
                new BigDecimal("1200.00"), new BigDecimal("60.00"), new BigDecimal("1260.00"),
                BookingStatus.HOLD,
                Instant.parse("2026-10-01T10:00:00Z").getEpochSecond(),
                Instant.parse("2026-10-01T10:10:00Z").getEpochSecond(),
                null, null, "12:00 PM", 1700000000L
        );

        String validToken = bookingTokenService.generateHoldToken(booking);
        String[] parts = validToken.split("\\.");

        // Tamper with payload: replace totalAmount with 100.00
        String tamperedPayloadJson = "{\"roomNumber\":101,\"checkIn\":\"2026-10-05\",\"totalAmount\":\"100.00\",\"checkOut\":\"2026-10-07\",\"bookingId\":\"BOOK-100\"}";
        String tamperedPayloadBase64 = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tamperedPayloadJson.getBytes(StandardCharsets.UTF_8));
        String tamperedToken = parts[0] + "." + tamperedPayloadBase64 + "." + parts[2];

        long now = Instant.parse("2026-10-01T10:05:00Z").getEpochSecond();
        BookingValidationException ex = assertThrows(BookingValidationException.class, () -> {
            bookingTokenService.verifyAndValidateToken(tamperedToken, booking, now);
        });

        assertTrue(ex.getMessage().contains("tampering detected") || ex.getMessage().contains("Invalid booking token signature"));
    }

    @Test
    @DisplayName("6. End-to-end Hold & Confirm flow with RFC 7515 JWS token verification")
    void testEndToEndHoldAndConfirmWithToken() {
        HoldBookingRequest holdReq = new HoldBookingRequest(
                101, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12),
                2, "David", "+919876543212", "david@example.com"
        );

        BookingResponse holdResponse = bookingService.holdRoom(holdReq);
        assertNotNull(holdResponse.bookingId());
        assertNotNull(holdResponse.bookingToken(), "Hold response must include signed JWS token");

        // Confirm using the valid signed token
        ConfirmBookingRequest confirmReq = ConfirmBookingRequest.upi("123123123123", holdResponse.bookingToken());
        BookingResponse confirmed = bookingService.confirmBooking(holdResponse.bookingId(), confirmReq);

        assertEquals(BookingStatus.CONFIRMED, confirmed.status());
        assertEquals("123123123123", confirmed.paymentReference());
        assertNotNull(confirmed.manageToken(), "Confirm must hand back a manage token for cancel / extend / pay");

        // Attempting to confirm with missing token throws exception
        HoldBookingRequest holdReq2 = new HoldBookingRequest(
                102, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12),
                2, "Eve", "+919876543213", "eve@example.com"
        );
        BookingResponse holdResponse2 = bookingService.holdRoom(holdReq2);

        ConfirmBookingRequest missingTokenReq = ConfirmBookingRequest.upi("456456456456", null);
        assertThrows(BookingValidationException.class, () -> {
            bookingService.confirmBooking(holdResponse2.bookingId(), missingTokenReq);
        });
    }
}

package com.hotel.security;

import com.hotel.exception.BookingForbiddenException;
import com.hotel.exception.BookingValidationException;
import com.hotel.model.Booking;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Date;

/**
 * RFC 7515 (JWS) service for generating and verifying tamper-proof booking tokens.
 * Uses HMAC-SHA256 (HS256).
 */
@Service
public class BookingTokenService {

    private static final Logger log = LoggerFactory.getLogger(BookingTokenService.class);
    private static final String DEFAULT_DEV_SIGNING_KEY = "hotel-booking-jws-hmac-sha256-signing-secret-key-32bytes!";

    // Matches the SPRING_PROFILES_ACTIVE value the CDK stack sets on the deployed Lambda.
    // Used only to fail fast if a real signing key was never configured for that deployment.
    private static final String PRODUCTION_PROFILE = "lambda";

    private final byte[] signingKey;

    public BookingTokenService(
            @Value("${HOTEL_SIGNING_KEY_256:${hotel.security.jws-key:}}") String configuredKey
    ) {
        if (configuredKey == null || configuredKey.trim().isEmpty()) {
            if (isProductionDeployment()) {
                throw new IllegalStateException(
                        "HOTEL_SIGNING_KEY_256 is not set. Refusing to start the '" + PRODUCTION_PROFILE
                                + "' profile with the hardcoded default signing key — anyone with the source "
                                + "code or the deployed JAR could forge valid booking-confirmation tokens. Set "
                                + "HOTEL_SIGNING_KEY_256 as a Lambda environment variable (see "
                                + "infrastructure/lib/hotel-infra-stack.ts) before deploying.");
            }
            this.signingKey = DEFAULT_DEV_SIGNING_KEY.getBytes(StandardCharsets.UTF_8);
            log.warn("Initialized BookingTokenService using the DEFAULT DEVELOPMENT signing secret. " +
                    "This is only safe for local development/tests — never for a deployed environment.");
        } else {
            byte[] keyBytes = configuredKey.trim().getBytes(StandardCharsets.UTF_8);
            if (keyBytes.length < 32) {
                try {
                    MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                    this.signingKey = sha256.digest(keyBytes);
                    log.info("Normalized configured JWS key to 256-bit via SHA-256 digest.");
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException("SHA-256 algorithm not available", e);
                }
            } else {
                this.signingKey = keyBytes;
                log.info("Initialized BookingTokenService using configured HMAC-SHA256 key.");
            }
        }
    }

    /**
     * Generates a signed RFC 7515 compact JWS token binding booking attributes and expiration.
     */
    public String generateHoldToken(Booking booking) {
        try {
            // NOTE: JWS only guarantees integrity, not confidentiality — this token is base64, not
            // encrypted, and is handed straight to the browser. Only non-PII claims needed for
            // verifyAndValidateToken's checks below belong here; guest PII (name/phone/email) must
            // never be embedded, or it would leak in plain sight despite being encrypted at rest.
            JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                    .subject(booking.bookingId())
                    .claim("bookingId", booking.bookingId())
                    .claim("roomNumber", booking.roomNumber())
                    .claim("checkIn", booking.checkInDate().toString())
                    .claim("checkOut", booking.checkOutDate().toString())
                    .claim("totalAmount", booking.totalAmount().toPlainString())
                    .issueTime(new Date(booking.createdAt() * 1000L))
                    .expirationTime(new Date(booking.expiresAt() * 1000L))
                    .build();

            JWSHeader header = new JWSHeader(JWSAlgorithm.HS256);
            JWSObject jwsObject = new JWSObject(header, new Payload(claimsSet.toJSONObject()));

            JWSSigner signer = new MACSigner(signingKey);
            jwsObject.sign(signer);

            return jwsObject.serialize();
        } catch (JOSEException e) {
            throw new SecurityException("Failed to generate RFC 7515 JWS booking token", e);
        }
    }

    /**
     * Verifies JWS token signature integrity and validates claims against stored booking.
     * Throws BookingValidationException if invalid or tampered.
     */
    public void verifyAndValidateToken(String tokenString, Booking storedBooking, long nowEpochSeconds) {
        verifyAndValidateToken(tokenString, storedBooking, nowEpochSeconds, false);
    }

    /**
     * Same as {@link #verifyAndValidateToken(String, Booking, long)} but can skip the expiry check.
     * Used when replaying an already-committed confirmation: the 10-minute hold token has expired by
     * then, yet a valid signature over the same booking still proves the caller created that hold.
     * The signature and every bound claim (id, room, dates, amount) are still enforced.
     */
    public void verifyAndValidateToken(String tokenString, Booking storedBooking, long nowEpochSeconds,
                                       boolean ignoreExpiry) {
        if (tokenString == null || tokenString.trim().isEmpty()) {
            throw new BookingValidationException("Missing required X-Booking-Token for confirmation");
        }

        try {
            JWSObject jwsObject = JWSObject.parse(tokenString);
            JWSVerifier verifier = new MACVerifier(signingKey);

            if (!jwsObject.verify(verifier)) {
                log.warn("JWS token signature verification failed for booking {}", storedBooking.bookingId());
                throw new BookingValidationException("Invalid booking token signature: cryptographic tampering detected");
            }

            var claims = jwsObject.getPayload().toJSONObject();

            // 1. Verify expiration
            Object expObj = claims.get("exp");
            if (expObj != null && !ignoreExpiry) {
                long expSeconds = ((Number) expObj).longValue();
                if (nowEpochSeconds > expSeconds) {
                    throw new BookingValidationException("Booking token has expired. Please initiate a new booking.");
                }
            }

            // 2. Validate bookingId match
            String tokenBookingId = (String) claims.get("bookingId");
            if (tokenBookingId == null || !tokenBookingId.equals(storedBooking.bookingId())) {
                throw new BookingValidationException("Booking token does not match requested booking ID");
            }

            // 3. Validate roomNumber match
            Object roomObj = claims.get("roomNumber");
            if (roomObj == null || ((Number) roomObj).intValue() != storedBooking.roomNumber()) {
                throw new BookingValidationException("Booking token room number does not match stored reservation");
            }

            // 4. Validate dates match
            String checkInStr = (String) claims.get("checkIn");
            String checkOutStr = (String) claims.get("checkOut");
            if (checkInStr == null || !LocalDate.parse(checkInStr).equals(storedBooking.checkInDate())
                    || checkOutStr == null || !LocalDate.parse(checkOutStr).equals(storedBooking.checkOutDate())) {
                throw new BookingValidationException("Booking token stay dates do not match stored reservation");
            }

            // 5. Validate totalAmount match (prevents price tampering)
            String amountStr = (String) claims.get("totalAmount");
            if (amountStr == null || new BigDecimal(amountStr).compareTo(storedBooking.totalAmount()) != 0) {
                throw new BookingValidationException("Booking token total amount does not match stored reservation");
            }

        } catch (BookingValidationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to parse or verify JWS token: {}", e.getMessage());
            throw new BookingValidationException("Malformed or invalid booking token: " + e.getMessage());
        }
    }

    /** Claim that separates a management token from a hold token (a hold token can never manage a booking). */
    private static final String PURPOSE_MANAGE = "manage";

    /** A manage token outlives check-out by this long so a guest can still open the invoice afterwards. */
    public static final long MANAGE_TOKEN_GRACE_SECONDS = 86400L * 30L;

    /**
     * Issues the post-confirmation "manage" token: a tamper-proof JWS proving the holder confirmed
     * (or phone-verified) this booking. It authorises cancel, extend and pay-balance. Like the hold
     * token it carries no PII. It expires 30 days after check-out (never sooner than a day from now).
     */
    public String generateManageToken(Booking booking, long nowEpochSeconds) {
        try {
            long checkoutEnd = booking.checkOutDate().plusDays(1)
                    .atStartOfDay(com.hotel.config.AppConfig.HOTEL_ZONE).toEpochSecond();
            long exp = Math.max(checkoutEnd + MANAGE_TOKEN_GRACE_SECONDS, nowEpochSeconds + 86400L);

            JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                    .subject(booking.bookingId())
                    .claim("bookingId", booking.bookingId())
                    .claim("purpose", PURPOSE_MANAGE)
                    .issueTime(new Date(nowEpochSeconds * 1000L))
                    .expirationTime(new Date(exp * 1000L))
                    .build();

            JWSObject jwsObject = new JWSObject(new JWSHeader(JWSAlgorithm.HS256), new Payload(claimsSet.toJSONObject()));
            jwsObject.sign(new MACSigner(signingKey));
            return jwsObject.serialize();
        } catch (JOSEException e) {
            throw new SecurityException("Failed to generate booking management token", e);
        }
    }

    /**
     * Verifies a manage token for this booking. Any failure (missing, forged, expired, wrong booking,
     * or a hold token presented in its place) throws {@link BookingForbiddenException} (HTTP 403).
     */
    public void verifyManageToken(String tokenString, String bookingId, long nowEpochSeconds) {
        if (tokenString == null || tokenString.trim().isEmpty()) {
            throw new BookingForbiddenException("A booking management token is required for this action.");
        }
        try {
            JWSObject jwsObject = JWSObject.parse(tokenString.trim());
            if (!jwsObject.verify(new MACVerifier(signingKey))) {
                throw new BookingForbiddenException("Invalid booking management token.");
            }
            var claims = jwsObject.getPayload().toJSONObject();
            if (!PURPOSE_MANAGE.equals(claims.get("purpose"))) {
                throw new BookingForbiddenException("This token cannot be used to manage a booking.");
            }
            Object expObj = claims.get("exp");
            if (expObj == null || nowEpochSeconds > ((Number) expObj).longValue()) {
                throw new BookingForbiddenException("Booking management token has expired. Please verify your phone number again.");
            }
            if (!bookingId.equals(claims.get("bookingId"))) {
                throw new BookingForbiddenException("Token does not belong to this booking.");
            }
        } catch (BookingForbiddenException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to parse or verify manage token: {}", e.getMessage());
            throw new BookingForbiddenException("Malformed booking management token.");
        }
    }

    /**
     * True only when running as the deployed AWS Lambda (SPRING_PROFILES_ACTIVE=lambda, set by the
     * CDK stack). Read directly from the OS environment rather than injected via Spring so the
     * existing single-argument constructor keeps working unchanged for unit tests.
     */
    private static boolean isProductionDeployment() {
        String activeProfiles = System.getenv("SPRING_PROFILES_ACTIVE");
        return activeProfiles != null
                && Arrays.stream(activeProfiles.split(","))
                        .map(String::trim)
                        .anyMatch(PRODUCTION_PROFILE::equalsIgnoreCase);
    }
}

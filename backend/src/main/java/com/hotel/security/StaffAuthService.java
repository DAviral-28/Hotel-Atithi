package com.hotel.security;

import com.hotel.exception.StaffUnauthorizedException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.Date;
import java.util.HexFormat;

/**
 * Zero-cost front-desk authentication: one shared staff PIN ({@code HOTEL_STAFF_PIN}, default 1439)
 * is exchanged for a signed session token (RFC 7515 JWS, HS256, {@code purpose=staff}) that is valid for
 * 8-12 hours (default 10) and is sent as {@code X-Staff-Token} (or {@code Authorization: Bearer ...}).
 *
 * <ul>
 *   <li>The signing key is derived from the booking signing secret with a domain-separation label, and
 *       every token carries {@code purpose=staff}, so a guest hold / manage token can never act as a staff
 *       token or vice versa.</li>
 *   <li>The token embeds a short fingerprint of the current PIN: changing {@code HOTEL_STAFF_PIN} signs
 *       every existing session out.</li>
 *   <li>The PIN is compared in constant time. Brute-force protection is the per-IP rate limit on
 *       {@code POST /api/staff/login} (see RateLimitFilter); choose a PIN longer than 4 digits for a
 *       deployed hotel.</li>
 * </ul>
 */
@Service
public class StaffAuthService {

    private static final Logger log = LoggerFactory.getLogger(StaffAuthService.class);

    public static final String DEFAULT_PIN = "1439";
    public static final int MIN_TOKEN_HOURS = 8;
    public static final int MAX_TOKEN_HOURS = 12;

    private static final String DEV_SIGNING_KEY = "hotel-booking-jws-hmac-sha256-signing-secret-key-32bytes!";
    private static final String KEY_LABEL = "hotel-staff-session-token/v1";
    private static final String PURPOSE_STAFF = "staff";
    private static final String PRODUCTION_PROFILE = "lambda";

    private final byte[] pinBytes;
    private final byte[] tokenKey;
    private final String pinFingerprint;
    private final long tokenSeconds;
    private final Clock clock;

    public StaffAuthService(
            @Value("${HOTEL_STAFF_PIN:${hotel.staff.pin:}}") String configuredPin,
            @Value("${HOTEL_SIGNING_KEY_256:${hotel.security.jws-key:}}") String configuredSigningKey,
            @Value("${hotel.staff.token-hours:10}") int tokenHours,
            Clock clock
    ) {
        String pin = configuredPin == null ? "" : configuredPin.trim();
        if (pin.isEmpty()) {
            pin = DEFAULT_PIN;
            log.warn("HOTEL_STAFF_PIN is not set: the staff console uses the DEFAULT PIN {}. Set HOTEL_STAFF_PIN "
                    + "(6+ digits recommended) before real guests use the system.", DEFAULT_PIN);
            if (isProductionDeployment()) {
                log.error("SECURITY: running the '{}' profile with the default staff PIN. Anyone who knows it can "
                        + "check guests in/out and block rooms.", PRODUCTION_PROFILE);
            }
        } else if (pin.length() < 4) {
            throw new IllegalStateException("HOTEL_STAFF_PIN must be at least 4 characters.");
        }
        this.pinBytes = pin.getBytes(StandardCharsets.UTF_8);

        String base = configuredSigningKey == null || configuredSigningKey.trim().isEmpty()
                ? DEV_SIGNING_KEY : configuredSigningKey.trim();
        this.tokenKey = hmac(base.getBytes(StandardCharsets.UTF_8), KEY_LABEL.getBytes(StandardCharsets.UTF_8));
        this.pinFingerprint = HexFormat.of().formatHex(hmac(tokenKey, pinBytes)).substring(0, 16);

        int hours = Math.max(MIN_TOKEN_HOURS, Math.min(MAX_TOKEN_HOURS, tokenHours));
        this.tokenSeconds = hours * 3600L;
        this.clock = clock;
    }

    /** Session lifetime in seconds (8-12 hours). */
    public long tokenLifetimeSeconds() {
        return tokenSeconds;
    }

    /** Verifies the PIN and returns a signed staff session token, or throws {@link StaffUnauthorizedException}. */
    public String login(String suppliedPin) {
        String supplied = suppliedPin == null ? "" : suppliedPin.trim();
        // Hash both sides first so the comparison time never depends on the PIN length.
        boolean ok = MessageDigest.isEqual(
                sha256(supplied.getBytes(StandardCharsets.UTF_8)), sha256(pinBytes));
        if (!ok) {
            log.warn("Staff login failed (wrong PIN)");
            throw new StaffUnauthorizedException("Incorrect PIN.");
        }
        return issueToken();
    }

    /** Epoch second at which a token issued now stops being valid. */
    public long expiryFromNowEpochSeconds() {
        return clock.instant().getEpochSecond() + tokenSeconds;
    }

    private String issueToken() {
        try {
            long now = clock.instant().getEpochSecond();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject("staff")
                    .claim("purpose", PURPOSE_STAFF)
                    .claim("pinFp", pinFingerprint)
                    .issueTime(new Date(now * 1000L))
                    .expirationTime(new Date((now + tokenSeconds) * 1000L))
                    .build();
            JWSObject jws = new JWSObject(new JWSHeader(JWSAlgorithm.HS256), new Payload(claims.toJSONObject()));
            jws.sign(new MACSigner(tokenKey));
            return jws.serialize();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new SecurityException("Failed to issue staff session token", e);
        }
    }

    /**
     * Authorises a staff request from its headers. Accepts {@code X-Staff-Token: <token>} or
     * {@code Authorization: Bearer <token>}. Throws {@link StaffUnauthorizedException} (HTTP 401) when
     * absent, forged, expired, of the wrong purpose, or issued under a previous PIN.
     */
    public void requireStaff(String staffTokenHeader, String authorizationHeader) {
        String token = null;
        if (staffTokenHeader != null && !staffTokenHeader.isBlank()) {
            token = staffTokenHeader.trim();
        } else if (authorizationHeader != null && authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = authorizationHeader.substring(7).trim();
        }
        if (token == null || token.isEmpty()) {
            throw new StaffUnauthorizedException("Staff sign-in required.");
        }
        try {
            JWSObject jws = JWSObject.parse(token);
            if (!jws.verify(new MACVerifier(tokenKey))) {
                throw new StaffUnauthorizedException("Invalid staff session. Please sign in again.");
            }
            var claims = jws.getPayload().toJSONObject();
            if (!PURPOSE_STAFF.equals(claims.get("purpose"))) {
                throw new StaffUnauthorizedException("Invalid staff session. Please sign in again.");
            }
            Object exp = claims.get("exp");
            if (exp == null || clock.instant().getEpochSecond() > ((Number) exp).longValue()) {
                throw new StaffUnauthorizedException("Your staff session has expired. Please sign in again.");
            }
            Object fp = claims.get("pinFp");
            if (!(fp instanceof String) || !MessageDigest.isEqual(
                    ((String) fp).getBytes(StandardCharsets.UTF_8), pinFingerprint.getBytes(StandardCharsets.UTF_8))) {
                throw new StaffUnauthorizedException("The staff PIN was changed. Please sign in again.");
            }
        } catch (StaffUnauthorizedException e) {
            throw e;
        } catch (Exception e) {
            throw new StaffUnauthorizedException("Invalid staff session. Please sign in again.");
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    private static boolean isProductionDeployment() {
        String profiles = System.getenv("SPRING_PROFILES_ACTIVE");
        return profiles != null && Arrays.stream(profiles.split(","))
                .map(String::trim).anyMatch(PRODUCTION_PROFILE::equalsIgnoreCase);
    }
}

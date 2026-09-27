package com.hotel.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.DirectDecrypter;
import com.nimbusds.jose.crypto.DirectEncrypter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * RFC 7516 (JWE) encryption service for protecting Guest PII at rest.
 * Uses Direct Key Encryption with AES-256-GCM (dir / A256GCM).
 */
@Service
public class PiiEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(PiiEncryptionService.class);
    private static final String DEFAULT_DEV_KEY = "hotel-32-room-booking-aes-256-key!"; // exactly 32 bytes

    // Matches the SPRING_PROFILES_ACTIVE value the CDK stack sets on the deployed Lambda.
    // Used only to fail fast if a real encryption key was never configured for that deployment.
    private static final String PRODUCTION_PROFILE = "lambda";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    /** Domain-separation label used to derive the blind-index pepper from the master key when none is configured. */
    private static final String PEPPER_DERIVATION_LABEL = "hotel-guest-blind-index-pepper/v1";
    private static final int MIN_PEPPER_LENGTH = 16;

    private final byte[] secretKey;
    /** HMAC key for the GSI1 blind index. Never stored in DynamoDB; without it the index cannot be brute-forced. */
    private final byte[] blindIndexKey;

    /** Convenience for tests / local use: the pepper is derived from the master key. */
    public PiiEncryptionService(String configuredKey) {
        this(configuredKey, "");
    }

    @Autowired
    public PiiEncryptionService(
            @Value("${HOTEL_ENCRYPTION_KEY_256:${hotel.security.jwe-key:}}") String configuredKey,
            @Value("${HOTEL_HMAC_PEPPER:${hotel.security.hmac-pepper:}}") String configuredPepper
    ) {
        if (configuredKey == null || configuredKey.trim().isEmpty()) {
            if (isProductionDeployment()) {
                throw new IllegalStateException(
                        "HOTEL_ENCRYPTION_KEY_256 is not set. Refusing to start the '" + PRODUCTION_PROFILE
                                + "' profile with the hardcoded default encryption key — guest PII would be "
                                + "readable by anyone with the source code or the deployed JAR. Set "
                                + "HOTEL_ENCRYPTION_KEY_256 as a Lambda environment variable (see "
                                + "infrastructure/lib/hotel-infra-stack.ts) before deploying.");
            }
            this.secretKey = DEFAULT_DEV_KEY.getBytes(StandardCharsets.UTF_8);
            log.warn("Initialized PiiEncryptionService using the DEFAULT DEVELOPMENT key. " +
                    "This is only safe for local development/tests — never for a deployed environment.");
        } else {
            byte[] keyBytes = configuredKey.trim().getBytes(StandardCharsets.UTF_8);
            if (keyBytes.length != 32) {
                // If not exactly 32 bytes, SHA-256 digest it to guarantee a 256-bit symmetric key
                try {
                    MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                    this.secretKey = sha256.digest(keyBytes);
                    log.info("Normalized configured JWE key to 256-bit via SHA-256 digest.");
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException("SHA-256 algorithm not available", e);
                }
            } else {
                this.secretKey = keyBytes;
                log.info("Initialized PiiEncryptionService using configured 256-bit key.");
            }
        }

        if (configuredPepper == null || configuredPepper.trim().isEmpty()) {
            this.blindIndexKey = hmac(secretKey, PEPPER_DERIVATION_LABEL.getBytes(StandardCharsets.UTF_8));
            log.info("HOTEL_HMAC_PEPPER not set: blind-index pepper derived from the master encryption key. "
                    + "Set HOTEL_HMAC_PEPPER to decouple it (changing either value re-keys the phone index).");
        } else {
            byte[] pepperBytes = configuredPepper.trim().getBytes(StandardCharsets.UTF_8);
            if (pepperBytes.length < MIN_PEPPER_LENGTH) {
                throw new IllegalStateException("HOTEL_HMAC_PEPPER must be at least " + MIN_PEPPER_LENGTH
                        + " characters (use e.g. `openssl rand -base64 32`).");
            }
            this.blindIndexKey = pepperBytes;
        }
    }

    /**
     * Encrypts plaintext PII into a compact RFC 7516 JWE string.
     * Format: <header>.<encrypted-key>.<iv>.<ciphertext>.<tag>
     */
    public String encryptPii(String rawPii) {
        if (rawPii == null || rawPii.isEmpty()) {
            return rawPii;
        }

        try {
            JWEHeader header = new JWEHeader(JWEAlgorithm.DIR, EncryptionMethod.A256GCM);
            Payload payload = new Payload(rawPii);
            JWEObject jweObject = new JWEObject(header, payload);

            DirectEncrypter encrypter = new DirectEncrypter(secretKey);
            jweObject.encrypt(encrypter);

            return jweObject.serialize();
        } catch (JOSEException e) {
            throw new SecurityException("Failed to encrypt PII via RFC 7516 JWE", e);
        }
    }

    /**
     * Decrypts an RFC 7516 compact JWE string back into plaintext PII.
     * Verifies the AES-GCM authentication tag to detect any tampering.
     */
    public String decryptPii(String jweString) {
        if (jweString == null || jweString.isEmpty()) {
            return jweString;
        }

        // Fast check if string is compact JWE (contains exactly 4 dots = 5 parts)
        if (countDots(jweString) != 4) {
            // Not encrypted (e.g. legacy plain text), return as-is
            return jweString;
        }

        try {
            JWEObject jweObject = JWEObject.parse(jweString);
            DirectDecrypter decrypter = new DirectDecrypter(secretKey);
            jweObject.decrypt(decrypter);

            return jweObject.getPayload().toString();
        } catch (Exception e) {
            throw new SecurityException("Failed to decrypt or authenticate JWE ciphertext", e);
        }
    }

    /**
     * Blind index for DynamoDB GSI1: hex HMAC-SHA256 of the (trimmed, lower-cased) value keyed with the
     * secret pepper. Unlike an unsalted SHA-256, an attacker who reads the table or the index cannot
     * recompute it for the ~4x10^9 valid mobile numbers without the pepper. Callers must pass the
     * CANONICAL phone (see PhoneNormalizer) so every spelling of a number maps to one index value.
     */
    public String blindIndex(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        byte[] mac = hmac(blindIndexKey, value.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac);
    }

    /** Kept for existing callers: identical to {@link #blindIndex(String)} (now HMAC-SHA256, no longer plain SHA-256). */
    public String hashDeterministic(String value) {
        return blindIndex(value);
    }

    /**
     * The PRE-Phase-3 index value (unsalted SHA-256). Only used to find and migrate rows written before
     * the HMAC upgrade; never write new index keys with it.
     */
    public String legacySha256Hash(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 digest algorithm not available", e);
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    private int countDots(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '.') {
                count++;
            }
        }
        return count;
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

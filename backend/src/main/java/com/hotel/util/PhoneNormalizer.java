package com.hotel.util;

import com.hotel.exception.BookingValidationException;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The one place that decides what a guest phone number looks like.
 *
 * <p>Every entry point (hold, access, lookup) and the blind-index writer go through here, so the
 * same person always maps to the same 10-digit value no matter how they typed it:
 * {@code "+91 94523 14397"}, {@code "09452314397"}, {@code "94523-14397"} and {@code "9452314397"}
 * all become {@code "9452314397"}.
 *
 * <p>Canonical form = 10 digits, first digit 6-9 (Indian mobile numbers): {@code ^[6-9]\d{9}$}.
 * Mirrors {@code frontend/src/utils/phoneNormalizer.ts}; keep the two in step.
 */
public final class PhoneNormalizer {

    /** The only shape that is ever stored, hashed or compared. */
    public static final Pattern CANONICAL = Pattern.compile("^[6-9]\\d{9}$");

    /** Formatting a person may type around the digits: whitespace, hyphens, dots and parentheses. */
    private static final Pattern FORMATTING = Pattern.compile("[\\s().\\-]");

    /** After formatting is removed: digits with at most one leading '+'. Anything else (letters, markup, '+' elsewhere) is invalid. */
    private static final Pattern DIGITS_WITH_OPTIONAL_PLUS = Pattern.compile("^\\+?\\d+$");

    private static final Pattern LAST4 = Pattern.compile("^\\d{4}$");

    public static final String INVALID_MESSAGE =
            "Enter a valid 10-digit Indian mobile number (starting with 6, 7, 8 or 9).";

    private PhoneNormalizer() {
    }

    /** Canonical 10-digit number, or empty when the input is not a valid Indian mobile number. */
    public static Optional<String> tryNormalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String compact = FORMATTING.matcher(raw.trim()).replaceAll("");
        if (!DIGITS_WITH_OPTIONAL_PLUS.matcher(compact).matches()) {
            return Optional.empty();
        }
        boolean hasPlus = compact.startsWith("+");
        String digits = hasPlus ? compact.substring(1) : compact;

        if (hasPlus) {
            // "+" is only meaningful as the +91 country code.
            if (digits.length() != 12 || !digits.startsWith("91")) {
                return Optional.empty();
            }
            digits = digits.substring(2);
        } else {
            // Trunk prefix(es): "09452314397", "0091...". A real mobile number never starts with 0.
            digits = stripLeadingZeros(digits);
            if (digits.length() == 12 && digits.startsWith("91")) {
                digits = digits.substring(2);
            }
        }
        return CANONICAL.matcher(digits).matches() ? Optional.of(digits) : Optional.empty();
    }

    /** Canonical number or a 400 ({@link BookingValidationException}) - call this before touching DynamoDB or hashing. */
    public static String require(String raw) {
        return tryNormalize(raw).orElseThrow(() -> new BookingValidationException(INVALID_MESSAGE));
    }

    /** Canonical form when the input is a valid number, otherwise the input trimmed (used by DTO constructors so @Pattern can report it). */
    public static String canonicalOrTrimmed(String raw) {
        if (raw == null) {
            return null;
        }
        return tryNormalize(raw).orElse(raw.trim());
    }

    public static boolean isCanonical(String value) {
        return value != null && CANONICAL.matcher(value).matches();
    }

    /** True when the input is exactly four digits (the "last 4 digits of your phone" proof). */
    public static boolean isLast4(String raw) {
        return raw != null && LAST4.matcher(raw.trim()).matches();
    }

    public static String last4(String canonical) {
        return canonical == null || canonical.length() < 4 ? "" : canonical.substring(canonical.length() - 4);
    }

    /**
     * Best-effort canonical form of a phone number ALREADY STORED (possibly written before
     * normalisation existed, e.g. "+919800000101" or a test value): the valid canonical form when
     * there is one, otherwise the last ten digits. Only for comparing / indexing stored data - never
     * for accepting new input.
     */
    public static String forStored(String stored) {
        if (stored == null) {
            return "";
        }
        Optional<String> strict = tryNormalize(stored);
        if (strict.isPresent()) {
            return strict.get();
        }
        String digits = stored.replaceAll("\\D", "");
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }

    /**
     * Spellings the pre-Phase-3 code could have SHA-256-hashed for GSI1PK (it hashed the phone exactly as
     * typed). Used only to find and migrate legacy rows.
     */
    public static java.util.List<String> legacySpellings(String canonical) {
        return java.util.List.of(canonical, "+91" + canonical, "91" + canonical, "0" + canonical);
    }

    /** "******3210" - safe to show without authentication. */
    public static String mask(String phone) {
        String canonical = forStored(phone);
        if (canonical.length() < 4) {
            return "**********";
        }
        return "*".repeat(canonical.length() - 4) + canonical.substring(canonical.length() - 4);
    }

    private static String stripLeadingZeros(String digits) {
        int i = 0;
        while (i < digits.length() && digits.charAt(i) == '0') {
            i++;
        }
        return digits.substring(i);
    }
}

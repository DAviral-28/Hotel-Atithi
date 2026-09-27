package com.hotel.util;

import java.util.regex.Pattern;

/**
 * Neutralises markup in free-text guest input and masks personal data for unauthenticated responses.
 * Guest text is only ever rendered as text by the SPA, but stored values also end up in staff tools,
 * e-mails and PDFs, so tags are removed at the door.
 */
public final class InputSanitizer {

    private static final Pattern SCRIPT_BLOCK =
            Pattern.compile("<\\s*(script|style)[^>]*>.*?<\\s*/\\s*\\1\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]*(?:>|$)");   // also an unterminated "<img src=x onerror=..."
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private InputSanitizer() {
    }

    /** Removes script/style blocks, HTML tags, stray angle brackets and control characters; collapses whitespace; trims. */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String s = SCRIPT_BLOCK.matcher(raw).replaceAll(" ");
        s = ANY_TAG.matcher(s).replaceAll(" ");
        s = s.replace("<", "").replace(">", "");
        s = CONTROL.matcher(s).replaceAll(" ");
        return WHITESPACE.matcher(s).replaceAll(" ").trim();
    }

    /** "Ravi Kumar" -> "R**** K*****" style: first letter of each word, then asterisks (at most four). */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return "Guest";
        }
        StringBuilder out = new StringBuilder();
        for (String word : name.trim().split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            int first = word.offsetByCodePoints(0, 1);
            out.append(word, 0, first).append("*".repeat(Math.min(4, word.codePointCount(0, word.length()) - 1)));
        }
        return out.toString();
    }

    /** "ravi@example.com" -> "r***@example.com". */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.indexOf('@');
        if (at < 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}

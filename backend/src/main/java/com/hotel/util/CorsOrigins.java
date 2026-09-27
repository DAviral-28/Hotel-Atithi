package com.hotel.util;

import java.util.Arrays;
import java.util.List;

/** Parses the CORS allow-list (HOTEL_ALLOWED_ORIGINS): exact origins only. */
public final class CorsOrigins {

    /** Local Vite dev server (5173 is Vite's default, 3000 is what vite.config.ts sets); the deployed CloudFront origin is added through HOTEL_ALLOWED_ORIGINS. */
    public static final String DEFAULT_ALLOWED_ORIGINS = "http://localhost:5173,http://localhost:3000,http://127.0.0.1:5173,http://127.0.0.1:3000";

    private CorsOrigins() {
    }

    /** Comma-separated exact origins. A wildcard is refused: it would defeat the allow-list. */
    public static List<String> parse(String csv) {
        List<String> origins = Arrays.stream((csv == null ? "" : csv).split(","))
                .map(String::trim)
                .map(o -> o.endsWith("/") ? o.substring(0, o.length() - 1) : o)
                .filter(o -> !o.isEmpty())
                .toList();
        if (origins.contains("*")) {
            throw new IllegalStateException("HOTEL_ALLOWED_ORIGINS must list exact origins; '*' is not allowed.");
        }
        return origins.isEmpty() ? parse(DEFAULT_ALLOWED_ORIGINS) : origins;
    }
}

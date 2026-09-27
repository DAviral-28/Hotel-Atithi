package com.hotel.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds baseline security headers to EVERY API response, including error responses and 429s produced
 * by other filters (this filter runs first). Headers are set before the chain runs so they survive
 * however the response is completed.
 */
public class SecurityHeadersFilter extends OncePerRequestFilter {

    public static final String STRICT_TRANSPORT_SECURITY = "max-age=31536000; includeSubDomains";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Strict-Transport-Security", STRICT_TRANSPORT_SECURITY);
        response.setHeader("Referrer-Policy", "no-referrer");
        // Booking data is personal: never let a browser or proxy cache it.
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}

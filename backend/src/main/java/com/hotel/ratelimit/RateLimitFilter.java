package com.hotel.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Abuse protection for the endpoints that hand out locks or probe guest data:
 * <ul>
 *   <li>{@code POST /api/bookings/hold}: holdMax per IP per holdWindow (default 3 / 10 min) - stops
 *       one client locking the whole hotel;</li>
 *   <li>{@code GET|POST /api/bookings/lookup} and {@code POST /api/bookings/{id}/access}: lookupMax
 *       per IP per lookupWindow, shared (default 5 / 1 min) - stops phone / booking-ID guessing.</li>
 * </ul>
 * Over the limit: HTTP 429 with a {@code Retry-After} header and a plain-language JSON message.
 * The client address is {@code request.getRemoteAddr()}, which behind API Gateway is the caller's
 * source IP as API Gateway saw it. {@code X-Forwarded-For} is deliberately NOT used: a client can
 * put anything in it and would then dodge the limit.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final Pattern ACCESS_PATH = Pattern.compile(".*/api/bookings/[^/]+/access$");

    private final SlidingWindowRateLimiter holdLimiter;
    private final SlidingWindowRateLimiter lookupLimiter;
    private final long holdWindowSeconds;
    private final long lookupWindowSeconds;
    /** Phase 4: guards {@code POST /api/staff/login} against PIN guessing. Null = no staff-login limiting. */
    private final SlidingWindowRateLimiter staffLoginLimiter;

    public RateLimitFilter(SlidingWindowRateLimiter holdLimiter, long holdWindowSeconds,
                           SlidingWindowRateLimiter lookupLimiter, long lookupWindowSeconds) {
        this(holdLimiter, holdWindowSeconds, lookupLimiter, lookupWindowSeconds, null);
    }

    public RateLimitFilter(SlidingWindowRateLimiter holdLimiter, long holdWindowSeconds,
                           SlidingWindowRateLimiter lookupLimiter, long lookupWindowSeconds,
                           SlidingWindowRateLimiter staffLoginLimiter) {
        this.holdLimiter = holdLimiter;
        this.holdWindowSeconds = holdWindowSeconds;
        this.lookupLimiter = lookupLimiter;
        this.lookupWindowSeconds = lookupWindowSeconds;
        this.staffLoginLimiter = staffLoginLimiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = trimTrailingSlash(request.getRequestURI());
        String method = request.getMethod();

        SlidingWindowRateLimiter limiter = null;
        String what = null;
        if ("POST".equals(method) && path.endsWith("/api/bookings/hold")) {
            limiter = holdLimiter;
            what = "room holds";
        } else if (("GET".equals(method) || "POST".equals(method)) && path.endsWith("/api/bookings/lookup")
                || "POST".equals(method) && ACCESS_PATH.matcher(path).matches()) {
            limiter = lookupLimiter;
            what = "booking look-ups";
        } else if (staffLoginLimiter != null && "POST".equals(method) && path.endsWith("/api/staff/login")) {
            limiter = staffLoginLimiter;
            what = "staff sign-in attempts";
        }

        if (limiter != null) {
            String ip = clientIp(request);
            SlidingWindowRateLimiter.Decision decision = limiter.tryAcquire(ip);
            if (!decision.allowed()) {
                log.warn("Rate limit hit for {} on {} {}", ip, method, path);
                reject(response, what, decision.retryAfterSeconds());
                return;
            }
        }
        chain.doFilter(request, response);
    }

    static String clientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null || ip.isBlank() ? "unknown" : ip;
    }

    private static String trimTrailingSlash(String uri) {
        if (uri == null) {
            return "";
        }
        return uri.length() > 1 && uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }

    private void reject(HttpServletResponse response, String what, long retryAfterSeconds) throws IOException {
        String message = "Too many " + what + " from your connection. Please wait " + retryAfterSeconds
                + (retryAfterSeconds == 1 ? " second" : " seconds") + " and try again.";
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"TOO_MANY_REQUESTS\",\"message\":\"" + message
                + "\",\"retryAfterSeconds\":" + retryAfterSeconds
                + ",\"timestamp\":\"" + Instant.now() + "\"}");
    }
}

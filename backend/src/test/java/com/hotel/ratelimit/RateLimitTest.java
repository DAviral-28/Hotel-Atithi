package com.hotel.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 (B4/B7): the sliding-window limiter, the servlet filter that applies it (429 + Retry-After)
 * and the security-headers filter. The servlet objects are java.lang.reflect.Proxy fakes, so no
 * mocking library or servlet container is needed.
 */
class RateLimitTest {

    private static final long MINUTE = 60_000L;
    private static final long TEN_MINUTES = 600_000L;

    // ------------------------------------------------------------ limiter

    @Test
    @DisplayName("hold limit: 3 per IP per 10 minutes, then denied with the time left; the window slides")
    void holdLimitIsThreePerTenMinutes() {
        AtomicLong now = new AtomicLong(1_000_000L);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(3, TEN_MINUTES, now::get);

        assertTrue(limiter.tryAcquire("1.2.3.4").allowed());
        now.addAndGet(1_000);
        assertTrue(limiter.tryAcquire("1.2.3.4").allowed());
        now.addAndGet(1_000);
        assertTrue(limiter.tryAcquire("1.2.3.4").allowed());
        now.addAndGet(1_000);

        SlidingWindowRateLimiter.Decision denied = limiter.tryAcquire("1.2.3.4");
        assertFalse(denied.allowed());
        assertEquals(597, denied.retryAfterSeconds(), "oldest hit was 3s ago, so 597s of the 600s window remain");

        now.addAndGet(300_000);
        assertFalse(limiter.tryAcquire("1.2.3.4").allowed(), "still inside the window after 5 minutes");

        now.addAndGet(297_000);   // 600s after the first hit
        assertTrue(limiter.tryAcquire("1.2.3.4").allowed(), "the first hit has left the window");
        assertFalse(limiter.tryAcquire("1.2.3.4").allowed(), "but only one slot freed up");
    }

    @Test
    @DisplayName("lookup limit: 5 per IP per minute; a denied call does not extend the penalty")
    void lookupLimitIsFivePerMinute() {
        AtomicLong now = new AtomicLong(0L);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(5, MINUTE, now::get);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("ip").allowed(), "attempt " + (i + 1));
        }
        SlidingWindowRateLimiter.Decision d = limiter.tryAcquire("ip");
        assertFalse(d.allowed());
        assertEquals(60, d.retryAfterSeconds());

        now.set(30_000);
        assertEquals(30, limiter.tryAcquire("ip").retryAfterSeconds(), "hammering while blocked does not reset the clock");
        now.set(60_000);
        assertTrue(limiter.tryAcquire("ip").allowed());
    }

    @Test
    @DisplayName("limits are per client IP")
    void limitsArePerIp() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(1, MINUTE, () -> 0L);
        assertTrue(limiter.tryAcquire("10.0.0.1").allowed());
        assertFalse(limiter.tryAcquire("10.0.0.1").allowed());
        assertTrue(limiter.tryAcquire("10.0.0.2").allowed());
    }

    @Test
    @DisplayName("idle clients are evicted, so memory does not grow without bound")
    void idleKeysAreEvicted() {
        AtomicLong now = new AtomicLong(0L);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(3, MINUTE, now::get);
        for (int i = 0; i < 500; i++) {
            limiter.tryAcquire("ip-" + i);
        }
        assertEquals(500, limiter.trackedKeys());
        now.set(2 * MINUTE);
        limiter.tryAcquire("fresh");
        assertEquals(1, limiter.trackedKeys(), "all 500 stale clients were swept; only the new one remains");
    }

    @Test
    @DisplayName("concurrent requests from one IP cannot exceed the limit")
    void limitHoldsUnderConcurrency() throws Exception {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(5, MINUTE, () -> 0L);
        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (limiter.tryAcquire("same-ip").allowed()) {
                        allowed.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdown();
        assertEquals(5, allowed.get());
    }

    @Test
    @DisplayName("invalid limiter settings are rejected")
    void invalidSettings() {
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowRateLimiter(0, MINUTE, () -> 0L));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowRateLimiter(1, 0, () -> 0L));
    }

    // ------------------------------------------------------------ filter

    /** What a fake response recorded. */
    private static final class Recorded {
        int status = 200;
        final Map<String, String> headers = new HashMap<>();
        final StringWriter body = new StringWriter();
        boolean chained;
    }

    private static HttpServletRequest request(String method, String uri, String ip) {
        return (HttpServletRequest) Proxy.newProxyInstance(RateLimitTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, m, args) -> switch (m.getName()) {
                    case "getMethod" -> method;
                    case "getRequestURI" -> uri;
                    case "getRemoteAddr" -> ip;
                    default -> defaultValue(m.getReturnType());
                });
    }

    private static HttpServletResponse response(Recorded rec) {
        return (HttpServletResponse) Proxy.newProxyInstance(RateLimitTest.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class}, (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "setStatus" -> rec.status = (Integer) args[0];
                        case "setHeader", "addHeader" -> rec.headers.put((String) args[0], (String) args[1]);
                        case "setContentType" -> rec.headers.put("Content-Type", (String) args[0]);
                        case "getWriter" -> {
                            return new PrintWriter(rec.body, true);
                        }
                        default -> {
                        }
                    }
                    return defaultValue(m.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    private static RateLimitFilter newFilter(AtomicLong now) {
        return new RateLimitFilter(
                new SlidingWindowRateLimiter(3, TEN_MINUTES, now::get), 600,
                new SlidingWindowRateLimiter(5, MINUTE, now::get), 60);
    }

    private static Recorded call(RateLimitFilter f, String method, String uri, String ip) throws Exception {
        Recorded rec = new Recorded();
        FilterChain chain = (req, res) -> rec.chained = true;
        f.doFilterInternal(request(method, uri, ip), response(rec), chain);
        return rec;
    }

    @Test
    @DisplayName("filter (Phase 4): the 6th staff PIN attempt from one IP in 5 minutes gets 429; other IPs and other staff paths are unaffected")
    void staffLoginIsRateLimited() throws Exception {
        AtomicLong now = new AtomicLong(0L);
        RateLimitFilter f = new RateLimitFilter(
                new SlidingWindowRateLimiter(3, TEN_MINUTES, now::get), 600,
                new SlidingWindowRateLimiter(5, MINUTE, now::get), 60,
                new SlidingWindowRateLimiter(5, 300_000L, now::get));
        for (int i = 0; i < 5; i++) {
            assertTrue(call(f, "POST", "/prod/api/staff/login", "7.7.7.7").chained);
        }
        Recorded blocked = call(f, "POST", "/prod/api/staff/login", "7.7.7.7");
        assertFalse(blocked.chained);
        assertEquals(429, blocked.status);
        assertEquals("300", blocked.headers.get("Retry-After"));
        assertTrue(blocked.body.toString().contains("staff sign-in attempts"), blocked.body.toString());

        assertTrue(call(f, "POST", "/api/staff/login", "6.6.6.6").chained, "another IP is unaffected");
        assertTrue(call(f, "GET", "/api/staff/dashboard", "7.7.7.7").chained, "only the login path is limited");
        assertTrue(call(newFilter(now), "POST", "/api/staff/login", "7.7.7.7").chained,
                "a filter built without a staff limiter (pre-Phase-4 constructor) does not limit it");
        now.set(300_001L);
        assertTrue(call(f, "POST", "/api/staff/login", "7.7.7.7").chained, "the window slides");
    }

    @Test
    @DisplayName("filter: the 4th hold from one IP in 10 minutes gets 429 + Retry-After and never reaches the controller")
    void fourthHoldIsRejected() throws Exception {
        AtomicLong now = new AtomicLong(0L);
        RateLimitFilter f = newFilter(now);
        for (int i = 0; i < 3; i++) {
            Recorded ok = call(f, "POST", "/api/bookings/hold", "9.9.9.9");
            assertTrue(ok.chained);
            assertEquals(200, ok.status);
        }
        Recorded blocked = call(f, "POST", "/api/bookings/hold", "9.9.9.9");
        assertFalse(blocked.chained, "request must not reach the controller");
        assertEquals(429, blocked.status);
        assertEquals("600", blocked.headers.get("Retry-After"));
        assertEquals("application/json", blocked.headers.get("Content-Type"));
        String body = blocked.body.toString();
        assertTrue(body.contains("TOO_MANY_REQUESTS"), body);
        assertTrue(body.contains("room holds"), body);
        assertTrue(body.contains("600 seconds"), body);
        assertTrue(body.contains("\"retryAfterSeconds\":600"), body);

        assertTrue(call(f, "POST", "/api/bookings/hold", "8.8.8.8").chained, "another client is unaffected");
    }

    @Test
    @DisplayName("filter: lookup and access share one bucket of 5 per minute per IP (GET/POST lookup, POST /{id}/access)")
    void lookupAndAccessShareABucket() throws Exception {
        AtomicLong now = new AtomicLong(0L);
        RateLimitFilter f = newFilter(now);
        assertTrue(call(f, "GET", "/api/bookings/lookup", "1.1.1.1").chained);
        assertTrue(call(f, "POST", "/api/bookings/lookup", "1.1.1.1").chained);
        assertTrue(call(f, "POST", "/api/bookings/3a7b-uuid/access", "1.1.1.1").chained);
        assertTrue(call(f, "POST", "/api/bookings/3a7b-uuid/access", "1.1.1.1").chained);
        assertTrue(call(f, "GET", "/api/bookings/lookup/", "1.1.1.1").chained, "trailing slash");

        Recorded blocked = call(f, "POST", "/api/bookings/other-id/access", "1.1.1.1");
        assertEquals(429, blocked.status);
        assertEquals("60", blocked.headers.get("Retry-After"));
        assertTrue(blocked.body.toString().contains("booking look-ups"));
        assertFalse(blocked.chained);

        now.set(MINUTE);
        assertTrue(call(f, "GET", "/api/bookings/lookup", "1.1.1.1").chained, "allowed again after the minute");
    }

    @Test
    @DisplayName("filter: the API Gateway stage prefix does not bypass the limit")
    void stagePrefixIsStillLimited() throws Exception {
        RateLimitFilter f = newFilter(new AtomicLong(0L));
        for (int i = 0; i < 3; i++) {
            assertTrue(call(f, "POST", "/prod/api/bookings/hold", "2.2.2.2").chained);
        }
        assertEquals(429, call(f, "POST", "/prod/api/bookings/hold", "2.2.2.2").status);
    }

    @Test
    @DisplayName("filter: other endpoints, preflights and other methods are never limited")
    void otherTrafficIsNotLimited() throws Exception {
        RateLimitFilter f = newFilter(new AtomicLong(0L));
        for (int i = 0; i < 20; i++) {
            assertTrue(call(f, "GET", "/api/rooms", "3.3.3.3").chained);
            assertTrue(call(f, "GET", "/api/bookings/availability", "3.3.3.3").chained);
            assertTrue(call(f, "OPTIONS", "/api/bookings/hold", "3.3.3.3").chained);
            assertTrue(call(f, "OPTIONS", "/api/bookings/lookup", "3.3.3.3").chained);
            assertTrue(call(f, "GET", "/api/bookings/abc/hold", "3.3.3.3").chained);
            assertTrue(call(f, "POST", "/api/bookings/abc/confirm", "3.3.3.3").chained);
        }
    }

    @Test
    @DisplayName("filter: a missing client address is bucketed as 'unknown' rather than skipping the limit")
    void unknownAddressIsStillLimited() throws Exception {
        RateLimitFilter f = newFilter(new AtomicLong(0L));
        for (int i = 0; i < 3; i++) {
            assertTrue(call(f, "POST", "/api/bookings/hold", null).chained);
        }
        assertEquals(429, call(f, "POST", "/api/bookings/hold", null).status);
    }

    // ------------------------------------------------------------ security headers

    @Test
    @DisplayName("security headers: nosniff, DENY framing and HSTS are on every response")
    void securityHeadersAreSet() throws Exception {
        Recorded rec = new Recorded();
        FilterChain chain = (req, res) -> rec.chained = true;
        new SecurityHeadersFilter().doFilterInternal(request("GET", "/api/rooms", "1.1.1.1"), response(rec), chain);

        assertTrue(rec.chained);
        assertEquals("nosniff", rec.headers.get("X-Content-Type-Options"));
        assertEquals("DENY", rec.headers.get("X-Frame-Options"));
        assertEquals("max-age=31536000; includeSubDomains", rec.headers.get("Strict-Transport-Security"));
        assertEquals("no-referrer", rec.headers.get("Referrer-Policy"));
        assertEquals("no-store", rec.headers.get("Cache-Control"));
    }
}

package com.hotel.ratelimit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Thread-safe in-memory sliding-window limiter: at most {@code maxRequests} per key inside any
 * {@code window}. Costs nothing (no Redis / WAF add-on). State lives in the JVM, so on AWS Lambda each
 * warm instance keeps its own counters - a deliberate zero-cost trade-off that still stops a single
 * client hammering one instance; API Gateway's stage throttling is the global backstop.
 */
public final class SlidingWindowRateLimiter {

    /** Above this many tracked keys a sweep is forced, bounding memory under a many-IP flood. */
    static final int MAX_TRACKED_KEYS = 10_000;

    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private final int maxRequests;
    private final long windowMillis;
    private final LongSupplier nowMillis;
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final AtomicLong lastSweep;

    public SlidingWindowRateLimiter(int maxRequests, long windowMillis, LongSupplier nowMillis) {
        if (maxRequests < 1 || windowMillis < 1) {
            throw new IllegalArgumentException("maxRequests and window must be positive");
        }
        this.maxRequests = maxRequests;
        this.windowMillis = windowMillis;
        this.nowMillis = nowMillis;
        this.lastSweep = new AtomicLong(nowMillis.getAsLong());
    }

    /** Records the request when allowed; otherwise says how many seconds until the oldest hit leaves the window. */
    public Decision tryAcquire(String key) {
        long now = nowMillis.getAsLong();
        sweepIfDue(now);
        Decision[] out = new Decision[1];
        // compute() is atomic per key, so concurrent requests from one client cannot slip past the limit.
        hits.compute(key, (k, q) -> {
            Deque<Long> window = q == null ? new ArrayDeque<>() : q;
            prune(window, now);
            if (window.size() >= maxRequests) {
                long waitMillis = window.peekFirst() + windowMillis - now;
                out[0] = new Decision(false, Math.max(1L, (waitMillis + 999) / 1000));
            } else {
                window.addLast(now);
                out[0] = new Decision(true, 0L);
            }
            return window;
        });
        return out[0];
    }

    /** Number of keys currently tracked (for tests / monitoring). */
    public int trackedKeys() {
        return hits.size();
    }

    /** Drops keys whose hits have all left the window. Runs at most once per window, or when the map grows too large. */
    private void sweepIfDue(long now) {
        long last = lastSweep.get();
        boolean due = now - last >= windowMillis || hits.size() > MAX_TRACKED_KEYS;
        if (due && lastSweep.compareAndSet(last, now)) {
            for (String key : hits.keySet()) {
                hits.computeIfPresent(key, (k, q) -> {
                    prune(q, now);
                    return q.isEmpty() ? null : q;
                });
            }
        }
    }

    private void prune(Deque<Long> window, long now) {
        long cutoff = now - windowMillis;
        while (!window.isEmpty() && window.peekFirst() <= cutoff) {
            window.pollFirst();
        }
    }
}

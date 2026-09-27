package com.hotel.model;

import com.hotel.config.AppConfig;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Cancellation rules, all in Indian Standard Time:
 * <ul>
 *   <li>Check-in time is 12:00 PM on the check-in date.</li>
 *   <li>Free cancellation until 24 hours before that moment.</li>
 *   <li>After that (but before check-in time) cancelling is still allowed online but is flagged as a
 *       LATE cancellation so the front desk can apply the hotel's late-cancellation terms.</li>
 *   <li>Once check-in time has been reached the stay can no longer be cancelled online.</li>
 * </ul>
 */
public final class CancellationPolicy {

    public static final int FREE_CANCELLATION_HOURS = 24;
    public static final LocalTime CHECK_IN_TIME = LocalTime.NOON;

    private CancellationPolicy() {
    }

    public static Instant checkInInstant(LocalDate checkInDate) {
        return checkInDate.atTime(CHECK_IN_TIME).atZone(AppConfig.HOTEL_ZONE).toInstant();
    }

    public static Instant freeCancellationDeadline(LocalDate checkInDate) {
        return checkInInstant(checkInDate).minusSeconds(FREE_CANCELLATION_HOURS * 3600L);
    }

    /** True once check-in time (12:00 PM IST on the check-in date) has been reached. */
    public static boolean stayStarted(LocalDate checkInDate, long nowEpochSeconds) {
        return nowEpochSeconds >= checkInInstant(checkInDate).getEpochSecond();
    }

    /** True if cancelling now is after the free-cancellation deadline. */
    public static boolean isLate(LocalDate checkInDate, long nowEpochSeconds) {
        return nowEpochSeconds > freeCancellationDeadline(checkInDate).getEpochSecond();
    }
}

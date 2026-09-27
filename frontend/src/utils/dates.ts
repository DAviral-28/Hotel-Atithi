/**
 * Calendar-date helpers for a hotel that runs on Indian Standard Time.
 *
 * `new Date().toISOString().split('T')[0]` is the UTC date, which is still "yesterday" for guests in
 * India between 00:00 and 05:30 IST. Every "today" in the UI must come from here instead. Dates are
 * handled as plain `YYYY-MM-DD` strings (what the API and <input type="date"> use), and arithmetic on
 * them is done in UTC on the bare calendar day so the local machine's time zone / DST can't shift it.
 */

export const HOTEL_TIME_ZONE = 'Asia/Kolkata';

/** Keep in sync with BookingService.MAX_STAY_NIGHTS / MAX_ADVANCE_DAYS on the backend. */
export const MAX_STAY_NIGHTS = 30;
export const MAX_ADVANCE_DAYS = 365;

/** Today's calendar date in India, as YYYY-MM-DD. */
export function todayIST(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: HOTEL_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now);
  const part = (type: string) => parts.find((p) => p.type === type)?.value ?? '';
  return `${part('year')}-${part('month')}-${part('day')}`;
}

function dayNumber(date: string): number {
  const [y, m, d] = date.split('-').map(Number);
  return Date.UTC(y, m - 1, d) / 86_400_000;
}

/** Adds (or subtracts) whole calendar days to a YYYY-MM-DD date. */
export function addDays(date: string, days: number): string {
  const [y, m, d] = date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

/** Number of nights between two YYYY-MM-DD dates (can be <= 0 if the range is invalid). */
export function nightsBetween(checkIn: string, checkOut: string): number {
  return Math.round(dayNumber(checkOut) - dayNumber(checkIn));
}

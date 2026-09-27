import {
  BookingResponse,
  BookingSummary,
  ConfirmBookingRequest,
  ExtendBookingRequest,
  HoldBookingRequest,
  PaymentConfig,
  Room,
  RoomAvailabilityResponse,
  RoomType,
  VerifiedBookingSummary,
} from '../types';
import { INITIAL_ROOMS } from '../data/roomDirectory';
import { PHONE_ERROR_MESSAGE, isLast4, normalizePhone } from '../utils/phoneNormalizer';

// Default to empty string for relative path (proxied by Vite in dev to :8080), or use VITE_API_BASE_URL if set
const API_BASE = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/+$/, '');

/**
 * Every failure of a booking-related call is an ApiError. There is NO offline simulation: if the
 * server can't be reached or refuses, the UI must say so and let the guest retry - a booking that
 * only exists in the browser would be a lie (the room would still be free for someone else).
 */
export class ApiError extends Error {
  /** HTTP status, or 0 when the server could not be reached at all. */
  readonly status: number;
  /** True when trying the same request again later may succeed (network down, throttled, 5xx). */
  readonly retryable: boolean;
  /** For HTTP 429: seconds the server asked us to wait (Retry-After), when it said. */
  readonly retryAfterSeconds?: number;

  constructor(message: string, status: number, retryable: boolean, retryAfterSeconds?: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.retryable = retryable;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

/** Whether an error thrown by this module is worth offering a "Try again" button for. */
export function isRetryable(err: unknown): boolean {
  return err instanceof ApiError ? err.retryable : false;
}

const NETWORK_MESSAGE =
  "We couldn't reach the booking server. Please check your internet connection and try again.";
const BUSY_MESSAGE = "We're receiving a lot of requests right now. Please try again in a few seconds.";

function tooManyRequestsMessage(retryAfterSeconds?: number): string {
  return retryAfterSeconds
    ? `Too many attempts from your connection. Please wait ${retryAfterSeconds} second${retryAfterSeconds === 1 ? '' : 's'} and try again.`
    : 'Too many attempts from your connection. Please wait a moment and try again.';
}

/** A local validation failure: raised before any request is made, so nothing reaches the server. */
function invalidInput(message: string): ApiError {
  return new ApiError(message, 400, false);
}

/**
 * Sends a request and returns the parsed JSON body. Network failures, throttling (429/503) and 5xx
 * become retryable ApiErrors; 4xx responses surface the server's own message.
 */
export async function request<T>(
  path: string,
  init: RequestInit = {},
  onHttpError?: (status: number, body: any) => string | undefined
): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${API_BASE}${path}`, init);
  } catch (err) {
    console.warn(`Request to ${path} could not reach the server.`, err);
    throw new ApiError(NETWORK_MESSAGE, 0, true);
  }

  const body = await res.json().catch(() => ({}));
  if (res.ok) {
    return body as T;
  }

  const custom = onHttpError?.(res.status, body);
  if (res.status === 429) {
    // Our own limiter sends TOO_MANY_REQUESTS with a plain-language message; API Gateway's throttle
    // only says "Too Many Requests", so build a friendlier one from Retry-After in that case.
    const header = Number(res.headers.get('Retry-After'));
    const retryAfter = Number.isFinite(header) && header > 0 ? header : body?.retryAfterSeconds;
    const message = body?.error === 'TOO_MANY_REQUESTS' && body.message ? body.message : tooManyRequestsMessage(retryAfter);
    throw new ApiError(custom || message, 429, true, retryAfter);
  }
  if (res.status === 503) {
    throw new ApiError(custom || body.message || BUSY_MESSAGE, res.status, true);
  }
  if (res.status >= 500) {
    throw new ApiError(
      custom || body.message || `The booking server had a problem (error ${res.status}). Please try again.`,
      res.status,
      true
    );
  }
  throw new ApiError(custom || body.message || `Request failed (server returned ${res.status}).`, res.status, false);
}

const jsonHeaders = (token?: string | null): Record<string, string> => {
  const h: Record<string, string> = { 'Content-Type': 'application/json' };
  if (token) h['X-Booking-Token'] = token;
  return h;
};

/**
 * The room catalogue (rooms, tariffs, occupancy) is static hotel information, so showing the built-in
 * copy when the server is unreachable is safe: it contains no bookings and no availability. Whether a
 * room is actually free is only ever taken from fetchAvailability.
 */
export async function fetchRooms(floor?: number, type?: RoomType): Promise<Room[]> {
  const params = new URLSearchParams();
  if (floor) params.append('floor', floor.toString());
  if (type) params.append('type', type);

  try {
    const res = await fetch(`${API_BASE}/api/rooms${params.toString() ? `?${params.toString()}` : ''}`);
    if (!res.ok) {
      throw new Error(`Server returned ${res.status}`);
    }
    const data = await res.json();
    if (Array.isArray(data) && data.length > 0) {
      // Gallery photos and the card cover image are frontend-only, static asset mappings (never sent by
      // the backend), so backfill each server-returned room from the local catalogue by room number.
      return data.map((r: Room) => {
        const local = INITIAL_ROOMS.find((room) => room.roomNumber === r.roomNumber);
        return {
          ...r,
          images: r.images && r.images.length > 0 ? r.images : local?.images ?? [],
          coverImage: r.coverImage || local?.coverImage || local?.images?.[0] || '',
        };
      });
    }
  } catch (err) {
    console.info('Room catalogue endpoint unavailable; showing the built-in room list.', err);
  }

  return INITIAL_ROOMS.filter((r) => {
    if (floor && r.floor !== floor) return false;
    if (type && r.type !== type) return false;
    return true;
  });
}

/**
 * Live availability from the booking server. Deliberately NEVER falls back to "everything is
 * available": on any failure the caller shows an error/retry state.
 */
export async function fetchAvailability(
  checkInDate: string,
  checkOutDate: string,
  roomType?: RoomType
): Promise<RoomAvailabilityResponse> {
  const params = new URLSearchParams({ checkInDate, checkOutDate });
  if (roomType) params.append('roomType', roomType);

  const data = await request<RoomAvailabilityResponse>(`/api/bookings/availability?${params.toString()}`);
  if (!data || !Array.isArray(data.availableRooms)) {
    throw new ApiError('The booking server sent an unexpected availability response. Please try again.', 502, true);
  }
  return data;
}

/** Public UPI settings (VPA + payee name) used to build the payment QR. */
export async function fetchPaymentConfig(): Promise<PaymentConfig> {
  return request<PaymentConfig>('/api/payments/config');
}

export async function holdRoom(payload: HoldBookingRequest): Promise<BookingResponse> {
  // Canonicalise and reject a bad phone here so nothing is sent (the server checks again).
  const guestPhone = normalizePhone(payload.guestPhone);
  if (guestPhone === null) throw invalidInput(PHONE_ERROR_MESSAGE);
  const outgoing: HoldBookingRequest = { ...payload, guestPhone };
  return request<BookingResponse>(
    '/api/bookings/hold',
    { method: 'POST', headers: jsonHeaders(), body: JSON.stringify(outgoing) },
    (status, body) => {
      if (status === 409) {
        const dates = body.conflictingDates ? ` (${body.conflictingDates.join(', ')})` : '';
        return `Room ${payload.roomNumber} is unavailable for the requested dates${dates}. Please select another room or dates.`;
      }
      return undefined;
    }
  );
}

/** Releases an un-confirmed hold on the server so the room is bookable again immediately. */
export async function releaseHold(bookingId: string, bookingToken?: string): Promise<void> {
  await request<unknown>(`/api/bookings/${encodeURIComponent(bookingId)}/hold`, {
    method: 'DELETE',
    headers: bookingToken ? { 'X-Booking-Token': bookingToken } : {},
  });
}

/**
 * Best-effort release fired when the guest closes the tab / navigates away while still holding a
 * room (BookingModal's `pagehide`/`visibilitychange` handler) — a normal `await releaseHold(...)`
 * would usually get killed mid-flight by the browser tearing the page down before the request
 * finishes. `keepalive: true` tells the browser to let this one request outlive the page; nothing
 * reads the response (there's no page left to read it), and any failure is silently swallowed
 * since the 10-minute hold will still expire on its own on the server either way.
 */
export function releaseHoldOnUnload(bookingId: string, bookingToken?: string): void {
  try {
    fetch(`${API_BASE}/api/bookings/${encodeURIComponent(bookingId)}/hold`, {
      method: 'DELETE',
      headers: bookingToken ? { 'X-Booking-Token': bookingToken } : {},
      keepalive: true,
    }).catch(() => {
      /* best effort only - the hold still expires on its own within 10 minutes */
    });
  } catch {
    /* fetch itself can throw synchronously (e.g. keepalive payload limits); ignore - same reason */
  }
}

export async function confirmBooking(
  bookingId: string,
  payload: ConfirmBookingRequest,
  token?: string
): Promise<BookingResponse> {
  return request<BookingResponse>(`/api/bookings/${encodeURIComponent(bookingId)}/confirm`, {
    method: 'POST',
    headers: jsonHeaders(token || payload.bookingToken),
    body: JSON.stringify(payload),
  });
}

/** Public view of a booking by ID: state and amounts only - guest details are masked and no token is returned. */
export async function fetchBooking(bookingId: string): Promise<BookingResponse> {
  return request<BookingResponse>(`/api/bookings/${encodeURIComponent(bookingId)}`, {}, (status) =>
    status === 404 ? `Booking ${bookingId} was not found. Please check your Booking ID.` : undefined
  );
}

/**
 * Bookings under a phone number, most recent stay first - MASKED summaries only (partial booking ID,
 * status, dates, room category, masked name). A phone number alone never opens a booking: to manage
 * one, the guest proves ownership with the Booking ID + full phone (accessBooking).
 */
export async function lookupBookingsByPhone(phone: string): Promise<BookingSummary[]> {
  const canonical = normalizePhone(phone);
  if (canonical === null) throw invalidInput(PHONE_ERROR_MESSAGE);
  return request<BookingSummary[]>(`/api/bookings/lookup?phone=${encodeURIComponent(canonical)}`);
}

/**
 * Two-factor lookup: Booking ID + last 4 digits of the phone (or the full number). Returns a masked,
 * token-free summary; use accessBooking with the full number to manage the booking.
 */
export async function lookupBookingVerified(bookingId: string, phoneOrLast4: string): Promise<VerifiedBookingSummary> {
  const proof = isLast4(phoneOrLast4) ? phoneOrLast4.trim() : normalizePhone(phoneOrLast4);
  if (proof === null) throw invalidInput('Enter the last 4 digits of the phone number, or the full 10-digit number.');
  return request<VerifiedBookingSummary>(
    '/api/bookings/lookup',
    { method: 'POST', headers: jsonHeaders(), body: JSON.stringify({ bookingId: bookingId.trim(), phone: proof }) },
    (status) => (status === 404 ? "We couldn't find a booking matching that Booking ID and phone number." : undefined)
  );
}

/**
 * Proves ownership of a booking with the guest's FULL phone number and returns the booking together
 * with a manage token (needed to cancel, extend or pay).
 */
export async function accessBooking(bookingId: string, guestPhone: string): Promise<BookingResponse> {
  const phone = normalizePhone(guestPhone);
  if (phone === null) throw invalidInput(PHONE_ERROR_MESSAGE);
  return request<BookingResponse>(
    `/api/bookings/${encodeURIComponent(bookingId)}/access`,
    { method: 'POST', headers: jsonHeaders(), body: JSON.stringify({ guestPhone: phone }) },
    (status) => {
      if (status === 403) return "That phone number doesn't match this booking. Please use the number you booked with.";
      if (status === 404) return `Booking ${bookingId} was not found. Please check your Booking ID.`;
      return undefined;
    }
  );
}

export async function extendBooking(
  bookingId: string,
  payload: ExtendBookingRequest,
  manageToken: string
): Promise<BookingResponse> {
  return request<BookingResponse>(
    `/api/bookings/${encodeURIComponent(bookingId)}/extend`,
    { method: 'POST', headers: jsonHeaders(manageToken), body: JSON.stringify(payload) },
    (status) =>
      status === 409
        ? 'Room unavailable for requested extension period. Please choose an earlier checkout date.'
        : undefined
  );
}

/** Records a UPI payment (12-character UTR) for the balance still owed on a confirmed booking. */
export async function payBalance(bookingId: string, paymentReference: string, manageToken: string): Promise<BookingResponse> {
  return request<BookingResponse>(`/api/bookings/${encodeURIComponent(bookingId)}/pay`, {
    method: 'POST',
    headers: jsonHeaders(manageToken),
    body: JSON.stringify({ paymentReference }),
  });
}

/**
 * Cancels a confirmed booking. Idempotent on the server. `acknowledgeLate` must be true to cancel
 * after the free-cancellation deadline.
 */
export async function cancelBooking(
  bookingId: string,
  manageToken: string,
  acknowledgeLate = false
): Promise<BookingResponse> {
  return request<BookingResponse>(`/api/bookings/${encodeURIComponent(bookingId)}/cancel`, {
    method: 'POST',
    headers: jsonHeaders(manageToken),
    body: JSON.stringify({ acknowledgeLate }),
  });
}

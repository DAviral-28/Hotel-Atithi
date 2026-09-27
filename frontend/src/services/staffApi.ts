import { request } from './api';
import {
  RoomBlockResponse,
  StaffBooking,
  StaffDashboard,
  StaffLoginResponse,
  WalkInRequest,
} from '../types';
import { requirePhone } from '../utils/phoneNormalizer';

/**
 * Front-desk API client. The staff session token lives only in this tab's sessionStorage (cleared when the
 * tab closes or the session expires) and is sent as X-Staff-Token on every call. A 401 from the server
 * means the session is over: callers clear it with clearStaffSession() and show the PIN screen.
 */

const STORAGE_KEY = 'atithi.staff.session';

interface StoredSession {
  token: string;
  expiresAt: string;
}

function readSession(): StoredSession | null {
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as StoredSession;
    if (!parsed.token || Date.parse(parsed.expiresAt) <= Date.now()) {
      window.sessionStorage.removeItem(STORAGE_KEY);
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}

/** The current staff token, or null when signed out / expired. */
export function getStaffToken(): string | null {
  return readSession()?.token ?? null;
}

export function clearStaffSession(): void {
  try {
    window.sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    /* storage unavailable: nothing to clear */
  }
}

function saveSession(res: StaffLoginResponse): void {
  try {
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ token: res.token, expiresAt: res.expiresAt }));
  } catch {
    /* private mode etc.: the session then only lasts until reload, which is fine */
  }
}

let memoryToken: string | null = null;

function authHeaders(json = true): Record<string, string> {
  const token = memoryToken ?? getStaffToken();
  const h: Record<string, string> = {};
  if (json) h['Content-Type'] = 'application/json';
  if (token) h['X-Staff-Token'] = token;
  return h;
}

/** Exchanges the staff PIN for a session token (valid 8-12 hours). */
export async function staffLogin(pin: string): Promise<StaffLoginResponse> {
  const res = await request<StaffLoginResponse>(
    '/api/staff/login',
    { method: 'POST', headers: authHeaders(), body: JSON.stringify({ pin: pin.trim() }) },
    (status) => (status === 401 ? 'Incorrect PIN. Please try again.' : undefined)
  );
  memoryToken = res.token;
  saveSession(res);
  return res;
}

export function staffLogout(): void {
  memoryToken = null;
  clearStaffSession();
}

export async function fetchStaffDashboard(): Promise<StaffDashboard> {
  return request<StaffDashboard>('/api/staff/dashboard', { headers: authHeaders(false) });
}

export async function staffCheckIn(bookingId: string): Promise<StaffBooking> {
  return request<StaffBooking>(`/api/staff/bookings/${encodeURIComponent(bookingId)}/check-in`, {
    method: 'POST',
    headers: authHeaders(),
  });
}

export async function staffCheckOut(bookingId: string): Promise<StaffBooking> {
  return request<StaffBooking>(`/api/staff/bookings/${encodeURIComponent(bookingId)}/check-out`, {
    method: 'POST',
    headers: authHeaders(),
  });
}

/** Collects the outstanding balance at the desk (or the given amount), or verifies an online UPI payment. */
export async function staffVerifyPayment(bookingId: string, amountCollected?: number): Promise<StaffBooking> {
  return request<StaffBooking>(`/api/staff/bookings/${encodeURIComponent(bookingId)}/verify-payment`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(amountCollected === undefined ? {} : { amountCollected }),
  });
}

/** Takes a room out of order for the inclusive nights startDate..endDate. */
export async function staffBlockRoom(roomNumber: number, startDate: string, endDate: string): Promise<RoomBlockResponse> {
  return request<RoomBlockResponse>(`/api/staff/rooms/${roomNumber}/block`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({ startDate, endDate }),
  });
}

/** Returns a room to service (all maintenance nights from today, unless a range is given). */
export async function staffUnblockRoom(roomNumber: number, startDate?: string, endDate?: string): Promise<RoomBlockResponse> {
  return request<RoomBlockResponse>(`/api/staff/rooms/${roomNumber}/unblock`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({ startDate, endDate }),
  });
}

export async function staffWalkIn(req: WalkInRequest): Promise<StaffBooking> {
  const body: WalkInRequest = { ...req, guestPhone: requirePhone(req.guestPhone) };
  if (!body.guestEmail?.trim()) delete body.guestEmail;
  return request<StaffBooking>('/api/staff/walkin', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(body),
  });
}

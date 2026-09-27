import { PROPERTY_DETAILS } from '../data/roomDirectory';
import { normalizePhone } from './phoneNormalizer';
import { formatRupees } from './format';

/**
 * Zero-cost guest notification: a click-to-chat link (https://wa.me/91<number>?text=...). It needs no
 * WhatsApp Business API, no account and no fees - the guest (or the front desk) just taps "Send" in
 * their own WhatsApp.
 */

/** The fields the message needs; satisfied by BookingResponse and by the staff view of a booking. */
export interface WhatsAppBooking {
  bookingId: string;
  roomNumber: number;
  checkInDate: string;
  checkOutDate: string;
  guestName?: string | null;
  guestPhone: string;
  guestCount?: number;
  totalAmount: number;
  amountPaid: number;
  balanceDue: number;
  paymentStatus: string;
}

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/**
 * "Mon, 5 Oct 2026" from a YYYY-MM-DD calendar date. Built by hand (not Intl) so the text is identical in
 * every browser / locale and never shifted by a time zone.
 */
export function formatLongDate(date: string): string {
  const [y, m, d] = date.split('-').map(Number);
  const weekday = new Date(Date.UTC(y, m - 1, d)).getUTCDay();
  return `${WEEKDAYS[weekday]}, ${d} ${MONTHS[m - 1]} ${y}`;
}

/** "Paid Online" / "Paid at the front desk" / "₹1,260.00 Due at Check-in". */
export function paymentSummary(b: Pick<WhatsAppBooking, 'paymentStatus' | 'balanceDue' | 'amountPaid'>): string {
  if (b.paymentStatus === 'PAID_ONLINE' && b.balanceDue <= 0) return 'Paid Online';
  if (b.paymentStatus === 'PAID_AT_DESK' && b.balanceDue <= 0) return 'Paid at the front desk';
  if (b.balanceDue > 0) {
    const paid = b.amountPaid > 0 ? ` (${formatRupees(b.amountPaid)} already paid)` : '';
    return `${formatRupees(b.balanceDue)} Due at Check-in${paid}`;
  }
  return 'Paid';
}

/** The confirmation text that is placed in the WhatsApp message box. */
export function buildWhatsAppMessage(b: WhatsAppBooking): string {
  const lines = [
    `*${PROPERTY_DETAILS.name} - Booking Confirmation*`,
    '',
    ...(b.guestName ? [`Guest: ${b.guestName}${b.guestCount ? ` (${b.guestCount} ${b.guestCount === 1 ? 'guest' : 'guests'})` : ''}`] : []),
    `Booking ID: ${b.bookingId}`,
    `Room Number: ${b.roomNumber}`,
    `Check-in: ${formatLongDate(b.checkInDate)}, 12:00 PM IST`,
    `Check-out: ${formatLongDate(b.checkOutDate)}, 12:00 PM IST`,
    `Payment: ${paymentSummary(b)}`,
    '',
    `Reception: ${PROPERTY_DETAILS.phone}`,
    `Directions: ${PROPERTY_DETAILS.name}, ${PROPERTY_DETAILS.address} (${PROPERTY_DETAILS.landmark})`,
    `Map: https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(`${PROPERTY_DETAILS.name} Station Road Banda`)}`,
    '',
    'Please show this message or your Booking ID at the front desk.',
  ];
  return lines.join('\n');
}

/**
 * https://wa.me/91XXXXXXXXXX?text=<encoded message> for the booking's guest, or null when the phone
 * number on the booking is not a valid Indian mobile number (the backend stores canonical numbers, but
 * a masked or legacy value must never produce a link to a wrong number).
 */
export function buildWhatsAppLink(b: WhatsAppBooking): string | null {
  const phone = normalizePhone(b.guestPhone);
  if (!phone) return null;
  return `https://wa.me/91${phone}?text=${encodeURIComponent(buildWhatsAppMessage(b))}`;
}

/** A plain chat link (no prefilled text) for the front desk to message a guest. */
export function buildWhatsAppChatLink(guestPhone: string): string | null {
  const phone = normalizePhone(guestPhone);
  return phone ? `https://wa.me/91${phone}` : null;
}

/**
 * UPI helpers (zero-cost payments: no gateway, the guest pays the hotel's UPI ID directly).
 * The URI follows the NPCI UPI linking specification.
 */

export interface UpiPayParams {
  /** Payee VPA, e.g. hotelatithi@okhdfcbank */
  vpa: string;
  payeeName: string;
  /** Amount in rupees. Formatted to 2 decimals. */
  amount: number;
  bookingId: string;
}

/** upi://pay?pa=<vpa>&pn=<name>&am=<amount>&tn=Booking-<id>&cu=INR */
export function buildUpiUri({ vpa, payeeName, amount, bookingId }: UpiPayParams): string {
  const query = [
    `pa=${encodeURIComponent(vpa)}`,
    `pn=${encodeURIComponent(payeeName)}`,
    `am=${amount.toFixed(2)}`,
    `tn=${encodeURIComponent(`Booking-${bookingId}`)}`,
    'cu=INR',
  ].join('&');
  return `upi://pay?${query}`;
}

/** A UPI transaction reference (UTR / RRN) is exactly 12 letters or digits (UPI apps show 12 digits; some banks add letters). */
export const UTR_PATTERN = /^[A-Za-z0-9]{12}$/;

export function isValidUtr(value: string): boolean {
  return UTR_PATTERN.test(value.trim());
}

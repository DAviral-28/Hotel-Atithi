import { HOTEL_TIME_ZONE } from './dates';

/** "23 Sep 2026, 12:00 PM" in Indian Standard Time, whatever time zone the browser is in. */
export function formatIstDateTime(value: string | number | Date): string {
  const d = value instanceof Date ? value : new Date(value);
  return new Intl.DateTimeFormat('en-IN', {
    timeZone: HOTEL_TIME_ZONE,
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    hour12: true,
  }).format(d);
}

/** ₹1,470.00 */
export function formatRupees(amount: number): string {
  return `₹${amount.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

/**
 * Canonical phone handling, mirroring the backend `PhoneNormalizer` (keep the two in step).
 *
 * "+91 94523 14397", "09452314397", "94523-14397" and "9452314397" all become "9452314397".
 * The canonical form is 10 digits starting with 6-9: /^[6-9]\d{9}$/.
 */

export const CANONICAL_PHONE = /^[6-9]\d{9}$/;
const FORMATTING = /[\s().\-]/g;
const DIGITS_WITH_OPTIONAL_PLUS = /^\+?\d+$/;
const LAST4 = /^\d{4}$/;

export const PHONE_ERROR_MESSAGE = 'Enter a valid 10-digit Indian mobile number (starting with 6, 7, 8 or 9).';

/** Canonical 10-digit number, or null when the input is not a valid Indian mobile number. */
export function normalizePhone(raw: string | null | undefined): string | null {
  if (raw == null) return null;
  const compact = raw.trim().replace(FORMATTING, '');
  if (!DIGITS_WITH_OPTIONAL_PLUS.test(compact)) return null;

  const hasPlus = compact.startsWith('+');
  let digits = hasPlus ? compact.slice(1) : compact;

  if (hasPlus) {
    // "+" is only meaningful as the +91 country code.
    if (digits.length !== 12 || !digits.startsWith('91')) return null;
    digits = digits.slice(2);
  } else {
    // Trunk prefix(es) such as "0" or "00": a real mobile number never starts with 0.
    digits = digits.replace(/^0+/, '');
    if (digits.length === 12 && digits.startsWith('91')) digits = digits.slice(2);
  }
  return CANONICAL_PHONE.test(digits) ? digits : null;
}

export function isValidPhone(raw: string | null | undefined): boolean {
  return normalizePhone(raw) !== null;
}

/** True when the input is exactly four digits ("last 4 digits of your phone"). */
export function isLast4(raw: string | null | undefined): boolean {
  return raw != null && LAST4.test(raw.trim());
}

/** Canonical number or an Error carrying the guest-facing message. */
export function requirePhone(raw: string | null | undefined): string {
  const phone = normalizePhone(raw);
  if (phone === null) throw new Error(PHONE_ERROR_MESSAGE);
  return phone;
}

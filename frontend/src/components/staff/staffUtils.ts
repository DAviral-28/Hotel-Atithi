import { ApiError } from '../../services/api';
import { INITIAL_ROOMS } from '../../data/roomDirectory';
import { PaymentStatus, RoomLiveStatus, RoomType, StaffBooking } from '../../types';

export function roomTypeLabel(type: RoomType): string {
  return type === 'AC' ? 'AC' : type === 'SUPER_DELUXE' ? 'Super Deluxe' : 'Deluxe';
}

/** Tailwind classes per live room status: Green = available, Blue = reserved, Purple = in-house, Amber = maintenance. */
export const ROOM_STATUS_STYLE: Record<RoomLiveStatus, { card: string; chip: string; label: string; dot: string }> = {
  AVAILABLE: {
    card: 'bg-emerald-50 border-emerald-300 hover:bg-emerald-100 text-emerald-900',
    chip: 'bg-emerald-100 text-emerald-800',
    label: 'Available',
    dot: 'bg-emerald-500',
  },
  RESERVED: {
    card: 'bg-sky-50 border-sky-300 hover:bg-sky-100 text-sky-900',
    chip: 'bg-sky-100 text-sky-800',
    label: 'Reserved',
    dot: 'bg-sky-500',
  },
  IN_HOUSE: {
    card: 'bg-purple-50 border-purple-300 hover:bg-purple-100 text-purple-900',
    chip: 'bg-purple-100 text-purple-800',
    label: 'In-house',
    dot: 'bg-purple-500',
  },
  MAINTENANCE: {
    card: 'bg-amber-50 border-amber-400 hover:bg-amber-100 text-amber-900',
    chip: 'bg-amber-100 text-amber-800',
    label: 'Maintenance',
    dot: 'bg-amber-500',
  },
};

/** Message to show for anything thrown by the API layer. */
export function errorMessage(err: unknown): string {
  if (err instanceof ApiError || err instanceof Error) return err.message;
  return 'Something went wrong. Please try again.';
}

/** True when the server rejected the staff session (expired, wrong PIN version...): show the PIN screen again. */
export function isSessionError(err: unknown): boolean {
  return err instanceof ApiError && err.status === 401;
}

export function isPaid(status: PaymentStatus): boolean {
  return status === 'PAID_ONLINE' || status === 'PAID_AT_DESK';
}

export function paymentChip(b: StaffBooking): { text: string; className: string } {
  if (b.balanceDue > 0) {
    return { text: b.amountPaid > 0 ? 'Part paid' : 'Due at desk', className: 'bg-amber-100 text-amber-800' };
  }
  if (b.paymentStatus === 'PAID_AT_DESK') return { text: 'Paid at desk', className: 'bg-emerald-100 text-emerald-800' };
  if (b.paymentMethod === 'UPI_QR' && !b.paymentVerified) {
    return { text: 'UPI - verify', className: 'bg-rose-100 text-rose-800' };
  }
  return { text: 'Paid', className: 'bg-emerald-100 text-emerald-800' };
}

/** Tariff estimate identical to the backend's Room.calculateTariff (used for the walk-in form preview only). */
export function estimateTotal(roomNumber: number, guests: number, nights: number): number | null {
  const room = INITIAL_ROOMS.find((r) => r.roomNumber === roomNumber);
  if (!room || nights < 1 || guests < 1 || guests > room.maxOccupancy) return null;
  const extra = Math.max(0, guests - room.baseOccupancy);
  const subtotal = (room.baseRate + extra * room.extraPersonRate) * nights;
  const gst = Math.round(subtotal * (room.taxRatePercent / 100) * 100) / 100;
  return Math.round((subtotal + gst) * 100) / 100;
}

import React, { useMemo, useState } from 'react';
import { X, UserPlus, Loader2, MessageCircle } from 'lucide-react';
import { StaffBooking, StaffRoomStatus } from '../../types';
import { staffWalkIn } from '../../services/staffApi';
import { INITIAL_ROOMS } from '../../data/roomDirectory';
import { addDays, nightsBetween } from '../../utils/dates';
import { formatRupees } from '../../utils/format';
import { PHONE_ERROR_MESSAGE, isValidPhone } from '../../utils/phoneNormalizer';
import { buildWhatsAppLink } from '../../utils/whatsapp';
import { ErrorBanner } from '../ErrorBanner';
import { ROOM_STATUS_STYLE, errorMessage, estimateTotal, isSessionError, roomTypeLabel } from './staffUtils';

interface WalkInModalProps {
  rooms: StaffRoomStatus[];
  today: string;
  /** Pre-select this room (e.g. opened from a room card). */
  initialRoom?: number;
  onClose: () => void;
  onCreated: (booking: StaffBooking) => void;
  onSessionExpired: () => void;
}

/** Fast front-desk booking: no hold, no online payment rules. The guest can be checked in straight away. */
export const WalkInModal: React.FC<WalkInModalProps> = ({ rooms, today, initialRoom, onClose, onCreated, onSessionExpired }) => {
  const firstFree = rooms.find((r) => r.status === 'AVAILABLE')?.roomNumber ?? rooms[0]?.roomNumber ?? 101;
  const [roomNumber, setRoomNumber] = useState<number>(initialRoom ?? firstFree);
  const [guestName, setGuestName] = useState('');
  const [guestPhone, setGuestPhone] = useState('');
  const [guestEmail, setGuestEmail] = useState('');
  const [guestCount, setGuestCount] = useState(1);
  const [checkInDate, setCheckInDate] = useState(today);
  const [checkOutDate, setCheckOutDate] = useState(addDays(today, 1));
  const [collected, setCollected] = useState('');
  const [checkInNow, setCheckInNow] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<StaffBooking | null>(null);

  const room = useMemo(() => INITIAL_ROOMS.find((r) => r.roomNumber === roomNumber), [roomNumber]);
  const maxGuests = room?.maxOccupancy ?? 3;
  const nights = nightsBetween(checkInDate, checkOutDate);
  const total = estimateTotal(roomNumber, Math.min(guestCount, maxGuests), nights);
  const collectedNumber = collected.trim() === '' ? 0 : Number(collected);
  const arrivesToday = checkInDate === today;

  const validationMessage = (): string | null => {
    if (guestName.trim().length < 2) return 'Enter the guest name.';
    if (!isValidPhone(guestPhone)) return PHONE_ERROR_MESSAGE;
    if (guestCount < 1 || guestCount > maxGuests) return `Room ${roomNumber} sleeps at most ${maxGuests} guests.`;
    if (!checkInDate || checkInDate < today) return 'Check-in cannot be in the past.';
    if (nights < 1) return 'Check-out must be after check-in.';
    if (nights > 30) return 'A stay can be at most 30 nights.';
    if (!Number.isFinite(collectedNumber) || collectedNumber < 0) return 'Enter the amount collected as a positive number (or leave it empty).';
    if (total !== null && collectedNumber > total) return `Amount collected cannot be more than the total (${formatRupees(total)}).`;
    return null;
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    const problem = validationMessage();
    if (problem) {
      setError(problem);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const booking = await staffWalkIn({
        roomNumber,
        checkInDate,
        checkOutDate,
        guestCount,
        guestName: guestName.trim(),
        guestPhone,
        guestEmail: guestEmail.trim() || undefined,
        amountCollected: collectedNumber > 0 ? collectedNumber : undefined,
        checkInNow: arrivesToday ? checkInNow : undefined,
      });
      setCreated(booking);
      onCreated(booking);
    } catch (err) {
      if (isSessionError(err)) {
        onSessionExpired();
        return;
      }
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const whatsApp = created ? buildWhatsAppLink(created) : null;

  return (
    <div className="fixed inset-0 z-50 bg-stone-900/50 flex items-end sm:items-center justify-center p-4" role="dialog" aria-modal="true" aria-label="Walk-in booking">
      <div className="w-full max-w-lg bg-white rounded-3xl shadow-2xl p-6 space-y-5 max-h-[92vh] overflow-y-auto">
        <div className="flex items-start justify-between">
          <div>
            <h2 className="text-xl font-extrabold text-stone-900 flex items-center space-x-2">
              <UserPlus className="w-5 h-5 text-amber-600" />
              <span>Walk-in booking</span>
            </h2>
            <p className="text-xs text-stone-500">Creates a confirmed booking at once. No online hold needed.</p>
          </div>
          <button type="button" onClick={onClose} aria-label="Close" className="p-1.5 rounded-lg hover:bg-stone-100">
            <X className="w-5 h-5 text-stone-500" />
          </button>
        </div>

        {created ? (
          <div className="space-y-4">
            <div className="rounded-2xl bg-emerald-50 border border-emerald-200 p-4 text-sm text-emerald-900 space-y-1">
              <p className="font-bold">
                Room {created.roomNumber} booked for {created.guestName}
              </p>
              <p className="text-xs">
                {created.checkInDate} to {created.checkOutDate} · {created.status === 'CHECKED_IN' ? 'Checked in' : 'Confirmed'} · Total{' '}
                {formatRupees(created.totalAmount)}
                {created.balanceDue > 0 ? ` · Due ${formatRupees(created.balanceDue)}` : ' · Paid'}
              </p>
              <p className="text-[11px] font-mono text-emerald-800 break-all">Booking ID {created.bookingId}</p>
            </div>
            {whatsApp && (
              <a
                href={whatsApp}
                target="_blank"
                rel="noopener noreferrer"
                className="w-full py-3 rounded-xl bg-emerald-600 hover:bg-emerald-700 text-white font-bold text-sm flex items-center justify-center space-x-2"
              >
                <MessageCircle className="w-4 h-4" />
                <span>Send confirmation on WhatsApp</span>
              </a>
            )}
            <button type="button" onClick={onClose} className="w-full py-3 rounded-xl bg-stone-100 hover:bg-stone-200 text-stone-800 font-semibold text-sm">
              Done
            </button>
          </div>
        ) : (
          <form onSubmit={submit} className="space-y-4">
            {error && <ErrorBanner message={error} />}

            <label className="block text-xs font-semibold text-stone-700 space-y-1">
              <span>Room</span>
              <select
                value={roomNumber}
                onChange={(e) => {
                  const n = Number(e.target.value);
                  setRoomNumber(n);
                  const max = INITIAL_ROOMS.find((r) => r.roomNumber === n)?.maxOccupancy ?? 3;
                  if (guestCount > max) setGuestCount(max);
                }}
                className="w-full px-3 py-2.5 rounded-lg border border-stone-300 text-sm bg-white"
              >
                {rooms.map((r) => (
                  <option key={r.roomNumber} value={r.roomNumber}>
                    {r.roomNumber} · {roomTypeLabel(r.type)} · {ROOM_STATUS_STYLE[r.status].label} today
                  </option>
                ))}
              </select>
            </label>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <label className="block text-xs font-semibold text-stone-700 space-y-1">
                <span>Guest name</span>
                <input value={guestName} onChange={(e) => setGuestName(e.target.value)} maxLength={80} className="w-full px-3 py-2.5 rounded-lg border border-stone-300 text-sm" />
              </label>
              <label className="block text-xs font-semibold text-stone-700 space-y-1">
                <span>Mobile number</span>
                <input
                  value={guestPhone}
                  onChange={(e) => setGuestPhone(e.target.value)}
                  inputMode="tel"
                  placeholder="98xxxxxxxx"
                  className="w-full px-3 py-2.5 rounded-lg border border-stone-300 text-sm"
                />
              </label>
              <label className="block text-xs font-semibold text-stone-700 space-y-1">
                <span>Email (optional)</span>
                <input type="email" value={guestEmail} onChange={(e) => setGuestEmail(e.target.value)} maxLength={120} className="w-full px-3 py-2.5 rounded-lg border border-stone-300 text-sm" />
              </label>
              <label className="block text-xs font-semibold text-stone-700 space-y-1">
                <span>Guests (max {maxGuests})</span>
                <input
                  type="number"
                  min={1}
                  max={maxGuests}
                  value={guestCount}
                  onChange={(e) => setGuestCount(Number(e.target.value))}
                  className="w-full px-3 py-2.5 rounded-lg border border-stone-300 text-sm"
                />
              </label>
              <label className="block text-xs font-semibold text-stone-700 space-y-1">
                <span>Check-in</span>
                <input
                  type="date"
                  min={today}
                  value={checkInDate}
                  onChange={(e) => {
                    setCheckInDate(e.target.value);
                    if (e.target.value >= checkOutDate) setCheckOutDate(addDays(e.target.value, 1));
                  }}
                  className="w-full px-3 py-2.5 rounded-lg border border-stone-300 text-sm"
                />
              </label>
              <label className="block text-xs font-semibold text-stone-700 space-y-1">
                <span>Check-out (12:00 PM)</span>
                <input
                  type="date"
                  min={addDays(checkInDate || today, 1)}
                  value={checkOutDate}
                  onChange={(e) => setCheckOutDate(e.target.value)}
                  className="w-full px-3 py-2.5 rounded-lg border border-stone-300 text-sm"
                />
              </label>
            </div>

            <div className="rounded-xl bg-stone-50 border border-stone-200 p-3 text-xs space-y-2">
              <div className="flex justify-between text-stone-600">
                <span>
                  {nights > 0 ? nights : 0} {nights === 1 ? 'night' : 'nights'} (incl. 5% GST)
                </span>
                <span className="font-extrabold text-stone-900 text-sm">{total !== null ? formatRupees(total) : '-'}</span>
              </div>
              <label className="flex items-center justify-between gap-3 text-stone-700 font-semibold">
                <span>Collected now (₹)</span>
                <span className="flex items-center gap-2">
                  <input
                    inputMode="decimal"
                    value={collected}
                    onChange={(e) => setCollected(e.target.value.replace(/[^0-9.]/g, ''))}
                    placeholder="0"
                    className="w-28 px-3 py-2 rounded-lg border border-stone-300 text-sm text-right"
                  />
                  {total !== null && (
                    <button type="button" onClick={() => setCollected(String(total))} className="text-[11px] text-amber-700 font-bold hover:underline">
                      Full amount
                    </button>
                  )}
                </span>
              </label>
            </div>

            {arrivesToday && (
              <label className="flex items-center space-x-2 text-xs text-stone-700">
                <input type="checkbox" checked={checkInNow} onChange={(e) => setCheckInNow(e.target.checked)} />
                <span>Guest is here now - check in immediately</span>
              </label>
            )}

            <button
              type="submit"
              disabled={busy}
              className="w-full py-3 rounded-xl bg-amber-600 hover:bg-amber-700 disabled:opacity-50 text-white font-bold text-sm flex items-center justify-center space-x-2"
            >
              {busy && <Loader2 className="w-4 h-4 animate-spin" />}
              <span>{busy ? 'Booking...' : 'Create booking'}</span>
            </button>
          </form>
        )}
      </div>
    </div>
  );
};

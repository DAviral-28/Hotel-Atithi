import React, { useState, useEffect } from 'react';
import { X, Clock, User, Phone, Mail, Users, ArrowRight, CheckCircle, Banknote } from 'lucide-react';
import { BookingResponse, Room } from '../types';
import { confirmBooking, holdRoom, isRetryable, releaseHold, releaseHoldOnUnload } from '../services/api';
import { nightsBetween } from '../utils/dates';
import { PHONE_ERROR_MESSAGE, isValidPhone, normalizePhone } from '../utils/phoneNormalizer';
import { ErrorBanner } from './ErrorBanner';

interface BookingModalProps {
  room: Room;
  checkInDate: string;
  checkOutDate: string;
  onClose: () => void;
  onSuccess: (booking: BookingResponse) => void;
}

export const BookingModal: React.FC<BookingModalProps> = ({
  room,
  checkInDate,
  checkOutDate,
  onClose,
  onSuccess,
}) => {
  // Step state
  const [step, setStep] = useState<'DETAILS' | 'HOLD_LOCKED'>('DETAILS');

  // Form inputs
  const [guestName, setGuestName] = useState('');
  const [guestPhone, setGuestPhone] = useState('');
  const [guestEmail, setGuestEmail] = useState('');
  const [phoneTouched, setPhoneTouched] = useState(false);
  const phoneInvalid = phoneTouched && guestPhone.trim() !== '' && !isValidPhone(guestPhone);
  const [guestCount, setGuestCount] = useState(room.baseOccupancy);

  // Active Hold state
  const [holdBooking, setHoldBooking] = useState<BookingResponse | null>(null);
  const [secondsRemaining, setSecondsRemaining] = useState<number>(600); // 10 minutes

  // UI state
  const [isSubmitting, setIsSubmitting] = useState(false);
  // A failed server call: what to tell the guest and, if trying again can help, how to retry.
  const [error, setError] = useState<{ message: string; retry?: () => void } | null>(null);
  // True after a failed attempt to release the hold, so the guest can still leave ("Close anyway").
  const [releaseFailed, setReleaseFailed] = useState(false);

  // Nights calculation
  const nights = Math.max(1, nightsBetween(checkInDate, checkOutDate));

  // Live Price Calculation: (Base Tariff + (Extra Guests * Extra Person Fee)) * Nights, plus GST
  // at the room's own tax rate (not a hardcoded figure, so this can't drift from RoomCard's or
  // the backend's calculation if tax rates ever differ by room type).
  const extraGuests = Math.max(0, guestCount - room.baseOccupancy);
  const extraGuestFeePerNight = extraGuests * room.extraPersonRate;
  const roomRatePerNight = room.baseRate + extraGuestFeePerNight;
  const subtotal = roomRatePerNight * nights;
  const gstAmount = Math.round(subtotal * (room.taxRatePercent / 100) * 100) / 100;
  const totalAmount = subtotal + gstAmount;

  const showError = (err: any, fallback: string, retry?: () => void) => {
    setError({ message: err?.message || fallback, retry: isRetryable(err) ? retry : undefined });
  };

  // Countdown timer when on HOLD_LOCKED
  useEffect(() => {
    if (step !== 'HOLD_LOCKED' || secondsRemaining <= 0) return;

    const timer = setInterval(() => {
      setSecondsRemaining((prev) => {
        if (prev <= 1) {
          clearInterval(timer);
          return 0;
        }
        return prev - 1;
      });
    }, 1000);

    return () => clearInterval(timer);
  }, [step, secondsRemaining]);

  // Release the room the instant the guest actually closes the tab/browser or navigates away,
  // instead of letting it sit locked for up to the rest of the 10-minute window. Deliberately
  // `pagehide` only, NOT `visibilitychange` -> 'hidden': visibilitychange also fires on an ordinary
  // tab switch (checking email, copying a code) and releasing the hold then would cancel a booking
  // the guest still intends to finish. `pagehide` fires only on a real unload/navigation-away, and
  // uses releaseHoldOnUnload's keepalive fetch since a page that's going away can't wait for a
  // normal await to resolve.
  useEffect(() => {
    if (step !== 'HOLD_LOCKED' || !holdBooking) return;

    const releaseOnLeave = () => releaseHoldOnUnload(holdBooking.bookingId, holdBooking.bookingToken);

    window.addEventListener('pagehide', releaseOnLeave);
    return () => window.removeEventListener('pagehide', releaseOnLeave);
  }, [step, holdBooking]);

  const formatTimer = (secs: number) => {
    const m = Math.floor(secs / 60);
    const s = secs % 60;
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  // Step 1: Submit Hold Lock
  const handleLockHold = async (e?: React.FormEvent) => {
    e?.preventDefault();
    setError(null);
    if (!isValidPhone(guestPhone)) {
      setPhoneTouched(true);
      setError({ message: PHONE_ERROR_MESSAGE });
      return;
    }
    setIsSubmitting(true);

    try {
      const response = await holdRoom({
        roomNumber: room.roomNumber,
        checkInDate,
        checkOutDate,
        guestCount,
        guestName,
        guestPhone: normalizePhone(guestPhone) ?? guestPhone,
        guestEmail: guestEmail.trim(),
      });

      setHoldBooking(response);
      setSecondsRemaining(response.remainingHoldSeconds ?? 600);
      setStep('HOLD_LOCKED');
    } catch (err: any) {
      showError(err, 'Unable to hold room right now. Please try again.', () => handleLockHold());
    } finally {
      setIsSubmitting(false);
    }
  };

  // Step 2: Confirm Booking. The public site only ever offers "pay at hotel" — nothing to collect online.
  const handleConfirm = async () => {
    if (!holdBooking) return;
    setError(null);
    setIsSubmitting(true);

    try {
      const response = await confirmBooking(
        holdBooking.bookingId,
        {
          paymentMethod: 'PAY_AT_HOTEL',
          bookingToken: holdBooking.bookingToken,
        },
        holdBooking.bookingToken
      );

      onSuccess(response);
    } catch (err: any) {
      // Safe to retry: the server treats a repeated identical confirm as a no-op success.
      showError(err, 'Booking confirmation failed. Please try again.', handleConfirm);
    } finally {
      setIsSubmitting(false);
    }
  };

  // Give the room back to other guests right away instead of leaving it locked for the rest of the
  // 10-minute window. Throws if the server could not be reached / refused.
  const releaseActiveHold = async () => {
    if (!holdBooking) return;
    await releaseHold(holdBooking.bookingId, holdBooking.bookingToken);
    setHoldBooking(null);
  };

  // "Modify guest details": drop the current hold first. Keeping it would make the re-submit try to
  // lock the same room/dates again and collide with the guest's own hold (409).
  const handleModifyDetails = async () => {
    setError(null);
    setIsSubmitting(true);
    try {
      await releaseActiveHold();
      setReleaseFailed(false);
      setStep('DETAILS');
    } catch (err: any) {
      setError({
        message:
          (err.message || 'Could not release your current hold.') +
          ' Please try again — going back now would make your new request clash with this hold.',
        retry: isRetryable(err) ? handleModifyDetails : undefined,
      });
    } finally {
      setIsSubmitting(false);
    }
  };

  // "Release hold & cancel": tell the server before closing.
  const handleReleaseAndCancel = async () => {
    if (releaseFailed) {
      onClose(); // guest chose to leave even though the release call failed; the hold expires by itself
      return;
    }
    setError(null);
    setIsSubmitting(true);
    try {
      await releaseActiveHold();
      onClose();
    } catch (err: any) {
      setReleaseFailed(true);
      setError({
        message:
          (err.message || 'Could not release your hold.') +
          ' Your room will be freed automatically when the 10-minute timer ends, or you can try again.',
      });
    } finally {
      setIsSubmitting(false);
    }
  };

  // Closing with the X (or otherwise dismissing) while a hold is active should also free the room.
  // Best effort and non-blocking: the hold expires on its own if this fails.
  const handleClose = () => {
    if (step === 'HOLD_LOCKED' && holdBooking) {
      releaseHold(holdBooking.bookingId, holdBooking.bookingToken).catch((err) =>
        console.warn('Could not release hold on close; it will expire on its own.', err)
      );
    }
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-in fade-in duration-200">
      <div className="relative w-full max-w-xl max-h-[92vh] overflow-y-auto bg-zinc-900/80 backdrop-blur-xl rounded-3xl p-6 sm:p-8 shadow-2xl border border-white/10">
        <div className="ambient-orb w-56 h-56 bg-cyan-500/10 -top-16 -right-16" />
        {/* Header */}
        <div className="relative z-10 flex items-center justify-between pb-4 border-b border-white/10 mb-6">
          <div className="flex items-center space-x-3.5 min-w-0">
            {room.images[0] && (
              <img
                src={room.images[0]}
                alt={`Room ${room.roomNumber}`}
                className="w-14 h-14 rounded-xl object-cover border border-white/10 shrink-0"
              />
            )}
            <div className="min-w-0">
              <div className="flex items-center space-x-2.5 flex-wrap">
                <span className="text-xl font-bold text-zinc-50">
                  {step === 'DETAILS' ? 'Guest Details & Price Summary' : 'Room Hold Active'}
                </span>
                <span className="text-xs px-2.5 py-0.5 rounded-full font-bold bg-cyan-400/10 text-cyan-300 border border-cyan-400/30">
                  Room {room.roomNumber}
                </span>
              </div>
              <p className="text-xs text-zinc-400 mt-1">
                {checkInDate} to {checkOutDate} • {nights} {nights === 1 ? 'Night' : 'Nights'} (Check-out 12:00 PM)
              </p>
            </div>
          </div>

          <button
            onClick={handleClose}
            aria-label="Close"
            className="p-1.5 rounded-lg hover:bg-white/10 text-zinc-400 hover:text-white transition-colors shrink-0"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Error Alert */}
        {error && (
          <div className="mb-5">
            <ErrorBanner message={error.message} onRetry={error.retry} retrying={isSubmitting} />
          </div>
        )}

        {/* STEP 1: GUEST FORM */}
        {step === 'DETAILS' && (
          <form onSubmit={handleLockHold} className="space-y-4">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              {/* Full Name */}
              <div className="space-y-1">
                <label className="text-xs font-bold text-zinc-300 flex items-center space-x-1.5">
                  <User className="w-3.5 h-3.5 text-cyan-400" />
                  <span>Full Name *</span>
                </label>
                <input
                  type="text"
                  required
                  minLength={2}
                  maxLength={80}
                  autoComplete="name"
                  placeholder="e.g. Vikram Sharma"
                  value={guestName}
                  onChange={(e) => setGuestName(e.target.value)}
                  className="w-full bg-white/5 border border-white/10 rounded-xl px-3.5 py-2 text-sm text-zinc-50 placeholder:text-zinc-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400/50 focus:bg-white/10"
                />
              </div>

              {/* Phone Number */}
              <div className="space-y-1">
                <label className="text-xs font-bold text-zinc-300 flex items-center space-x-1.5">
                  <Phone className="w-3.5 h-3.5 text-cyan-400" />
                  <span>Phone Number *</span>
                </label>
                <input
                  type="tel"
                  required
                  inputMode="tel"
                  autoComplete="tel"
                  maxLength={20}
                  placeholder="+91 98765 43210"
                  value={guestPhone}
                  onChange={(e) => setGuestPhone(e.target.value)}
                  onBlur={() => setPhoneTouched(true)}
                  aria-invalid={phoneInvalid}
                  className={`w-full bg-white/5 border rounded-xl px-3.5 py-2 text-sm text-zinc-50 placeholder:text-zinc-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:bg-white/10 ${
                    phoneInvalid ? 'border-rose-400/60' : 'border-white/10 focus:border-cyan-400/50'
                  }`}
                />
                {phoneInvalid && <p className="text-[11px] text-rose-400">{PHONE_ERROR_MESSAGE}</p>}
              </div>

              {/* Email */}
              <div className="space-y-1">
                <label className="text-xs font-bold text-zinc-300 flex items-center space-x-1.5">
                  <Mail className="w-3.5 h-3.5 text-cyan-400" />
                  <span>Email Address *</span>
                </label>
                <input
                  type="email"
                  required
                  maxLength={120}
                  autoComplete="email"
                  placeholder="vikram@example.com"
                  value={guestEmail}
                  onChange={(e) => setGuestEmail(e.target.value)}
                  className="w-full bg-white/5 border border-white/10 rounded-xl px-3.5 py-2 text-sm text-zinc-50 placeholder:text-zinc-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400/50 focus:bg-white/10"
                />
              </div>

              {/* Guest Count */}
              <div className="space-y-1">
                <label className="text-xs font-bold text-zinc-300 flex items-center space-x-1.5">
                  <Users className="w-3.5 h-3.5 text-cyan-400" />
                  <span>Total Guests (Max {room.maxOccupancy})</span>
                </label>
                <select
                  value={guestCount}
                  onChange={(e) => setGuestCount(Number(e.target.value))}
                  className="w-full bg-white/5 border border-white/10 rounded-xl px-3 py-2 text-sm text-zinc-50 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400/50 focus:bg-white/10 [color-scheme:dark]"
                >
                  {Array.from({ length: room.maxOccupancy }, (_, i) => i + 1).map((n) => (
                    <option key={n} value={n}>
                      {n} {n === 1 ? 'Guest' : 'Guests'} {n > room.baseOccupancy ? `(+₹${room.extraPersonRate * (n - room.baseOccupancy)}/night)` : ''}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            {/* Price Breakdown Calculation */}
            <div className="p-4 rounded-2xl bg-white/5 border border-white/10 space-y-2 text-xs">
              <div className="flex justify-between text-zinc-400">
                <span>Room Tariff ({nights} {nights === 1 ? 'night' : 'nights'} × ₹{room.baseRate})</span>
                <span className="font-semibold text-zinc-200">₹{(room.baseRate * nights).toFixed(2)}</span>
              </div>

              {extraGuests > 0 && (
                <div className="flex justify-between text-zinc-400">
                  <span>
                    Extra Person Charges ({extraGuests} guest × ₹{room.extraPersonRate} × {nights} nights)
                  </span>
                  <span className="font-semibold text-zinc-200">₹{(extraGuestFeePerNight * nights).toFixed(2)}</span>
                </div>
              )}

              <div className="flex justify-between text-zinc-400">
                <span>GST ({room.taxRatePercent}% = CGST {room.taxRatePercent / 2}% + SGST {room.taxRatePercent / 2}%)</span>
                <span className="font-semibold text-zinc-200">₹{gstAmount.toFixed(2)}</span>
              </div>

              <div className="pt-2 border-t border-white/10 flex justify-between items-baseline">
                <div>
                  <span className="text-sm font-bold text-zinc-50 block">Total Amount Payable</span>
                  <span className="text-[11px] text-zinc-500">Includes all taxes and standard amenities</span>
                </div>
                <span className="text-xl font-black gold-text">₹{totalAmount.toFixed(2)}</span>
              </div>
            </div>

            {/* Hold Button CTA */}
            <div className="pt-2">
              <button
                type="submit"
                disabled={isSubmitting}
                className="w-full py-3 bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white font-black rounded-xl flex items-center justify-center space-x-2 shadow-lg shadow-cyan-500/20 transition-all active:scale-[0.98] disabled:opacity-50"
              >
                {isSubmitting ? (
                  <div className="w-5 h-5 border-2 border-white border-t-transparent rounded-full animate-spin" />
                ) : (
                  <>
                    <span>Hold Room for 10 Minutes</span>
                    <ArrowRight className="w-4 h-4 stroke-[2.5]" />
                  </>
                )}
              </button>
              <p className="text-[11px] text-zinc-500 text-center mt-2">
                Locks this room exclusively for 10 minutes so you can review and confirm without double-booking.
              </p>
            </div>
          </form>
        )}

        {/* STEP 2: HOLD LOCKED COUNTDOWN & CONFIRMATION */}
        {step === 'HOLD_LOCKED' && holdBooking && (
          <div className="space-y-5 animate-in fade-in duration-200">
            {/* Countdown Box */}
            <div className="p-5 rounded-2xl bg-cyan-400/10 border border-cyan-400/30 text-center relative overflow-hidden">
              <div className="flex items-center justify-center space-x-2 text-cyan-200 mb-1">
                <Clock className="w-5 h-5 text-cyan-400 animate-spin" style={{ animationDuration: '6s' }} />
                <span className="text-xs font-bold uppercase tracking-wider">
                  Room Reserved Exclusively
                </span>
              </div>

              {/* Big Countdown Timer */}
              <div className="text-4xl sm:text-5xl font-black tracking-widest gold-text my-2 font-mono">
                {formatTimer(secondsRemaining)}
              </div>

              <p className="text-xs text-cyan-200/90 max-w-sm mx-auto">
                {secondsRemaining > 0 ? (
                  <>Please complete your booking before the timer expires. Your room is temporarily held.</>
                ) : (
                  <span className="text-rose-400 font-bold">
                    Hold expired! The room has been released back to availability.
                  </span>
                )}
              </p>
            </div>

            {/* Booking Details Summary */}
            <div className="grid grid-cols-2 gap-3 text-xs bg-white/5 p-4 rounded-xl border border-white/10">
              <div>
                <span className="text-zinc-500 block">Booking Reference</span>
                <span className="font-mono font-bold text-zinc-100">{holdBooking.bookingId}</span>
              </div>
              <div>
                <span className="text-zinc-500 block">Guest Name</span>
                <span className="font-bold text-zinc-100">{guestName}</span>
              </div>
              <div>
                <span className="text-zinc-500 block">Stay Dates</span>
                <span className="font-semibold text-zinc-300">{checkInDate} to {checkOutDate}</span>
              </div>
              <div>
                <span className="text-zinc-500 block">Total Amount</span>
                <span className="font-bold text-cyan-300 text-sm">₹{holdBooking.totalAmount.toFixed(2)}</span>
              </div>
            </div>

            {/* Payment: pay-at-hotel only — nothing to fill in, nothing to pay online */}
            <div className="flex items-start space-x-3 p-3.5 rounded-xl border border-cyan-400/40 bg-cyan-400/10">
              <Banknote className="w-4 h-4 text-cyan-300 mt-0.5 shrink-0" />
              <span className="text-xs">
                <span className="font-bold text-zinc-50 block text-sm">Pay at Hotel on Arrival</span>
                <span className="text-zinc-300 block mt-0.5">
                  No advance payment needed. Pay ₹{holdBooking.totalAmount.toFixed(2)} easily via Cash, UPI, or Card at the
                  front desk during check-in.
                </span>
              </span>
            </div>

            {/* Confirm CTA */}
            <div className="pt-2 space-y-2">
              <button
                onClick={handleConfirm}
                disabled={isSubmitting || secondsRemaining <= 0}
                className="w-full py-3 bg-gradient-to-r from-emerald-400 to-emerald-500 hover:from-emerald-300 hover:to-emerald-400 text-zinc-950 font-black rounded-xl flex items-center justify-center space-x-2 shadow-lg shadow-emerald-500/20 transition-all active:scale-[0.98] disabled:opacity-40"
              >
                {isSubmitting ? (
                  <div className="w-5 h-5 border-2 border-zinc-950 border-t-transparent rounded-full animate-spin" />
                ) : (
                  <>
                    <CheckCircle className="w-5 h-5" />
                    <span>Confirm Reservation (Pay at Hotel)</span>
                  </>
                )}
              </button>

              <div className="flex justify-between items-center px-1">
                <button
                  onClick={handleModifyDetails}
                  disabled={isSubmitting}
                  className="text-xs text-zinc-500 hover:text-zinc-200 underline disabled:opacity-50"
                >
                  Modify guest details
                </button>
                <button
                  onClick={handleReleaseAndCancel}
                  disabled={isSubmitting}
                  className="text-xs text-rose-400 hover:text-rose-300 disabled:opacity-50"
                >
                  {releaseFailed ? 'Close anyway' : 'Release hold & cancel'}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

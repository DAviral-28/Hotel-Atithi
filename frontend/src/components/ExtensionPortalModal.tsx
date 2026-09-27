import React, { useRef, useState } from 'react';
import { X, Search, CalendarClock, CheckCircle2, ArrowRight, Phone, ArrowLeft, Ban, FileText, QrCode } from 'lucide-react';
import { BookingResponse, BookingSummary, PaymentConfig, VerifiedBookingSummary } from '../types';
import {
  accessBooking,
  extendBooking,
  fetchPaymentConfig,
  isRetryable,
  lookupBookingsByPhone,
  lookupBookingVerified,
  payBalance,
} from '../services/api';
import { PROPERTY_DETAILS } from '../data/roomDirectory';
import { MAX_STAY_NIGHTS, addDays, todayIST } from '../utils/dates';
import { formatRupees } from '../utils/format';
import { isValidUtr } from '../utils/upi';
import { PHONE_ERROR_MESSAGE, isLast4, isValidPhone } from '../utils/phoneNormalizer';
import { ErrorBanner } from './ErrorBanner';
import { UpiPaymentPanel } from './UpiPaymentPanel';
import { CancelBookingModal } from './CancelBookingModal';

interface ExtensionPortalModalProps {
  onClose: () => void;
  /** Opens the invoice / slip for a booking (with its manage token so it can be cancelled from there). */
  onViewSlip: (booking: BookingResponse, manageToken: string) => void;
  /** Tell the app the room grid may have changed (extension / cancellation). */
  onInventoryChanged?: () => void;
}

/**
 * Self-service "manage my booking" portal. A phone number is not a secret, so it is never enough on its own:
 *  - phone only            -> masked summaries (partial booking ID, dates, room type) - nothing actionable;
 *  - Booking ID + last 4   -> a masked view-only summary;
 *  - Booking ID + full phone -> the server issues a manage token, and the guest can extend the stay,
 *    pay an outstanding balance by UPI, open the invoice, or cancel.
 */
export const ExtensionPortalModal: React.FC<ExtensionPortalModalProps> = ({ onClose, onViewSlip, onInventoryChanged }) => {
  const [phone, setPhone] = useState('');
  const [bookingIdInput, setBookingIdInput] = useState('');
  const [summaries, setSummaries] = useState<BookingSummary[] | null>(null);
  const [verified, setVerified] = useState<VerifiedBookingSummary | null>(null);
  const [fullPhone, setFullPhone] = useState('');

  const [current, setCurrent] = useState<BookingResponse | null>(null);
  const [manageToken, setManageToken] = useState<string | null>(null);

  const [newCheckOutDate, setNewCheckOutDate] = useState('');
  const [showPay, setShowPay] = useState(false);
  const [paymentConfig, setPaymentConfig] = useState<PaymentConfig | null>(null);
  const [utr, setUtr] = useState('');
  const [showCancel, setShowCancel] = useState(false);

  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<{ message: string; retry?: () => void } | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  // Runs a server call with shared loading / error handling; a retryable failure offers "Try again".
  const lastAction = useRef<() => Promise<void>>(async () => {});
  const run = async (action: () => Promise<void>) => {
    lastAction.current = action;
    setError(null);
    setSuccessMessage(null);
    setIsLoading(true);
    try {
      await action();
    } catch (err: any) {
      setError({
        message: err?.message || 'Something went wrong. Please try again.',
        retry: isRetryable(err) ? () => run(lastAction.current) : undefined,
      });
    } finally {
      setIsLoading(false);
    }
  };

  const openBooking = async (bookingId: string, fullPhoneNumber: string) => {
    const b = await accessBooking(bookingId, fullPhoneNumber.trim());
    setCurrent(b);
    setManageToken(b.manageToken ?? null);
    setSummaries(null);
    setVerified(null);
    setShowPay(false);
    setUtr('');
    setNewCheckOutDate(addDays(b.checkOutDate, 1));
  };

  const handleIdentify = (e: React.FormEvent) => {
    e.preventDefault();
    const id = bookingIdInput.trim();
    const proof = phone.trim();
    if (!proof) return;
    setSummaries(null);
    setVerified(null);
    setError(null);

    if (id) {
      if (isLast4(proof)) {
        // Booking ID + last 4 digits: a view-only, masked summary.
        run(async () => setVerified(await lookupBookingVerified(id, proof)));
        return;
      }
      if (!isValidPhone(proof)) {
        setError({ message: PHONE_ERROR_MESSAGE });
        return;
      }
      run(() => openBooking(id, proof));
      return;
    }

    if (!isValidPhone(proof)) {
      setError({ message: PHONE_ERROR_MESSAGE });
      return;
    }
    // Phone only: masked summaries, never anything that can be acted on.
    run(async () => {
      const found = (await lookupBookingsByPhone(proof)).filter(
        (b) => ['CONFIRMED', 'CHECKED_IN', 'CHECKED_OUT', 'CANCELLED'].includes(b.status)
      );
      if (found.length === 0) {
        throw new Error('We found no confirmed bookings for that phone number. Check the number, or enter your Booking ID.');
      }
      setSummaries(found);
    });
  };

  const handleOpenVerified = (e: React.FormEvent) => {
    e.preventDefault();
    if (!verified) return;
    if (!isValidPhone(fullPhone)) {
      setError({ message: PHONE_ERROR_MESSAGE });
      return;
    }
    run(() => openBooking(verified.bookingId, fullPhone));
  };

  const handleExtend = (e: React.FormEvent) => {
    e.preventDefault();
    if (!current || !manageToken || !newCheckOutDate) return;
    run(async () => {
      const updated = await extendBooking(current.bookingId, { newCheckOutDate }, manageToken);
      setCurrent(updated);
      onInventoryChanged?.();
      setSuccessMessage(
        `Your stay is extended until ${updated.checkOutDate} (12:00 PM). New total ${formatRupees(updated.totalAmount)}` +
          (updated.balanceDue > 0
            ? ` — ${formatRupees(updated.balanceDue)} is due at check-in, or you can pay it now by UPI below.`
            : '.')
      );
    });
  };

  const togglePay = () => {
    const next = !showPay;
    setShowPay(next);
    if (next && !paymentConfig) {
      run(async () => {
        const cfg = await fetchPaymentConfig();
        if (!cfg.upiEnabled) {
          setShowPay(false);
          throw new Error('Online UPI payment is not available right now. Please pay at the front desk.');
        }
        setPaymentConfig(cfg);
      });
    }
  };

  const handlePay = () => {
    if (!current || !manageToken) return;
    if (!isValidUtr(utr)) {
      setError({ message: 'Enter the 12-character UTR from your UPI payment receipt.' });
      return;
    }
    run(async () => {
      const updated = await payBalance(current.bookingId, utr.trim(), manageToken);
      setCurrent(updated);
      setShowPay(false);
      setUtr('');
      setSuccessMessage(`Payment recorded (UTR ${utr.trim()}). Nothing is due on this booking now.`);
    });
  };

  const stayEnded = current ? todayIST() > current.checkOutDate : false;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-in fade-in duration-200 no-print">
      <div className="relative w-full max-w-lg max-h-[92vh] overflow-y-auto bg-zinc-900/80 backdrop-blur-xl rounded-3xl p-6 sm:p-8 shadow-2xl border border-white/10">
        <div className="ambient-orb w-56 h-56 bg-cyan-500/10 -top-16 -right-16" />
        {/* Header */}
        <div className="relative z-10 flex items-center justify-between pb-4 border-b border-white/10 mb-6">
          <div className="flex items-center space-x-2.5">
            <div className="p-2.5 rounded-xl bg-cyan-400/10 text-cyan-300 border border-cyan-400/20">
              <CalendarClock className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-lg font-bold text-zinc-50">Manage My Booking</h2>
              <p className="text-xs text-zinc-400">Extend • Pay • Invoice • Cancel — Hotel Atithi</p>
            </div>
          </div>
          <button
            onClick={onClose}
            aria-label="Close"
            className="p-1.5 rounded-lg hover:bg-white/10 text-zinc-400 hover:text-white transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Alerts */}
        {error && (
          <div className="mb-4">
            <ErrorBanner message={error.message} onRetry={error.retry} retrying={isLoading} />
          </div>
        )}
        {successMessage && (
          <div className="mb-4 p-3.5 rounded-xl bg-emerald-400/10 border border-emerald-400/30 text-emerald-200 text-xs flex items-start space-x-2">
            <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0 mt-0.5" />
            <span>{successMessage}</span>
          </div>
        )}

        {/* 1. Identify */}
        {!current && (
          <form onSubmit={handleIdentify} className="space-y-3">
            <div className="space-y-1">
              <label className="text-xs font-bold text-zinc-300 block">Phone number used for the booking *</label>
              <input
                type="tel"
                required
                inputMode="tel"
                maxLength={20}
                placeholder="+91 98765 43210"
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
                className="w-full bg-white/5 border border-white/10 rounded-xl px-3.5 py-2.5 text-sm text-zinc-50 placeholder:text-zinc-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400/50 focus:bg-white/10"
              />
            </div>
            <div className="space-y-1">
              <label className="text-xs font-bold text-zinc-300 block">Booking ID (from your confirmation)</label>
              <input
                type="text"
                maxLength={64}
                autoComplete="off"
                placeholder="Needed to manage a booking"
                value={bookingIdInput}
                onChange={(e) => setBookingIdInput(e.target.value)}
                className="w-full bg-white/5 border border-white/10 rounded-xl px-3.5 py-2.5 text-sm text-zinc-50 placeholder:text-zinc-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400/50 focus:bg-white/10"
              />
            </div>
            <p className="text-[11px] text-zinc-500">
              To protect your privacy, a phone number alone shows only partial details. Enter your Booking ID with your full phone
              number to manage the booking, or with just the last 4 digits to check its status.
            </p>
            <button
              type="submit"
              disabled={isLoading}
              className="w-full px-4 py-2.5 rounded-xl bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white font-black text-sm shadow-lg shadow-cyan-500/20 transition-all flex items-center justify-center space-x-1.5 disabled:opacity-50"
            >
              {isLoading ? (
                <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin" />
              ) : (
                <>
                  <Search className="w-4 h-4" />
                  <span>Find my booking</span>
                </>
              )}
            </button>
          </form>
        )}

        {/* 1b. Phone only: masked summaries */}
        {!current && summaries && (
          <div className="mt-5 space-y-2">
            <p className="text-xs font-bold text-zinc-300">Bookings under this number</p>
            {summaries.map((b) => (
              <div key={`${b.bookingIdHint}-${b.checkInDate}`} className="p-3 rounded-xl border border-white/10 bg-white/5 text-xs space-y-1">
                <div className="flex items-center justify-between">
                  <span className="font-bold text-zinc-50">{b.roomLabel}</span>
                  <span
                    className={`px-2 py-0.5 rounded-full font-bold border ${
                      b.status === 'CANCELLED'
                        ? 'bg-rose-400/10 text-rose-300 border-rose-400/30'
                        : 'bg-emerald-400/10 text-emerald-300 border-emerald-400/30'
                    }`}
                  >
                    {b.status === 'CANCELLED' ? 'Cancelled' : b.status === 'CHECKED_IN' ? 'Checked in' : b.status === 'CHECKED_OUT' ? 'Checked out' : 'Confirmed'}
                  </span>
                </div>
                <div className="text-zinc-400">
                  {b.checkInDate} to {b.checkOutDate} · Guest {b.guestNameMasked}
                </div>
                <div className="text-zinc-500">
                  Booking ID starts with <span className="font-mono font-bold text-zinc-300">{b.bookingIdHint}</span>
                </div>
              </div>
            ))}
            <p className="text-[11px] text-zinc-500">
              Found yours? Enter the full Booking ID from your confirmation above, with your phone number, to manage it.
            </p>
          </div>
        )}

        {/* 1c. Booking ID + last 4 digits: masked, view-only */}
        {!current && verified && (
          <div className="mt-5 space-y-3">
            <div className="p-4 rounded-2xl bg-white/5 border border-white/10 space-y-2 text-xs">
              <div className="flex justify-between">
                <span className="text-zinc-500">Guest:</span>
                <span className="font-bold text-zinc-50">{verified.guestNameMasked}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-zinc-500">Room / Status:</span>
                <span className="font-bold text-cyan-300">
                  {verified.roomLabel} ·{' '}
                  <span className={verified.status === 'CANCELLED' ? 'text-rose-400' : 'text-emerald-400'}>{verified.status}</span>
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-zinc-500">Stay:</span>
                <span className="text-zinc-300">
                  {verified.checkInDate} to {verified.checkOutDate} ({verified.nights} night{verified.nights === 1 ? '' : 's'})
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-zinc-500">Total (incl. GST):</span>
                <span className="text-cyan-300 font-bold">{formatRupees(verified.totalAmount)}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-zinc-500">Phone / Email on file:</span>
                <span className="text-zinc-300">
                  {verified.guestPhoneMasked}
                  {verified.guestEmailMasked ? ` · ${verified.guestEmailMasked}` : ''}
                </span>
              </div>
            </div>
            <form onSubmit={handleOpenVerified} className="space-y-2">
              <label className="text-xs font-bold text-zinc-300 block">Enter the full phone number to manage this booking</label>
              <input
                type="tel"
                required
                inputMode="tel"
                maxLength={20}
                placeholder="+91 98765 43210"
                value={fullPhone}
                onChange={(e) => setFullPhone(e.target.value)}
                className="w-full bg-white/5 border border-white/10 rounded-xl px-3.5 py-2.5 text-sm text-zinc-50 placeholder:text-zinc-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400/50 focus:bg-white/10"
              />
              <button
                type="submit"
                disabled={isLoading}
                className="w-full px-4 py-2.5 rounded-xl bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white font-black text-sm shadow-lg shadow-cyan-500/20 disabled:opacity-50"
              >
                Manage this booking
              </button>
            </form>
          </div>
        )}

        {/* 2. Manage */}
        {current && (
          <div className="space-y-4">
            <button
              onClick={() => {
                setCurrent(null);
                setManageToken(null);
                setSuccessMessage(null);
                setError(null);
              }}
              className="text-xs text-zinc-500 hover:text-zinc-200 flex items-center space-x-1"
            >
              <ArrowLeft className="w-3.5 h-3.5" />
              <span>Different booking</span>
            </button>

            {/* Summary */}
            <div className="p-4 rounded-2xl bg-white/5 border border-white/10 space-y-2 text-xs">
              <div className="flex justify-between">
                <span className="text-zinc-500">Guest:</span>
                <span className="font-bold text-zinc-50">{current.guestName}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-zinc-500">Room / Status:</span>
                <span className="font-bold text-cyan-300">
                  Room {current.roomNumber} ·{' '}
                  <span className={current.status === 'CANCELLED' ? 'text-rose-400' : 'text-emerald-400'}>{current.status}</span>
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-zinc-500">Stay:</span>
                <span className="text-zinc-300">
                  {current.checkInDate} to {current.checkOutDate} (12:00 PM)
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-zinc-500">Total (incl. GST):</span>
                <span className="text-cyan-300 font-bold">{formatRupees(current.totalAmount)}</span>
              </div>
              {(current.status === 'CONFIRMED' || current.status === 'CHECKED_IN') && (
                <div className="flex justify-between">
                  <span className="text-zinc-500">Paid so far / Due at check-in:</span>
                  <span className="text-zinc-300 font-semibold">
                    {formatRupees(current.amountPaid)} / {formatRupees(current.balanceDue)}
                  </span>
                </div>
              )}
            </div>

            {/* Invoice */}
            {manageToken && (
              <button
                onClick={() => onViewSlip(current, manageToken)}
                className="w-full py-2.5 rounded-xl bg-white/5 hover:bg-white/10 border border-white/10 text-zinc-100 font-semibold text-sm flex items-center justify-center space-x-2"
              >
                <FileText className="w-4 h-4 text-cyan-400" />
                <span>View invoice / print</span>
              </button>
            )}

            {(current.status === 'CONFIRMED' || current.status === 'CHECKED_IN') && manageToken && (
              <>
                {/* Pay balance by UPI */}
                {current.balanceDue > 0 && !stayEnded && (
                  <div className="space-y-3">
                    <button
                      onClick={togglePay}
                      disabled={isLoading}
                      className="w-full py-2.5 rounded-xl border border-cyan-400/40 bg-cyan-400/10 hover:bg-cyan-400/15 text-cyan-200 font-semibold text-sm flex items-center justify-center space-x-2 disabled:opacity-50"
                    >
                      <QrCode className="w-4 h-4" />
                      <span>{showPay ? 'Hide UPI payment' : `Pay ${formatRupees(current.balanceDue)} now by UPI`}</span>
                    </button>
                    {showPay && paymentConfig && (
                      <>
                        <UpiPaymentPanel
                          config={paymentConfig}
                          bookingId={current.bookingId}
                          amount={current.balanceDue}
                          utr={utr}
                          onUtrChange={setUtr}
                          disabled={isLoading}
                        />
                        <button
                          onClick={handlePay}
                          disabled={isLoading || !isValidUtr(utr)}
                          className="w-full py-2.5 rounded-xl bg-gradient-to-r from-emerald-400 to-emerald-500 hover:from-emerald-300 hover:to-emerald-400 text-zinc-950 font-black text-sm shadow-lg shadow-emerald-500/20 disabled:opacity-40"
                        >
                          Record my UPI payment
                        </button>
                      </>
                    )}
                    <p className="text-[11px] text-zinc-500 text-center">
                      Or simply pay {formatRupees(current.balanceDue)} at the front desk at check-in.
                    </p>
                  </div>
                )}

                {/* Extend */}
                {!stayEnded && (
                  <form onSubmit={handleExtend} className="space-y-3 pt-4 border-t border-white/10">
                    <div className="space-y-1.5">
                      <label className="text-xs font-bold text-zinc-300 block">Extend stay — new check-out date (12:00 PM)</label>
                      <p className="text-[11px] text-zinc-500">
                        A single stay can be at most {MAX_STAY_NIGHTS} nights in total. The extra amount is added to your bill.
                      </p>
                      <input
                        type="date"
                        required
                        min={addDays(current.checkOutDate, 1)}
                        max={addDays(current.checkInDate, MAX_STAY_NIGHTS)}
                        value={newCheckOutDate}
                        onChange={(e) => setNewCheckOutDate(e.target.value)}
                        className="w-full bg-white/5 border border-white/10 rounded-xl px-3.5 py-2.5 text-sm text-zinc-50 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400/50 focus:bg-white/10 [color-scheme:dark]"
                      />
                    </div>
                    <button
                      type="submit"
                      disabled={isLoading || newCheckOutDate <= current.checkOutDate}
                      className="w-full py-3 bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white font-black rounded-xl flex items-center justify-center space-x-2 shadow-lg shadow-cyan-500/20 transition-all disabled:opacity-40 text-sm"
                    >
                      {isLoading ? (
                        <div className="w-5 h-5 border-2 border-white border-t-transparent rounded-full animate-spin" />
                      ) : (
                        <>
                          <span>Confirm Checkout Extension</span>
                          <ArrowRight className="w-4 h-4 stroke-[2.5]" />
                        </>
                      )}
                    </button>
                  </form>
                )}
                {stayEnded && (
                  <p className="text-xs text-zinc-500 text-center">This stay has ended, so it can no longer be extended.</p>
                )}

                {/* Cancel */}
                {current.cancellable && (
                  <button
                    onClick={() => setShowCancel(true)}
                    className="w-full py-2.5 rounded-xl border border-rose-400/30 text-rose-300 hover:bg-rose-400/10 font-semibold text-sm flex items-center justify-center space-x-2"
                  >
                    <Ban className="w-4 h-4" />
                    <span>Cancel Booking</span>
                  </button>
                )}
              </>
            )}
          </div>
        )}

        {/* Helpline */}
        <div className="mt-5 pt-4 border-t border-white/10 flex items-center justify-center space-x-2 text-xs text-zinc-400">
          <Phone className="w-3.5 h-3.5 text-cyan-400" />
          <span>Need help? Call Front Desk:</span>
          <a href={`tel:${PROPERTY_DETAILS.phoneTel}`} className="font-bold text-cyan-300 hover:underline">
            {PROPERTY_DETAILS.phone}
          </a>
        </div>
      </div>

      {showCancel && current && manageToken && (
        <CancelBookingModal
          booking={current}
          manageToken={manageToken}
          onClose={() => setShowCancel(false)}
          onCancelled={(updated) => {
            setCurrent(updated);
            onInventoryChanged?.();
          }}
        />
      )}
    </div>
  );
};

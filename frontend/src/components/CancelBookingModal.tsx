import React, { useState } from 'react';
import { X, XCircle, CheckCircle2, Clock, Loader2 } from 'lucide-react';
import { BookingResponse } from '../types';
import { cancelBooking, isRetryable } from '../services/api';
import { formatIstDateTime } from '../utils/format';
import { PROPERTY_DETAILS } from '../data/roomDirectory';
import { ErrorBanner } from './ErrorBanner';

interface CancelBookingModalProps {
  booking: BookingResponse;
  /** Proof of ownership issued on confirmation / phone verification. */
  manageToken: string;
  onClose: () => void;
  /** Called as soon as the server has cancelled the booking, with the updated booking. */
  onCancelled: (updated: BookingResponse) => void;
}

/**
 * Confirmation dialog for cancelling a booking. Shows the cancellation policy, warns clearly when the
 * free-cancellation window has passed (and then requires an explicit acknowledgement), and reports the
 * server's real answer: a spinner while cancelling, then either success or a retryable error.
 */
export const CancelBookingModal: React.FC<CancelBookingModalProps> = ({ booking, manageToken, onClose, onCancelled }) => {
  const deadline = booking.freeCancellationDeadline ? new Date(booking.freeCancellationDeadline) : null;
  const isLate = deadline !== null && Date.now() > deadline.getTime();

  const [acknowledged, setAcknowledged] = useState(false);
  const [isCancelling, setIsCancelling] = useState(false);
  const [error, setError] = useState<{ message: string; retry?: boolean } | null>(null);
  const [result, setResult] = useState<BookingResponse | null>(null);

  const doCancel = async () => {
    setError(null);
    setIsCancelling(true);
    try {
      const updated = await cancelBooking(booking.bookingId, manageToken, isLate && acknowledged);
      setResult(updated);
      onCancelled(updated);
    } catch (err: any) {
      setError({ message: err?.message || 'We could not cancel the booking. Please try again.', retry: isRetryable(err) });
    } finally {
      setIsCancelling(false);
    }
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="cancel-title"
      className="fixed inset-0 z-[60] flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm no-print"
    >
      <div className="relative w-full max-w-md bg-zinc-900/90 backdrop-blur-xl rounded-3xl p-6 sm:p-7 shadow-2xl border border-white/10 space-y-4">
        <div className="flex items-start justify-between">
          <div className="flex items-center space-x-2.5">
            <div className={`p-2.5 rounded-xl ${result ? 'bg-emerald-400/10 text-emerald-300 border border-emerald-400/20' : 'bg-rose-400/10 text-rose-300 border border-rose-400/20'}`}>
              {result ? <CheckCircle2 className="w-5 h-5" /> : <XCircle className="w-5 h-5" />}
            </div>
            <h2 id="cancel-title" className="text-lg font-bold text-zinc-50">
              {result ? 'Booking cancelled' : 'Cancel this booking?'}
            </h2>
          </div>
          <button
            onClick={onClose}
            aria-label="Close"
            className="p-1.5 rounded-lg hover:bg-white/10 text-zinc-400 hover:text-white"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* ---- Success ---- */}
        {result ? (
          <div className="space-y-3 text-sm">
            <p className="text-zinc-300">
              Room {result.roomNumber} ({result.checkInDate} to {result.checkOutDate}) has been released and your booking
              is now <strong className="text-zinc-100">CANCELLED</strong>.
            </p>
            {result.amountPaid > 0 &&
              (result.lateCancellation ? (
                <p className="p-3 rounded-xl bg-cyan-400/10 border border-cyan-400/30 text-cyan-200 text-xs">
                  This was a late cancellation, so the ₹{result.amountPaid.toFixed(2)} you paid online is not refunded
                  automatically. Please call the front desk on {PROPERTY_DETAILS.phone} to discuss it.
                </p>
              ) : (
                <p className="p-3 rounded-xl bg-emerald-400/10 border border-emerald-400/30 text-emerald-200 text-xs">
                  Your online payment of ₹{result.amountPaid.toFixed(2)} (UTR {result.paymentReference}) will be refunded
                  by the hotel to your UPI account. The front desk may contact you on {PROPERTY_DETAILS.phone}.
                </p>
              ))}
            <button
              onClick={onClose}
              className="w-full py-2.5 rounded-xl bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white font-black text-sm shadow-lg shadow-cyan-500/20"
            >
              Done
            </button>
          </div>
        ) : (
          <>
            {/* ---- Policy ---- */}
            <div className="p-4 rounded-2xl bg-white/5 border border-white/10 text-xs text-zinc-300 space-y-2">
              <p className="font-bold text-zinc-50 flex items-center space-x-1.5">
                <Clock className="w-3.5 h-3.5 text-cyan-400" />
                <span>Cancellation policy</span>
              </p>
              <ul className="list-disc list-inside space-y-1">
                <li>
                  <strong>Free cancellation</strong> up to 24 hours before check-in (check-in is 12:00 PM IST on{' '}
                  {booking.checkInDate}).
                </li>
                <li>
                  After that it is a <strong>late cancellation</strong>: any amount paid online may not be refunded.
                </li>
                <li>Once check-in time is reached the booking can no longer be cancelled online.</li>
              </ul>
              {deadline && (
                <p className="pt-1 font-semibold text-zinc-100">
                  Free cancellation {isLate ? 'ended' : 'ends'} on {formatIstDateTime(deadline)} IST.
                </p>
              )}
            </div>

            {isLate && (
              <label className="flex items-start space-x-2.5 p-3 rounded-xl border border-cyan-400/30 bg-cyan-400/10 text-xs text-cyan-200 cursor-pointer">
                <input
                  type="checkbox"
                  className="mt-0.5 accent-cyan-500"
                  checked={acknowledged}
                  onChange={(e) => setAcknowledged(e.target.checked)}
                />
                <span>
                  <strong>This is a late cancellation.</strong> I understand any prepaid amount
                  {booking.amountPaid > 0 ? ` (₹${booking.amountPaid.toFixed(2)})` : ''} may not be refunded.
                </span>
              </label>
            )}

            {error && (
              <ErrorBanner
                title="Cancellation didn't go through."
                message={error.message}
                onRetry={error.retry ? doCancel : undefined}
                retrying={isCancelling}
              />
            )}

            <div className="flex flex-col-reverse sm:flex-row gap-2.5 pt-1">
              <button
                onClick={onClose}
                disabled={isCancelling}
                className="w-full py-2.5 rounded-xl bg-white/5 hover:bg-white/10 border border-white/10 text-zinc-100 font-semibold text-sm disabled:opacity-50"
              >
                Keep my booking
              </button>
              <button
                onClick={doCancel}
                disabled={isCancelling || (isLate && !acknowledged)}
                className="w-full py-2.5 rounded-xl bg-rose-500 hover:bg-rose-600 text-white font-bold text-sm flex items-center justify-center space-x-2 disabled:opacity-40"
              >
                {isCancelling ? (
                  <>
                    <Loader2 className="w-4 h-4 animate-spin" />
                    <span>Cancelling…</span>
                  </>
                ) : (
                  <span>{isLate ? 'Cancel anyway' : 'Yes, cancel booking'}</span>
                )}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
};

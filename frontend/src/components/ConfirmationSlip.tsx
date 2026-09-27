import React, { useState } from 'react';
import { CheckCircle2, XCircle, Printer, Calendar, User, ArrowLeft, MapPin, Ban, CreditCard, Clock, MessageCircle } from 'lucide-react';
import { BookingResponse, PaymentMethod } from '../types';
import { PROPERTY_DETAILS, INITIAL_ROOMS } from '../data/roomDirectory';
import { nightsBetween } from '../utils/dates';
import { formatIstDateTime, formatRupees } from '../utils/format';
import { buildWhatsAppLink } from '../utils/whatsapp';
import { CancelBookingModal } from './CancelBookingModal';

interface ConfirmationSlipProps {
  booking: BookingResponse;
  /** Manage token from confirmation / phone verification. Without it the Cancel button is hidden. */
  manageToken?: string | null;
  onReset: () => void;
  /** Called with the server's updated booking after a successful cancellation. */
  onBookingChange?: (updated: BookingResponse) => void;
}

const METHOD_LABEL: Record<PaymentMethod, string> = {
  PAY_AT_HOTEL: 'Pay at Hotel',
  UPI_QR: 'UPI (QR / UTR)',
};

/**
 * Booking confirmation + GST tax invoice. What it says about money depends on what was really paid:
 * a pay-at-hotel booking shows "Payment Due at Check-in", never "Total Paid Amount".
 */
export const ConfirmationSlip: React.FC<ConfirmationSlipProps> = ({ booking, manageToken, onReset, onBookingChange }) => {
  const [showCancel, setShowCancel] = useState(false);

  const maskPhone = (phone: string) => {
    if (!phone || phone.length < 6) return phone;
    return phone.slice(0, 3) + ' ••••• •••' + phone.slice(-2);
  };

  const roomThumbnail = INITIAL_ROOMS.find((r) => r.roomNumber === booking.roomNumber)?.images[0];
  const nights = Math.max(1, nightsBetween(booking.checkInDate, booking.checkOutDate));
  const isCancelled = booking.status === 'CANCELLED';
  const fullyPaid = booking.paymentStatus === 'PAID_ONLINE' || booking.paymentStatus === 'PAID_AT_DESK';
  const paidAtDesk = booking.paymentStatus === 'PAID_AT_DESK';
  const balanceDue = booking.balanceDue;
  const halfRate = booking.subtotal > 0 ? Math.round((booking.cgstAmount / booking.subtotal) * 10000) / 100 : 2.5;
  const canCancel = booking.status === 'CONFIRMED' && booking.cancellable && !!manageToken;
  // Zero-cost "notification": a wa.me click-to-chat link with the confirmation text pre-filled. Only offered
  // when the slip holds the guest's full phone number (the confirm / access responses do; masked reads don't).
  const whatsAppLink =
    !isCancelled && (booking.status === 'CONFIRMED' || booking.status === 'CHECKED_IN')
      ? buildWhatsAppLink(booking)
      : null;

  return (
    <>
      {/*
       * Deliberately kept light/white (not the site's dark theme) — this is the invoice guests print
       * for check-in and the tax record they keep. @media print in index.css forces html/body/this
       * container back to a plain white background regardless, so this stays a clean A4 document.
       */}
      <div id="printable-slip" className="max-w-2xl mx-auto bg-white rounded-3xl p-6 sm:p-10 shadow-2xl ring-1 ring-sky-200 border border-stone-200 my-8">
        {/* Legal header */}
        <div className="text-center pb-5 border-b border-stone-200">
          <div
            className={`w-14 h-14 rounded-full flex items-center justify-center mx-auto mb-3 no-print ${
              isCancelled ? 'bg-rose-100 text-rose-700' : 'bg-emerald-100 text-emerald-700'
            }`}
          >
            {isCancelled ? <XCircle className="w-8 h-8 stroke-[2.2]" /> : <CheckCircle2 className="w-8 h-8 stroke-[2.2]" />}
          </div>
          <p className="text-[11px] font-bold tracking-[0.2em] text-stone-500 uppercase">
            {isCancelled ? 'Cancelled Booking' : 'Tax Invoice · Booking Confirmation'}
          </p>
          <h2 className="text-xl sm:text-2xl font-black text-stone-900 mt-1">{PROPERTY_DETAILS.legalName}</h2>
          <p className="text-xs text-stone-500 flex items-center justify-center space-x-1 mt-1">
            <MapPin className="w-3 h-3 text-sky-600 inline no-print" />
            <span>{PROPERTY_DETAILS.address}</span>
          </p>
          <p className="text-xs text-stone-600 font-semibold mt-0.5">Front Desk Phone: {PROPERTY_DETAILS.phone}</p>
          <p className="text-xs text-stone-600 mt-0.5">
            {PROPERTY_DETAILS.gstin && <span className="font-semibold">GSTIN: {PROPERTY_DETAILS.gstin} · </span>}
            SAC: {PROPERTY_DETAILS.sacCode} (Accommodation services) · Place of supply: {PROPERTY_DETAILS.state}
          </p>
          <div
            className={`mt-3 inline-block px-3 py-1 rounded-full text-xs font-bold border ${
              isCancelled
                ? 'bg-rose-50 text-rose-800 border-rose-200'
                : 'bg-emerald-50 text-emerald-800 border-emerald-200'
            }`}
          >
            {isCancelled ? 'Booking Cancelled' : 'Booking Confirmed'}
          </div>
        </div>

        <div className="py-6 space-y-5 text-sm">
          {/* Quick Snapshot Banner */}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 p-4 rounded-2xl bg-stone-50 border border-stone-200 text-center">
            <div>
              <span className="text-[11px] text-stone-500 block uppercase font-bold">Booking / Invoice No.</span>
              <span className="font-bold text-stone-900 font-mono text-[10px] break-all">{booking.bookingId}</span>
            </div>
            <div>
              <span className="text-[11px] text-stone-500 block uppercase font-bold">Room</span>
              {roomThumbnail && (
                <img
                  src={roomThumbnail}
                  alt={`Room ${booking.roomNumber}`}
                  className="w-10 h-10 rounded-lg object-cover border border-stone-200 mx-auto mb-1 no-print"
                />
              )}
              <span className="font-extrabold text-sky-700 text-base">Room {booking.roomNumber}</span>
            </div>
            <div>
              <span className="text-[11px] text-stone-500 block uppercase font-bold">Check-in (12 PM)</span>
              <span className="font-bold text-stone-900 text-xs">{booking.checkInDate}</span>
            </div>
            <div>
              <span className="text-[11px] text-stone-500 block uppercase font-bold">Check-out (12 PM)</span>
              <span className="font-bold text-stone-900 text-xs">{booking.checkOutDate}</span>
            </div>
          </div>

          {/* Stay & Guest Details */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div className="p-4 rounded-xl bg-stone-50 border border-stone-200 space-y-2 text-xs">
              <span className="font-bold text-stone-700 flex items-center space-x-1.5 uppercase tracking-wider text-[11px]">
                <Calendar className="w-3.5 h-3.5 text-sky-600 no-print" />
                <span>Reservation Details</span>
              </span>
              <div className="space-y-1">
                <div className="flex justify-between">
                  <span className="text-stone-500">Check-in:</span>
                  <span className="font-semibold text-stone-800">{booking.checkInDate} (12:00 PM)</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-stone-500">Check-out:</span>
                  <span className="font-semibold text-stone-800">{booking.checkOutDate} (12:00 PM)</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-stone-500">Duration:</span>
                  <span className="font-bold text-sky-800">{nights} {nights === 1 ? 'Night' : 'Nights'}</span>
                </div>
                {booking.confirmedAt && (
                  <div className="flex justify-between">
                    <span className="text-stone-500">Booked on:</span>
                    <span className="font-semibold text-stone-800">{formatIstDateTime(booking.confirmedAt)}</span>
                  </div>
                )}
              </div>
            </div>

            <div className="p-4 rounded-xl bg-stone-50 border border-stone-200 space-y-2 text-xs">
              <span className="font-bold text-stone-700 flex items-center space-x-1.5 uppercase tracking-wider text-[11px]">
                <User className="w-3.5 h-3.5 text-sky-600 no-print" />
                <span>Guest Information</span>
              </span>
              <div className="space-y-1">
                <div className="flex justify-between">
                  <span className="text-stone-500">Guest Name:</span>
                  <span className="font-bold text-stone-900">{booking.guestName}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-stone-500">Contact:</span>
                  <span className="font-mono text-stone-800">{maskPhone(booking.guestPhone)}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-stone-500">Total Guests:</span>
                  <span className="font-semibold text-stone-800">{booking.guestCount} Staying</span>
                </div>
              </div>
            </div>
          </div>

          {/* GST tax invoice */}
          <div className="rounded-xl border border-stone-200 overflow-hidden text-xs">
            <div className="px-4 py-2 bg-stone-100 font-bold text-stone-700 uppercase tracking-wider text-[11px]">
              Tax Invoice — Accommodation Services (SAC {PROPERTY_DETAILS.sacCode})
            </div>
            <table className="invoice-table w-full">
              <thead>
                <tr className="text-left text-stone-500 border-b border-stone-200">
                  <th className="px-4 py-2 font-semibold">Description</th>
                  <th className="px-2 py-2 font-semibold text-right">Qty</th>
                  <th className="px-2 py-2 font-semibold text-right">Rate</th>
                  <th className="px-4 py-2 font-semibold text-right">Amount</th>
                </tr>
              </thead>
              <tbody className="text-stone-700">
                <tr>
                  <td className="px-4 py-2">Room tariff — Room {booking.roomNumber}</td>
                  <td className="px-2 py-2 text-right">{nights} {nights === 1 ? 'night' : 'nights'}</td>
                  <td className="px-2 py-2 text-right">{formatRupees(booking.baseRatePerNight ?? booking.roomTariffSubtotal / nights)}</td>
                  <td className="px-4 py-2 text-right">{formatRupees(booking.roomTariffSubtotal)}</td>
                </tr>
                {booking.extraGuestCount > 0 && (
                  <tr>
                    <td className="px-4 py-2">
                      Extra guest charges ({booking.extraGuestCount} extra {booking.extraGuestCount === 1 ? 'guest' : 'guests'})
                    </td>
                    <td className="px-2 py-2 text-right">{nights} {nights === 1 ? 'night' : 'nights'}</td>
                    <td className="px-2 py-2 text-right">{formatRupees(booking.extraGuestRatePerNight ?? booking.extraGuestChargesSubtotal / nights)}</td>
                    <td className="px-4 py-2 text-right">{formatRupees(booking.extraGuestChargesSubtotal)}</td>
                  </tr>
                )}
                <tr className="border-t border-stone-200 font-semibold text-stone-900">
                  <td className="px-4 py-2" colSpan={3}>Taxable value (Subtotal)</td>
                  <td className="px-4 py-2 text-right">{formatRupees(booking.subtotal)}</td>
                </tr>
                <tr>
                  <td className="px-4 py-1.5" colSpan={3}>CGST @ {halfRate}%</td>
                  <td className="px-4 py-1.5 text-right">{formatRupees(booking.cgstAmount)}</td>
                </tr>
                <tr>
                  <td className="px-4 py-1.5" colSpan={3}>SGST @ {halfRate}%</td>
                  <td className="px-4 py-1.5 text-right">{formatRupees(booking.sgstAmount)}</td>
                </tr>
                <tr className="border-t-2 border-stone-300 text-sm font-bold text-stone-900">
                  <td className="px-4 py-2.5" colSpan={3}>Invoice Total (incl. GST)</td>
                  <td className="px-4 py-2.5 text-right">{formatRupees(booking.totalAmount)}</td>
                </tr>
              </tbody>
            </table>
          </div>

          {/* Payment */}
          <div className="p-4 rounded-xl bg-stone-50 border border-stone-200 space-y-2 text-xs">
            <span className="font-bold text-stone-700 flex items-center space-x-1.5 uppercase tracking-wider text-[11px]">
              <CreditCard className="w-3.5 h-3.5 text-sky-600 no-print" />
              <span>Payment</span>
            </span>
            <div className="flex justify-between text-stone-600">
              <span>Payment method</span>
              <span className="font-semibold text-stone-800">
                {booking.paymentMethod ? METHOD_LABEL[booking.paymentMethod] : '—'}
              </span>
            </div>
            <div className="flex justify-between text-stone-600 items-center">
              <span>Payment status</span>
              <span
                className={`px-2 py-0.5 rounded-full font-bold border ${
                  isCancelled
                    ? 'bg-stone-100 text-stone-700 border-stone-300'
                    : fullyPaid
                    ? 'bg-emerald-50 text-emerald-800 border-emerald-200'
                    : 'bg-sky-50 text-sky-800 border-sky-200'
                }`}
              >
                {isCancelled ? 'Cancelled' : fullyPaid ? 'Paid' : 'Due at Desk'}
              </span>
            </div>
            {booking.amountPaid > 0 && (
              <>
                <div className="flex justify-between text-stone-600">
                  <span>{paidAtDesk ? 'Paid at the front desk' : 'Paid online (UPI)'}</span>
                  <span className="font-semibold text-stone-800">{formatRupees(booking.amountPaid)}</span>
                </div>
                {booking.paymentReference && (
                  <div className="flex justify-between text-stone-600">
                    <span>UTR / Reference</span>
                    <span className="font-mono text-stone-800 break-all text-right">{booking.paymentReference.split(',').join(', ')}</span>
                  </div>
                )}
                {booking.paidAt && (
                  <div className="flex justify-between text-stone-600">
                    <span>Last payment on</span>
                    <span className="text-stone-800">{formatIstDateTime(booking.paidAt)}</span>
                  </div>
                )}
              </>
            )}

            {!isCancelled && (
              <div className="flex justify-between items-baseline pt-2 border-t border-stone-200 text-sm font-bold text-stone-900">
                {balanceDue > 0 ? (
                  <>
                    <span>Payment Due at Check-in</span>
                    <span className="text-sky-800 font-black text-lg">{formatRupees(balanceDue)}</span>
                  </>
                ) : (
                  <>
                    <span>Total Paid Amount</span>
                    <span className="text-emerald-800 font-black text-lg">{formatRupees(booking.amountPaid)}</span>
                  </>
                )}
              </div>
            )}
            {!isCancelled && balanceDue > 0 && (
              <p className="text-[11px] text-stone-500">Accepted at Front Desk: Cash &amp; UPI.</p>
            )}
            {!isCancelled && booking.amountPaid > 0 && (
              <p className="text-[11px] text-stone-500">
                Online payments are verified by the front desk against the hotel's UPI statement.
              </p>
            )}
          </div>

          {/* Cancellation state / policy */}
          {isCancelled ? (
            <div className="p-4 rounded-xl bg-rose-50 border border-rose-200 text-xs text-rose-900 space-y-1">
              <p className="font-bold flex items-center space-x-1.5">
                <Ban className="w-3.5 h-3.5 no-print" />
                <span>
                  This booking was cancelled
                  {booking.cancelledAt ? ` on ${formatIstDateTime(booking.cancelledAt)} IST` : ''}
                  {booking.lateCancellation ? ' (late cancellation)' : ''}.
                </span>
              </p>
              {booking.amountPaid > 0 &&
                (booking.lateCancellation ? (
                  <p>The ₹{booking.amountPaid.toFixed(2)} paid online is not refunded automatically — please contact the front desk.</p>
                ) : (
                  <p>The ₹{booking.amountPaid.toFixed(2)} paid online will be refunded by the hotel to your UPI account.</p>
                ))}
              <p>This document is no longer a valid tax invoice for a stay.</p>
            </div>
          ) : (
            booking.freeCancellationDeadline && (
              <p className="text-[11px] text-stone-600 flex items-start space-x-1.5">
                <Clock className="w-3.5 h-3.5 text-sky-700 shrink-0 mt-px no-print" />
                <span>
                  Free cancellation until <strong>{formatIstDateTime(booking.freeCancellationDeadline)} IST</strong> (24 hours before
                  check-in). Later cancellations are treated as late cancellations and prepaid amounts may not be refunded.
                </span>
              </p>
            )
          )}

          {/* Guest Note */}
          <p className="text-[11px] text-stone-500 text-center">
            Please present this slip or Booking ID at the Front Desk upon arrival. Check-in and check-out timings are 12:00 PM.
            For any assistance, call <strong>{PROPERTY_DETAILS.phone}</strong>.
          </p>
          <p className="print-only text-[10px] text-stone-400 text-center">
            This is a computer-generated invoice and does not require a signature.
          </p>
        </div>

        {/* Buttons */}
        <div className="pt-4 border-t border-stone-200 space-y-3 no-print">
          {whatsAppLink && (
            <a
              href={whatsAppLink}
              target="_blank"
              rel="noopener noreferrer"
              className="w-full py-3.5 rounded-xl bg-emerald-600 hover:bg-emerald-700 text-white font-bold text-sm shadow-sm active:scale-[0.98] transition-all flex items-center justify-center space-x-2"
            >
              <MessageCircle className="w-5 h-5 stroke-[2.2]" />
              <span>Send confirmation to WhatsApp</span>
            </a>
          )}
          <div className="flex flex-col sm:flex-row items-center gap-3">
            <button
              onClick={onReset}
              className="w-full sm:w-1/2 py-3 rounded-xl bg-stone-100 hover:bg-stone-200 text-stone-800 font-semibold text-sm transition-colors flex items-center justify-center space-x-2"
            >
              <ArrowLeft className="w-4 h-4" />
              <span>Book Another Room</span>
            </button>

            <button
              onClick={() => window.print()}
              className="w-full sm:w-1/2 py-3 bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white font-black rounded-xl flex items-center justify-center space-x-2 shadow-lg shadow-cyan-500/20 active:scale-[0.98] transition-all text-sm"
            >
              <Printer className="w-4 h-4 stroke-[2.2]" />
              <span>Print / Save PDF</span>
            </button>
          </div>

          {canCancel && (
            <button
              onClick={() => setShowCancel(true)}
              className="w-full py-2.5 rounded-xl border border-rose-300 text-rose-700 hover:bg-rose-50 font-semibold text-sm flex items-center justify-center space-x-2 transition-colors"
            >
              <Ban className="w-4 h-4" />
              <span>Cancel Booking</span>
            </button>
          )}
          <p className="text-[11px] text-stone-400 text-center">
            To keep a PDF copy, choose “Save as PDF” as the printer in the print dialog.
          </p>
        </div>
      </div>

      {showCancel && manageToken && (
        <CancelBookingModal
          booking={booking}
          manageToken={manageToken}
          onClose={() => setShowCancel(false)}
          onCancelled={(updated) => onBookingChange?.(updated)}
        />
      )}
    </>
  );
};

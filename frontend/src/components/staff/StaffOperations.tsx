import React, { useEffect, useState } from 'react';
import { Phone, MessageCircle, LogIn, LogOut, IndianRupee, ShieldCheck, Loader2 } from 'lucide-react';
import { StaffBooking, StaffDashboard } from '../../types';
import { formatRupees } from '../../utils/format';
import { buildWhatsAppChatLink } from '../../utils/whatsapp';
import { paymentChip, roomTypeLabel } from './staffUtils';

export interface StaffActions {
  /** key of the action currently running (bookingId:action) so its button can show a spinner */
  busyKey: string | null;
  onCheckIn: (b: StaffBooking) => void;
  onCheckOut: (b: StaffBooking) => void;
  /** Collect the full outstanding balance at the desk. */
  onCollect: (b: StaffBooking) => void;
  /** Mark an online UPI payment as checked against the UPI statement. */
  onVerify: (b: StaffBooking) => void;
}

/** A button that needs a second tap within 5 seconds, so money is never recorded by an accidental click. */
const ConfirmButton: React.FC<{
  label: string;
  confirmLabel: string;
  busy: boolean;
  disabled?: boolean;
  className: string;
  icon: React.ReactNode;
  onConfirm: () => void;
}> = ({ label, confirmLabel, busy, disabled, className, icon, onConfirm }) => {
  const [armed, setArmed] = useState(false);
  useEffect(() => {
    if (!armed) return undefined;
    const t = window.setTimeout(() => setArmed(false), 5000);
    return () => window.clearTimeout(t);
  }, [armed]);

  return (
    <button
      type="button"
      disabled={busy || disabled}
      onClick={() => {
        if (armed) {
          setArmed(false);
          onConfirm();
        } else {
          setArmed(true);
        }
      }}
      className={`px-3 py-2 rounded-lg text-xs font-bold flex items-center space-x-1.5 disabled:opacity-50 transition-colors ${
        armed ? 'bg-emerald-700 text-white ring-2 ring-emerald-300' : className
      }`}
    >
      {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : icon}
      <span>{armed ? confirmLabel : label}</span>
    </button>
  );
};

const BookingRow: React.FC<{ booking: StaffBooking; actions: StaffActions; mode: 'arrival' | 'inhouse' | 'payment'; leavesToday?: boolean; overdue?: boolean }> = ({
  booking: b,
  actions,
  mode,
  leavesToday,
  overdue,
}) => {
  const chip = paymentChip(b);
  const chat = buildWhatsAppChatLink(b.guestPhone);
  const busy = (a: string) => actions.busyKey === `${b.bookingId}:${a}`;
  const anyBusy = actions.busyKey !== null && actions.busyKey.startsWith(`${b.bookingId}:`);
  const upiUnverified = b.paymentMethod === 'UPI_QR' && !b.paymentVerified;

  return (
    <li className="bg-white border border-stone-200 rounded-2xl p-4 space-y-3 shadow-sm">
      <div className="flex items-start justify-between gap-3">
        <div className="space-y-0.5 min-w-0">
          <div className="flex items-center flex-wrap gap-2">
            <span className="text-lg font-extrabold text-stone-900">Room {b.roomNumber}</span>
            <span className={`px-2 py-0.5 rounded-full text-[11px] font-bold ${chip.className}`}>{chip.text}</span>
            {overdue && <span className="px-2 py-0.5 rounded-full text-[11px] font-bold bg-rose-100 text-rose-800">Overdue</span>}
            {!overdue && leavesToday && (
              <span className="px-2 py-0.5 rounded-full text-[11px] font-bold bg-purple-100 text-purple-800">Leaves today</span>
            )}
          </div>
          <p className="text-sm font-semibold text-stone-800 truncate">{b.guestName}</p>
          <p className="text-xs text-stone-500">
            {b.checkInDate} to {b.checkOutDate} ({b.nights} {b.nights === 1 ? 'night' : 'nights'}) · {b.guestCount}{' '}
            {b.guestCount === 1 ? 'guest' : 'guests'}
          </p>
        </div>
        <div className="text-right shrink-0">
          <p className="text-[11px] text-stone-500">Total</p>
          <p className="text-sm font-bold text-stone-900">{formatRupees(b.totalAmount)}</p>
          {b.balanceDue > 0 && <p className="text-[11px] font-semibold text-amber-700">Due {formatRupees(b.balanceDue)}</p>}
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-2 text-xs">
        <a href={`tel:+91${b.guestPhone}`} className="inline-flex items-center space-x-1 text-stone-600 hover:text-amber-700">
          <Phone className="w-3.5 h-3.5" />
          <span>{b.guestPhone}</span>
        </a>
        {chat && (
          <a href={chat} target="_blank" rel="noopener noreferrer" className="inline-flex items-center space-x-1 text-emerald-700 hover:underline">
            <MessageCircle className="w-3.5 h-3.5" />
            <span>WhatsApp</span>
          </a>
        )}
        <span className="text-stone-400 font-mono">#{b.bookingId.slice(0, 8)}</span>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        {mode === 'arrival' && (
          <button
            type="button"
            disabled={anyBusy}
            onClick={() => actions.onCheckIn(b)}
            className="px-4 py-2 rounded-lg bg-amber-600 hover:bg-amber-700 disabled:opacity-50 text-white text-xs font-bold flex items-center space-x-1.5"
          >
            {busy('check-in') ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <LogIn className="w-3.5 h-3.5" />}
            <span>Check In</span>
          </button>
        )}
        {b.balanceDue > 0 && (
          <ConfirmButton
            label={`Collect ${formatRupees(b.balanceDue)}`}
            confirmLabel={`Confirm ${formatRupees(b.balanceDue)} received`}
            busy={busy('collect')}
            disabled={anyBusy && !busy('collect')}
            className="bg-emerald-600 hover:bg-emerald-700 text-white"
            icon={<IndianRupee className="w-3.5 h-3.5" />}
            onConfirm={() => actions.onCollect(b)}
          />
        )}
        {upiUnverified && b.balanceDue <= 0 && (
          <ConfirmButton
            label={`Verify UPI${b.paymentReference ? ` (${b.paymentReference})` : ''}`}
            confirmLabel="Confirm: found in UPI statement"
            busy={busy('verify')}
            disabled={anyBusy && !busy('verify')}
            className="bg-rose-600 hover:bg-rose-700 text-white"
            icon={<ShieldCheck className="w-3.5 h-3.5" />}
            onConfirm={() => actions.onVerify(b)}
          />
        )}
        {mode === 'inhouse' && (
          <button
            type="button"
            disabled={anyBusy}
            onClick={() => actions.onCheckOut(b)}
            className="px-4 py-2 rounded-lg bg-purple-600 hover:bg-purple-700 disabled:opacity-50 text-white text-xs font-bold flex items-center space-x-1.5"
          >
            {busy('check-out') ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <LogOut className="w-3.5 h-3.5" />}
            <span>Check Out</span>
          </button>
        )}
      </div>
    </li>
  );
};

const Section: React.FC<{ title: string; count: number; empty: string; children?: React.ReactNode }> = ({ title, count, empty, children }) => (
  <section className="space-y-3">
    <h2 className="text-sm font-extrabold text-stone-800 uppercase tracking-wider flex items-center space-x-2">
      <span>{title}</span>
      <span className="px-2 py-0.5 rounded-full bg-stone-200 text-stone-700 text-[11px]">{count}</span>
    </h2>
    {count === 0 ? <p className="text-xs text-stone-500 bg-white border border-dashed border-stone-300 rounded-2xl p-4">{empty}</p> : <ul className="space-y-3">{children}</ul>}
  </section>
);

/** "Today's operations": who is arriving, who is in the hotel, who leaves. */
export const StaffOperations: React.FC<{ dashboard: StaffDashboard; actions: StaffActions }> = ({ dashboard, actions }) => {
  const departingIds = new Set(dashboard.departures.map((d) => d.bookingId));
  return (
    <div className="space-y-8">
      <Section title="Arrivals" count={dashboard.arrivals.length} empty="No guests are waiting to check in.">
        {dashboard.arrivals.map((b) => (
          <BookingRow key={b.bookingId} booking={b} actions={actions} mode="arrival" overdue={b.checkInDate < dashboard.date} />
        ))}
      </Section>

      <Section title="In-house guests" count={dashboard.inHouse.length} empty="Nobody is checked in right now.">
        {dashboard.inHouse.map((b) => (
          <BookingRow
            key={b.bookingId}
            booking={b}
            actions={actions}
            mode="inhouse"
            leavesToday={departingIds.has(b.bookingId) && b.checkOutDate === dashboard.date}
            overdue={b.checkOutDate < dashboard.date}
          />
        ))}
      </Section>
      <p className="text-[11px] text-stone-400">
        Room type reference: {roomTypeLabel('DELUXE')} / {roomTypeLabel('SUPER_DELUXE')} / {roomTypeLabel('AC')}. Check-in and check-out time is 12:00 PM IST.
      </p>
    </div>
  );
};

/** Online UPI payments the guest declared (UTR) that still have to be matched with the UPI statement. */
export const StaffPayments: React.FC<{ dashboard: StaffDashboard; actions: StaffActions }> = ({ dashboard, actions }) => (
  <div className="space-y-4">
    <p className="text-xs text-stone-600 bg-amber-50 border border-amber-200 rounded-xl p-3">
      Guests type their own UTR after paying by UPI, so it is not proof of payment. Open the hotel's UPI / bank statement, find the
      same UTR and amount, then tap <strong>Verify UPI</strong>.
    </p>
    <Section title="UPI payments to verify" count={dashboard.unverifiedPayments.length} empty="Everything is reconciled.">
      {dashboard.unverifiedPayments.map((b) => (
        <BookingRow key={b.bookingId} booking={b} actions={actions} mode="payment" />
      ))}
    </Section>
  </div>
);

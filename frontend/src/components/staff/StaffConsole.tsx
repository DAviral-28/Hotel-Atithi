import React, { useCallback, useEffect, useRef, useState } from 'react';
import { RefreshCw, LogOut as SignOutIcon, UserPlus, BedDouble, ClipboardList, ShieldCheck } from 'lucide-react';
import { StaffBooking, StaffDashboard, StaffRoomStatus } from '../../types';
import {
  clearStaffSession,
  fetchStaffDashboard,
  getStaffToken,
  staffCheckIn,
  staffCheckOut,
  staffLogout,
  staffVerifyPayment,
} from '../../services/staffApi';
import { PROPERTY_DETAILS } from '../../data/roomDirectory';
import { formatRupees } from '../../utils/format';
import { ErrorBanner } from '../ErrorBanner';
import { StaffPinLock } from './StaffPinLock';
import { StaffActions, StaffOperations, StaffPayments } from './StaffOperations';
import { RoomGrid } from './RoomGrid';
import { RoomActionModal } from './RoomActionModal';
import { WalkInModal } from './WalkInModal';
import { errorMessage, isSessionError } from './staffUtils';

type Tab = 'operations' | 'rooms' | 'payments';

/** Refresh the live board this often while the console is open (cheap: a handful of DynamoDB reads). */
const REFRESH_MS = 45_000;

const StatCard: React.FC<{ label: string; value: React.ReactNode; hint?: string; tone?: string }> = ({ label, value, hint, tone = 'text-stone-900' }) => (
  <div className="bg-white border border-stone-200 rounded-2xl p-4 shadow-sm">
    <p className="text-[11px] font-semibold text-stone-500 uppercase tracking-wider">{label}</p>
    <p className={`text-2xl font-extrabold mt-1 ${tone}`}>{value}</p>
    {hint && <p className="text-[11px] text-stone-500 mt-0.5">{hint}</p>}
  </div>
);

/** The front-desk console at /staff: PIN lock, live stats, today's operations, room grid, walk-ins. */
export const StaffConsole: React.FC = () => {
  const [signedIn, setSignedIn] = useState<boolean>(() => getStaffToken() !== null);
  const [lockNotice, setLockNotice] = useState<string | null>(null);
  const [dashboard, setDashboard] = useState<StaffDashboard | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('operations');
  const [busyKey, setBusyKey] = useState<string | null>(null);
  const [selectedRoom, setSelectedRoom] = useState<StaffRoomStatus | null>(null);
  const [walkInOpen, setWalkInOpen] = useState(false);
  const latestLoad = useRef(0);

  const sessionExpired = useCallback(() => {
    clearStaffSession();
    setDashboard(null);
    setSignedIn(false);
    setLockNotice('Your session ended. Please enter the PIN again.');
  }, []);

  const load = useCallback(
    async (quiet = false) => {
      const id = ++latestLoad.current;
      if (!quiet) setLoading(true);
      try {
        const data = await fetchStaffDashboard();
        if (id !== latestLoad.current) return;
        setDashboard(data);
        setError(null);
      } catch (err) {
        if (id !== latestLoad.current) return;
        if (isSessionError(err)) {
          sessionExpired();
          return;
        }
        setError(errorMessage(err));
      } finally {
        if (id === latestLoad.current) setLoading(false);
      }
    },
    [sessionExpired]
  );

  useEffect(() => {
    if (!signedIn) return undefined;
    load();
    const timer = window.setInterval(() => load(true), REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [signedIn, load]);

  const flash = (message: string) => {
    setNotice(message);
    window.setTimeout(() => setNotice((current) => (current === message ? null : current)), 6000);
  };

  /** Runs one desk action, reports the outcome and reloads the board. */
  const perform = async (booking: StaffBooking, action: string, call: () => Promise<StaffBooking>, success: (b: StaffBooking) => string) => {
    if (busyKey) return;
    setBusyKey(`${booking.bookingId}:${action}`);
    setError(null);
    try {
      const updated = await call();
      flash(success(updated));
      await load(true);
    } catch (err) {
      if (isSessionError(err)) {
        sessionExpired();
        return;
      }
      setError(errorMessage(err));
    } finally {
      setBusyKey(null);
    }
  };

  const actions: StaffActions = {
    busyKey,
    onCheckIn: (b) => perform(b, 'check-in', () => staffCheckIn(b.bookingId), (u) => `Room ${u.roomNumber}: ${u.guestName} checked in.`),
    onCheckOut: (b) =>
      perform(b, 'check-out', () => staffCheckOut(b.bookingId), (u) =>
        u.balanceDue > 0
          ? `Room ${u.roomNumber} checked out - ${formatRupees(u.balanceDue)} is still unpaid.`
          : `Room ${u.roomNumber}: ${u.guestName} checked out. The room is free.`
      ),
    onCollect: (b) => perform(b, 'collect', () => staffVerifyPayment(b.bookingId), (u) => `Room ${u.roomNumber}: ${formatRupees(b.balanceDue)} collected. Fully paid.`),
    onVerify: (b) => perform(b, 'verify', () => staffVerifyPayment(b.bookingId), (u) => `Room ${u.roomNumber}: UPI payment verified.`),
  };

  if (!signedIn) {
    return (
      <StaffPinLock
        notice={lockNotice}
        onUnlocked={() => {
          setLockNotice(null);
          setSignedIn(true);
        }}
      />
    );
  }

  const stats = dashboard?.stats;
  const tabs: Array<{ id: Tab; label: string; icon: React.ReactNode; badge?: number }> = [
    { id: 'operations', label: "Today's operations", icon: <ClipboardList className="w-4 h-4" /> },
    { id: 'rooms', label: 'Room grid', icon: <BedDouble className="w-4 h-4" /> },
    { id: 'payments', label: 'UPI to verify', icon: <ShieldCheck className="w-4 h-4" />, badge: stats?.unverifiedPayments },
  ];

  return (
    <div className="min-h-screen bg-stone-50">
      <header className="bg-white border-b border-stone-200 sticky top-0 z-30">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 py-3 flex items-center justify-between gap-3">
          <div>
            <h1 className="text-lg font-extrabold text-stone-900">{PROPERTY_DETAILS.name} · Front Desk</h1>
            <p className="text-[11px] text-stone-500">{dashboard ? `Today: ${dashboard.date} (IST)` : 'Loading...'}</p>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setWalkInOpen(true)}
              disabled={!dashboard}
              className="px-3.5 py-2 rounded-lg bg-amber-600 hover:bg-amber-700 disabled:opacity-50 text-white text-xs font-bold flex items-center space-x-1.5"
            >
              <UserPlus className="w-4 h-4" />
              <span>Walk-in</span>
            </button>
            <button type="button" onClick={() => load()} aria-label="Refresh" className="p-2 rounded-lg border border-stone-300 hover:bg-stone-100">
              <RefreshCw className={`w-4 h-4 text-stone-600 ${loading ? 'animate-spin' : ''}`} />
            </button>
            <button
              type="button"
              onClick={() => {
                staffLogout();
                setDashboard(null);
                setLockNotice(null);
                setSignedIn(false);
              }}
              className="px-3 py-2 rounded-lg border border-stone-300 hover:bg-stone-100 text-xs font-semibold text-stone-700 flex items-center space-x-1.5"
            >
              <SignOutIcon className="w-4 h-4" />
              <span>Lock</span>
            </button>
          </div>
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-4 sm:px-6 py-6 space-y-6">
        {error && <ErrorBanner message={error} onRetry={() => load()} retrying={loading} />}
        {notice && (
          <p role="status" className="text-xs font-semibold text-emerald-800 bg-emerald-50 border border-emerald-200 rounded-xl px-4 py-3">
            {notice}
          </p>
        )}

        {stats && (
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
            <StatCard label="Rooms occupied" value={`${stats.roomsOccupied} / ${stats.totalRooms}`} hint={`${stats.roomsReserved} reserved · ${stats.roomsAvailable} free${stats.roomsMaintenance ? ` · ${stats.roomsMaintenance} blocked` : ''}`} tone="text-purple-700" />
            <StatCard label="Arrivals today" value={stats.arrivalsToday} hint={`${dashboard?.arrivals.length ?? 0} waiting to check in`} tone="text-sky-700" />
            <StatCard label="Departures today" value={stats.departuresToday} hint="checked-in guests leaving" tone="text-stone-900" />
            <StatCard label="Pending desk dues" value={formatRupees(stats.pendingDeskDuesAmount)} hint={`${stats.pendingDeskDues} ${stats.pendingDeskDues === 1 ? 'booking' : 'bookings'} owe money`} tone="text-amber-700" />
          </div>
        )}

        <nav className="flex flex-wrap gap-2" aria-label="Sections">
          {tabs.map((t) => (
            <button
              key={t.id}
              type="button"
              onClick={() => setTab(t.id)}
              aria-current={tab === t.id ? 'page' : undefined}
              className={`px-4 py-2 rounded-full text-xs font-bold flex items-center space-x-1.5 border transition-colors ${
                tab === t.id ? 'bg-stone-900 text-white border-stone-900' : 'bg-white text-stone-700 border-stone-300 hover:bg-stone-100'
              }`}
            >
              {t.icon}
              <span>{t.label}</span>
              {t.badge ? <span className="ml-1 px-1.5 py-0.5 rounded-full bg-rose-600 text-white text-[10px]">{t.badge}</span> : null}
            </button>
          ))}
        </nav>

        {!dashboard && loading && <p className="text-sm text-stone-500">Loading the front-desk board...</p>}

        {dashboard && tab === 'operations' && <StaffOperations dashboard={dashboard} actions={actions} />}
        {dashboard && tab === 'rooms' && <RoomGrid rooms={dashboard.rooms} onSelect={setSelectedRoom} />}
        {dashboard && tab === 'payments' && <StaffPayments dashboard={dashboard} actions={actions} />}
      </main>

      {dashboard && selectedRoom && (
        <RoomActionModal
          room={selectedRoom}
          today={dashboard.date}
          onClose={() => setSelectedRoom(null)}
          onChanged={(message) => {
            flash(message);
            load(true);
          }}
          onSessionExpired={() => {
            setSelectedRoom(null);
            sessionExpired();
          }}
        />
      )}

      {dashboard && walkInOpen && (
        <WalkInModal
          rooms={dashboard.rooms}
          today={dashboard.date}
          onClose={() => setWalkInOpen(false)}
          onCreated={(b) => {
            flash(`Walk-in booked: Room ${b.roomNumber} for ${b.guestName}.`);
            load(true);
          }}
          onSessionExpired={() => {
            setWalkInOpen(false);
            sessionExpired();
          }}
        />
      )}
    </div>
  );
};

import React, { useState } from 'react';
import { X, Wrench, Loader2, RefreshCw } from 'lucide-react';
import { StaffRoomStatus } from '../../types';
import { staffBlockRoom, staffUnblockRoom } from '../../services/staffApi';
import { addDays, nightsBetween } from '../../utils/dates';
import { ErrorBanner } from '../ErrorBanner';
import { ROOM_STATUS_STYLE, errorMessage, isSessionError, roomTypeLabel } from './staffUtils';

interface RoomActionModalProps {
  room: StaffRoomStatus;
  today: string;
  onClose: () => void;
  /** Called after a successful block / unblock so the dashboard reloads. */
  onChanged: (message: string) => void;
  onSessionExpired: () => void;
}

/**
 * Block a room for maintenance (start / end night, both inclusive) or return it to service. The server
 * refuses to block nights that already have a booking; its message is shown as-is.
 */
export const RoomActionModal: React.FC<RoomActionModalProps> = ({ room, today, onClose, onChanged, onSessionExpired }) => {
  const [startDate, setStartDate] = useState(today);
  const [endDate, setEndDate] = useState(today);
  const [busy, setBusy] = useState<'block' | 'unblock' | null>(null);
  const [error, setError] = useState<string | null>(null);

  const style = ROOM_STATUS_STYLE[room.status];
  const nights = nightsBetween(startDate, endDate) + 1;
  const validRange = !!startDate && !!endDate && nights >= 1 && startDate >= today;

  const run = async (kind: 'block' | 'unblock') => {
    setBusy(kind);
    setError(null);
    try {
      const res =
        kind === 'block'
          ? await staffBlockRoom(room.roomNumber, startDate, endDate)
          : await staffUnblockRoom(room.roomNumber);
      onChanged(res.message);
      onClose();
    } catch (err) {
      if (isSessionError(err)) {
        onSessionExpired();
        return;
      }
      setError(errorMessage(err));
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="fixed inset-0 z-50 bg-stone-900/50 flex items-end sm:items-center justify-center p-4" role="dialog" aria-modal="true" aria-label={`Room ${room.roomNumber}`}>
      <div className="w-full max-w-md bg-white rounded-3xl shadow-2xl p-6 space-y-5 max-h-[90vh] overflow-y-auto">
        <div className="flex items-start justify-between">
          <div>
            <h2 className="text-xl font-extrabold text-stone-900">Room {room.roomNumber}</h2>
            <p className="text-xs text-stone-500">
              Floor {room.floor} · {roomTypeLabel(room.type)}
            </p>
          </div>
          <button type="button" onClick={onClose} aria-label="Close" className="p-1.5 rounded-lg hover:bg-stone-100">
            <X className="w-5 h-5 text-stone-500" />
          </button>
        </div>

        <div className={`rounded-xl border p-3 text-xs space-y-1 ${style.card.split(' ').slice(0, 2).join(' ')}`}>
          <p className="font-bold">Today: {style.label}</p>
          {room.guestName && (
            <p>
              {room.guestName} · {room.checkInDate} to {room.checkOutDate}
            </p>
          )}
        </div>

        {error && <ErrorBanner message={error} />}

        {room.status === 'MAINTENANCE' && (
          <button
            type="button"
            disabled={busy !== null}
            onClick={() => run('unblock')}
            className="w-full py-3 rounded-xl bg-emerald-600 hover:bg-emerald-700 disabled:opacity-50 text-white font-bold text-sm flex items-center justify-center space-x-2"
          >
            {busy === 'unblock' ? <Loader2 className="w-4 h-4 animate-spin" /> : <RefreshCw className="w-4 h-4" />}
            <span>Return to service (unblock)</span>
          </button>
        )}

        <div className="space-y-3 border-t border-stone-200 pt-4">
          <h3 className="text-sm font-bold text-stone-800 flex items-center space-x-2">
            <Wrench className="w-4 h-4 text-amber-600" />
            <span>Block for maintenance</span>
          </h3>
          <p className="text-[11px] text-stone-500">
            The public cannot book the room on these nights. Nights that already have a booking cannot be blocked.
          </p>
          <div className="grid grid-cols-2 gap-3">
            <label className="text-xs font-semibold text-stone-700 space-y-1">
              <span>First night</span>
              <input
                type="date"
                min={today}
                value={startDate}
                onChange={(e) => {
                  setStartDate(e.target.value);
                  if (e.target.value > endDate) setEndDate(e.target.value);
                }}
                className="w-full px-3 py-2 rounded-lg border border-stone-300 text-sm"
              />
            </label>
            <label className="text-xs font-semibold text-stone-700 space-y-1">
              <span>Last night</span>
              <input
                type="date"
                min={startDate || today}
                max={startDate ? addDays(startDate, 89) : undefined}
                value={endDate}
                onChange={(e) => setEndDate(e.target.value)}
                className="w-full px-3 py-2 rounded-lg border border-stone-300 text-sm"
              />
            </label>
          </div>
          <p className="text-[11px] text-stone-500">{validRange ? `${nights} ${nights === 1 ? 'night' : 'nights'} will be blocked.` : 'Choose a valid range.'}</p>
          <button
            type="button"
            disabled={busy !== null || !validRange}
            onClick={() => run('block')}
            className="w-full py-3 rounded-xl bg-amber-600 hover:bg-amber-700 disabled:opacity-50 text-white font-bold text-sm flex items-center justify-center space-x-2"
          >
            {busy === 'block' ? <Loader2 className="w-4 h-4 animate-spin" /> : <Wrench className="w-4 h-4" />}
            <span>Block room</span>
          </button>
        </div>
      </div>
    </div>
  );
};

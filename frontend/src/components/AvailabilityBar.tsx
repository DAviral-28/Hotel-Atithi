import React from 'react';
import { Calendar, Filter, Clock, Search, Layers } from 'lucide-react';
import { RoomType } from '../types';
import { MAX_ADVANCE_DAYS, MAX_STAY_NIGHTS, addDays, todayIST } from '../utils/dates';

interface AvailabilityBarProps {
  checkInDate: string;
  checkOutDate: string;
  onCheckInChange: (val: string) => void;
  onCheckOutChange: (val: string) => void;
  selectedType: RoomType | 'ALL';
  onTypeChange: (type: RoomType | 'ALL') => void;
  selectedFloor: number | 'ALL';
  onFloorChange: (floor: number | 'ALL') => void;
  onSearch: () => void;
  isLoading: boolean;
  /** null while unknown (loading, or the last availability check failed) */
  totalAvailable: number | null;
}

export const AvailabilityBar: React.FC<AvailabilityBarProps> = ({
  checkInDate,
  checkOutDate,
  onCheckInChange,
  onCheckOutChange,
  selectedType,
  onTypeChange,
  selectedFloor,
  onFloorChange,
  onSearch,
  isLoading,
  totalAvailable,
}) => {
  const today = todayIST();
  const latestCheckIn = addDays(today, MAX_ADVANCE_DAYS);
  const earliestCheckOut = addDays(checkInDate || today, 1);
  const latestCheckOut = addDays(checkInDate || today, MAX_STAY_NIGHTS);

  return (
    <div className="relative bg-zinc-900/80 backdrop-blur-xl border border-white/10 shadow-2xl rounded-2xl p-4 sm:p-5 space-y-5">
      {/* Date Selectors & Search Action */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-12 gap-4 items-end">
        {/* Check-In */}
        <div className="lg:col-span-3 space-y-1.5">
          <label className="text-xs font-bold text-zinc-400 uppercase tracking-wider flex items-center space-x-1.5">
            <Calendar className="w-3.5 h-3.5 text-cyan-400" />
            <span>Check-in Date</span>
          </label>
          <input
            type="date"
            min={today}
            max={latestCheckIn}
            value={checkInDate}
            onChange={(e) => onCheckInChange(e.target.value)}
            className="w-full bg-white/5 border border-white/10 rounded-xl px-3.5 py-2.5 text-sm text-zinc-50 font-medium focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400/50 focus:bg-white/10 transition-all [color-scheme:dark]"
          />
        </div>

        {/* Check-Out */}
        <div className="lg:col-span-3 space-y-1.5">
          <label className="text-xs font-bold text-zinc-400 uppercase tracking-wider flex items-center space-x-1.5">
            <Calendar className="w-3.5 h-3.5 text-cyan-400" />
            <span>Check-out Date</span>
          </label>
          <input
            type="date"
            min={earliestCheckOut}
            max={latestCheckOut}
            value={checkOutDate}
            onChange={(e) => onCheckOutChange(e.target.value)}
            className="w-full bg-white/5 border border-white/10 rounded-xl px-3.5 py-2.5 text-sm text-zinc-50 font-medium focus:outline-none focus:ring-2 focus:ring-cyan-400/70 focus:border-cyan-400/50 focus:bg-white/10 transition-all [color-scheme:dark]"
          />
        </div>

        {/* 12:00 PM Checkout Policy Badge */}
        <div className="lg:col-span-3">
          <div className="flex items-center space-x-2.5 px-3.5 py-2.5 rounded-xl bg-white/5 border border-white/10 text-xs text-cyan-200">
            <Clock className="w-4 h-4 text-cyan-400 shrink-0" />
            <div>
              <span className="font-bold block text-zinc-100">Check-in / Out: 12:00 PM</span>
              <span className="text-zinc-500 text-[11px]">Standard hotel policy</span>
            </div>
          </div>
        </div>

        {/* Search CTA */}
        <div className="lg:col-span-3">
          <button
            onClick={onSearch}
            disabled={isLoading}
            className="w-full h-11 bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white font-black rounded-xl flex items-center justify-center space-x-2 shadow-lg shadow-cyan-500/20 transition-all active:scale-[0.98] disabled:opacity-50"
          >
            {isLoading ? (
              <div className="w-5 h-5 border-2 border-white border-t-transparent rounded-full animate-spin" />
            ) : (
              <>
                <Search className="w-4 h-4 stroke-[2.5]" />
                <span>Check Live Availability</span>
              </>
            )}
          </button>
        </div>
      </div>

      {/* Filter Tabs */}
      <div className="pt-4 border-t border-white/10 flex flex-wrap items-center justify-between gap-3 text-xs">
        {/* Category Filters */}
        <div className="flex items-center space-x-1.5 overflow-x-auto pb-1 sm:pb-0">
          <span className="text-zinc-500 font-semibold flex items-center mr-1">
            <Filter className="w-3.5 h-3.5 mr-1 text-zinc-500" /> Category:
          </span>
          {(
            [
              { id: 'ALL', label: 'All Rooms (32)' },
              { id: 'DELUXE', label: 'Deluxe (₹600 + 5% GST)' },
              { id: 'SUPER_DELUXE', label: 'Super Deluxe (₹700 + 5% GST)' },
              { id: 'AC', label: 'AC Rooms (₹1,100 + 5% GST)' },
            ] as const
          ).map((cat) => (
            <button
              key={cat.id}
              onClick={() => onTypeChange(cat.id)}
              className={`px-3 py-1.5 rounded-full font-semibold transition-all whitespace-nowrap ${
                selectedType === cat.id
                  ? 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/40 font-bold shadow-md shadow-cyan-500/10'
                  : 'bg-white/5 hover:bg-white/10 text-zinc-300 border border-white/10'
              }`}
            >
              {cat.label}
            </button>
          ))}
        </div>

        {/* Floor Filter & Availability Indicator */}
        <div className="flex items-center space-x-3">
          <div className="flex items-center space-x-1 bg-white/5 border border-white/10 p-1 rounded-full">
            <Layers className="w-3.5 h-3.5 text-zinc-500 ml-1.5 mr-1" />
            {(
              [
                { id: 'ALL', label: 'All Floors' },
                { id: 1, label: '1st Floor (101-116)' },
                { id: 2, label: '2nd Floor (201-216)' },
              ] as const
            ).map((fl) => (
              <button
                key={fl.id}
                onClick={() => onFloorChange(fl.id)}
                className={`px-2.5 py-1 rounded-full text-xs font-semibold transition-all whitespace-nowrap ${
                  selectedFloor === fl.id
                    ? 'bg-white/15 text-zinc-50 font-bold'
                    : 'text-zinc-500 hover:text-zinc-200'
                }`}
              >
                {fl.label}
              </button>
            ))}
          </div>

          {totalAvailable === null ? (
            <div className="px-3 py-1.5 rounded-full bg-white/5 border border-white/10 text-zinc-400 font-bold text-xs flex items-center space-x-1.5">
              <span className="w-2 h-2 rounded-full bg-zinc-500" />
              <span>{isLoading ? 'Checking…' : 'Availability unknown'}</span>
            </div>
          ) : (
            <div className="px-3 py-1.5 rounded-full bg-emerald-500/10 border border-emerald-400/30 text-emerald-300 font-bold text-xs flex items-center space-x-1.5">
              <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse shadow-[0_0_8px_rgba(52,211,153,0.8)]" />
              <span>{totalAvailable} Rooms Available for Selected Dates</span>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

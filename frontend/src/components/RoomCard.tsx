import React from 'react';
import { Bed, Users, Wind, CheckCircle2, Lock, ArrowRight, HelpCircle, Camera, Droplets, BatteryCharging, Sofa } from 'lucide-react';
import { Room } from '../types';

export type RoomAvailabilityState = 'available' | 'unavailable' | 'unknown';

interface RoomCardProps {
  room: Room;
  /** 'unknown' = we could not (yet) confirm live availability; never presented as available. */
  availability: RoomAvailabilityState;
  onBookNow: (room: Room) => void;
  /** Opens the hotel photo gallery focused on this room's photos. */
  onViewPhotos: (room: Room) => void;
}

export const RoomCard: React.FC<RoomCardProps> = ({ room, availability, onBookNow, onViewPhotos }) => {
  const isAvailable = availability === 'available';
  const isUnknown = availability === 'unknown';
  const isSingleBed = room.bedType === 'SINGLE';
  const gstAmount = room.baseRate * (room.taxRatePercent / 100);
  const totalWithGst = room.baseRate + gstAmount;
  // Card cover: a polished web stock photo, purely for a consistent, aesthetic listing grid.
  const thumbnail = room.coverImage || room.images[0];
  // "View Photos" always opens the gallery on the room's real, actual property photos (room.images) —
  // never the cover photo — so the count shown here matches what the gallery/lightbox will display.
  const photoCount = room.images.length;

  return (
    <div
      className={`hospitality-card group flex flex-col justify-between relative overflow-hidden ${
        !isAvailable ? 'opacity-70' : ''
      }`}
    >
      {/* Photo Thumbnail */}
      {thumbnail && (
        <button
          type="button"
          onClick={() => onViewPhotos(room)}
          className="group relative w-full aspect-[16/10] overflow-hidden bg-zinc-800"
          aria-label={`View photos of Room ${room.roomNumber}`}
        >
          <img
            src={thumbnail}
            alt={`Room ${room.roomNumber}`}
            className="w-full h-full object-cover transition-transform duration-500 group-hover:scale-105"
            loading="lazy"
          />
          <div className="absolute inset-0 bg-gradient-to-t from-black/70 via-black/5 to-transparent" />
          <span className="absolute bottom-2.5 right-2.5 flex items-center space-x-1 px-2 py-1 rounded-lg bg-black/60 backdrop-blur-md border border-white/10 text-white text-[11px] font-bold">
            <Camera className="w-3 h-3" />
            <span>{photoCount} Photo{photoCount === 1 ? '' : 's'}</span>
          </span>
          <span
            className={`absolute top-2.5 left-2.5 text-[11px] font-bold px-2.5 py-1 rounded-lg border backdrop-blur-md shadow-sm ${
              room.type === 'AC'
                ? 'bg-sky-400/20 text-sky-200 border-sky-400/30'
                : room.type === 'SUPER_DELUXE'
                ? 'bg-indigo-400/20 text-indigo-200 border-indigo-400/30'
                : 'bg-white/10 text-zinc-100 border-white/20'
            }`}
          >
            {room.type === 'AC' ? 'AC Room' : room.type === 'SUPER_DELUXE' ? 'Super Deluxe' : 'Deluxe'}
          </span>
        </button>
      )}

      <div className="p-5 sm:p-6 flex flex-col justify-between flex-1">
      {/* Top Details & Badges */}
      <div>
        <div className="flex items-start justify-between mb-3">
          <div>
            <div className="flex items-center space-x-2.5">
              <span className="text-2xl font-black text-zinc-50 tracking-tight">
                Room {room.roomNumber}
              </span>
              <span className="text-xs font-semibold px-2.5 py-0.5 rounded-full bg-white/5 text-zinc-400 border border-white/10">
                {room.floor === 1 ? '1st Floor' : '2nd Floor'}
              </span>
            </div>
            <p className="text-xs text-zinc-500 mt-1">
              {room.type === 'DELUXE' && 'Comfortable deluxe room with modern hospitality amenities'}
              {room.type === 'SUPER_DELUXE' && 'Spacious super deluxe room with premium comfort'}
              {room.type === 'AC' && 'Climate-controlled air-conditioned room with power backup'}
            </p>
          </div>

          {/* Availability Status Badge */}
          {isAvailable ? (
            <span className="flex items-center space-x-1 px-2.5 py-1 rounded-full text-xs font-bold bg-emerald-400/10 text-emerald-300 border border-emerald-400/30">
              <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />
              <span>Available</span>
            </span>
          ) : isUnknown ? (
            <span className="flex items-center space-x-1 px-2.5 py-1 rounded-full text-xs font-bold bg-white/5 text-zinc-400 border border-white/10">
              <HelpCircle className="w-3.5 h-3.5 text-zinc-500" />
              <span>Not checked</span>
            </span>
          ) : (
            <span className="flex items-center space-x-1 px-2.5 py-1 rounded-full text-xs font-bold bg-rose-400/10 text-rose-300 border border-rose-400/30">
              <Lock className="w-3.5 h-3.5 text-rose-400" />
              <span>Reserved</span>
            </span>
          )}
        </div>

        {/* Badges: Category, Bed Type, Occupancy */}
        <div className="flex flex-wrap gap-2 mb-4">
          {/* Category Badge */}
          <span
            className={`text-xs font-bold px-2.5 py-1 rounded-lg border ${
              room.type === 'AC'
                ? 'bg-sky-400/10 text-sky-300 border-sky-400/25'
                : room.type === 'SUPER_DELUXE'
                ? 'bg-indigo-400/10 text-indigo-300 border-indigo-400/25'
                : 'bg-white/5 text-zinc-300 border-white/10'
            }`}
          >
            {room.type === 'AC' ? 'AC Room' : room.type === 'SUPER_DELUXE' ? 'Super Deluxe' : 'Deluxe'}
          </span>

          {/* Bed Configuration Badge: Explicit Single Bed on 104 & 204 */}
          <span
            className={`text-xs font-bold px-2.5 py-1 rounded-lg border flex items-center space-x-1.5 ${
              isSingleBed
                ? 'bg-cyan-400/10 text-cyan-200 border-cyan-400/25 shadow-sm'
                : 'bg-white/5 text-zinc-300 border-white/10'
            }`}
          >
            <Bed className="w-3.5 h-3.5 text-cyan-400" />
            <span>{isSingleBed ? 'Single Bed (Max 2)' : 'Double Bed (Max 3)'}</span>
          </span>

          {/* Max Guests */}
          <span className="text-xs px-2.5 py-1 rounded-lg bg-white/5 text-zinc-400 border border-white/10 flex items-center space-x-1">
            <Users className="w-3.5 h-3.5 text-zinc-500" />
            <span>Base {room.baseOccupancy} / Max {room.maxOccupancy}</span>
          </span>

          {room.type === 'AC' && (
            <span className="text-xs px-2.5 py-1 rounded-lg bg-sky-400/10 text-sky-300 border border-sky-400/25 flex items-center space-x-1 font-medium">
              <Wind className="w-3.5 h-3.5 text-sky-400" />
              <span>Full Air Conditioning</span>
            </span>
          )}
        </div>

        {/* Amenity Chips */}
        <div className="flex flex-wrap gap-1.5 mb-4">
          <span className="text-[11px] px-2 py-1 rounded-lg bg-white/5 text-zinc-400 border border-white/10 flex items-center space-x-1">
            <Droplets className="w-3 h-3 text-cyan-400" />
            <span>Indian &amp; Western Bath</span>
          </span>
          <span className="text-[11px] px-2 py-1 rounded-lg bg-white/5 text-zinc-400 border border-white/10 flex items-center space-x-1">
            <Droplets className="w-3 h-3 text-cyan-400" />
            <span>24/7 Hot Water</span>
          </span>
          <span className="text-[11px] px-2 py-1 rounded-lg bg-white/5 text-zinc-400 border border-white/10 flex items-center space-x-1">
            <BatteryCharging className="w-3 h-3 text-cyan-400" />
            <span>24/7 Power Backup</span>
          </span>
          <span className="text-[11px] px-2 py-1 rounded-lg bg-white/5 text-zinc-400 border border-white/10 flex items-center space-x-1">
            <Sofa className="w-3 h-3 text-cyan-400" />
            <span>Sofa Seating</span>
          </span>
        </div>
      </div>

      {/* Pricing Breakdown & Action */}
      <div className="pt-4 border-t border-white/10 mt-2">
        <div className="flex items-baseline justify-between mb-3.5">
          <div>
            <div className="flex items-baseline space-x-1">
              <span className="text-2xl font-black gold-text">₹{room.baseRate}</span>
              <span className="text-xs text-zinc-500">/ night</span>
            </div>
            <p className="text-xs text-cyan-300/90 font-semibold">
              + 5% GST (₹{gstAmount.toFixed(0)}) = ₹{totalWithGst.toFixed(0)} total
            </p>
          </div>

          <div className="text-right">
            <span className="text-[11px] text-zinc-500 block">Extra Guest</span>
            <span className="text-xs font-bold text-zinc-300">+₹{room.extraPersonRate} / night</span>
          </div>
        </div>

        <div className="flex gap-2">
          <button
            onClick={() => onViewPhotos(room)}
            className="shrink-0 px-3.5 py-2.5 rounded-xl font-bold text-sm bg-white/5 hover:bg-white/10 border border-white/10 text-zinc-100 transition-all flex items-center justify-center space-x-1.5"
          >
            <Camera className="w-4 h-4 text-cyan-400" />
            <span className="hidden sm:inline">View Photos ({photoCount})</span>
          </button>

          <button
            onClick={() => onBookNow(room)}
            disabled={!isAvailable}
            className={`flex-1 py-2.5 rounded-xl font-black text-sm transition-all flex items-center justify-center space-x-2 ${
              isAvailable
                ? 'bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white shadow-lg shadow-cyan-500/20 active:scale-95'
                : 'bg-white/5 text-zinc-500 cursor-not-allowed border border-white/10'
            }`}
          >
            {isAvailable ? (
              <>
                <span>Book Room</span>
                <ArrowRight className="w-4 h-4 stroke-[2.5]" />
              </>
            ) : isUnknown ? (
              <span>Check availability first</span>
            ) : (
              <span>Unavailable for Selected Dates</span>
            )}
          </button>
        </div>
      </div>
      </div>
    </div>
  );
};

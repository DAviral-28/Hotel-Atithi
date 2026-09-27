import React from 'react';
import { StaffRoomStatus } from '../../types';
import { ROOM_STATUS_STYLE, roomTypeLabel } from './staffUtils';

const LEGEND: Array<keyof typeof ROOM_STATUS_STYLE> = ['AVAILABLE', 'RESERVED', 'IN_HOUSE', 'MAINTENANCE'];

/** All 32 rooms, Floor 1 and Floor 2, colour-coded. Tapping a room opens its actions (block / unblock). */
export const RoomGrid: React.FC<{ rooms: StaffRoomStatus[]; onSelect: (room: StaffRoomStatus) => void }> = ({ rooms, onSelect }) => {
  const floors = [1, 2];
  return (
    <div className="space-y-6">
      <div className="flex flex-wrap gap-3 text-xs">
        {LEGEND.map((s) => (
          <span key={s} className="inline-flex items-center space-x-1.5 text-stone-600">
            <span className={`w-3 h-3 rounded-full ${ROOM_STATUS_STYLE[s].dot}`} />
            <span>{ROOM_STATUS_STYLE[s].label}</span>
          </span>
        ))}
      </div>

      {floors.map((floor) => {
        const list = rooms.filter((r) => r.floor === floor);
        return (
          <section key={floor} className="space-y-3">
            <h2 className="text-sm font-extrabold text-stone-800 uppercase tracking-wider">
              Floor {floor} <span className="text-stone-400 font-semibold normal-case">({floor}01 - {floor}16)</span>
            </h2>
            <div className="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-8 gap-3">
              {list.map((room) => {
                const style = ROOM_STATUS_STYLE[room.status];
                return (
                  <button
                    key={room.roomNumber}
                    type="button"
                    onClick={() => onSelect(room)}
                    aria-label={`Room ${room.roomNumber}, ${style.label}`}
                    className={`text-left rounded-2xl border-2 p-3 space-y-1 transition-colors ${style.card}`}
                  >
                    <div className="flex items-center justify-between">
                      <span className="text-lg font-extrabold">{room.roomNumber}</span>
                      <span className={`w-2.5 h-2.5 rounded-full ${style.dot}`} />
                    </div>
                    <p className="text-[11px] font-semibold opacity-80">{roomTypeLabel(room.type)}</p>
                    <p className={`inline-block px-1.5 py-0.5 rounded text-[10px] font-bold ${style.chip}`}>{style.label}</p>
                    {room.guestName && <p className="text-[11px] truncate opacity-80">{room.guestName}</p>}
                  </button>
                );
              })}
            </div>
          </section>
        );
      })}
    </div>
  );
};

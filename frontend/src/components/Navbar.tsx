import React from 'react';
import { BedDouble, Camera, CalendarClock, MapPin, Phone, TrainFront } from 'lucide-react';
import { PROPERTY_DETAILS } from '../data/roomDirectory';

interface NavbarProps {
  onOpenManageModal: () => void;
  onOpenGallery: () => void;
}

export const Navbar: React.FC<NavbarProps> = ({ onOpenManageModal, onOpenGallery }) => {
  return (
    <header className="sticky top-0 z-40 bg-zinc-950/80 backdrop-blur-md border-b border-white/10">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="h-20 flex items-center justify-between">
          {/* Brand Logo & Name */}
          <div className="flex items-center space-x-3.5">
            <div className="w-11 h-11 rounded-xl bg-gradient-to-br from-cyan-400 to-blue-600 flex items-center justify-center shadow-lg shadow-cyan-500/20 text-white">
              <BedDouble className="w-6 h-6 stroke-[2]" />
            </div>
            <div>
              <div className="flex items-center space-x-2.5">
                <h1 className="text-xl font-bold tracking-tight text-zinc-50">
                  {PROPERTY_DETAILS.name}
                </h1>
                <span className="hidden sm:inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold bg-white/5 text-cyan-300 border border-cyan-500/20">
                  Banda
                </span>
              </div>
              <p className="text-xs text-zinc-400 flex items-center space-x-1 mt-0.5">
                <MapPin className="w-3 h-3 text-cyan-400 inline shrink-0" />
                <span>Station Road, Gular Naka • Near Railway Station</span>
              </p>
            </div>
          </div>

          {/* Action Buttons & Phone */}
          <div className="flex items-center space-x-2.5 sm:space-x-3">
            <a
              href={`tel:${PROPERTY_DETAILS.phoneTel}`}
              className="hidden lg:flex items-center space-x-2 px-3.5 py-2 rounded-xl bg-white/5 hover:bg-white/10 text-cyan-200 border border-white/10 text-xs font-semibold transition-colors"
            >
              <Phone className="w-3.5 h-3.5 text-cyan-400" />
              <span>{PROPERTY_DETAILS.phone}</span>
            </a>

            <button
              onClick={onOpenGallery}
              className="flex items-center space-x-2 px-3.5 py-2 rounded-xl bg-white/5 hover:bg-white/10 border border-white/10 text-zinc-100 text-xs sm:text-sm font-semibold backdrop-blur-md transition-all"
            >
              <Camera className="w-4 h-4 text-cyan-400" />
              <span className="hidden sm:inline">View Hotel Photos</span>
              <span className="sm:hidden">Photos</span>
            </button>

            <button
              onClick={onOpenManageModal}
              className="flex items-center space-x-2 px-3.5 sm:px-4 py-2 rounded-xl bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white text-xs sm:text-sm font-bold transition-all shadow-lg shadow-cyan-500/20 active:scale-95"
            >
              <CalendarClock className="w-4 h-4" />
              <span className="hidden sm:inline">Manage / Extend Stay</span>
              <span className="sm:hidden">Manage</span>
            </button>
          </div>
        </div>

        {/* Prominent landmark badge with an animated cyan pulse indicator */}
        <div className="pb-2.5 -mt-1">
          <span className="inline-flex items-center space-x-1.5 px-2.5 py-1 rounded-full bg-white/5 border border-cyan-500/25 text-cyan-300 text-[11px] font-bold">
            <span className="relative flex items-center justify-center w-2 h-2">
              <span className="absolute inline-flex h-full w-full rounded-full bg-cyan-400 gold-pulse-dot" />
              <span className="relative inline-flex rounded-full h-2 w-2 bg-cyan-400" />
            </span>
            <TrainFront className="w-3 h-3 text-cyan-400" />
            <span>{PROPERTY_DETAILS.amenities[0]}</span>
          </span>
        </div>
      </div>
    </header>
  );
};

import React, { useState, useEffect, useRef } from 'react';
import { Navbar } from './components/Navbar';
import { AvailabilityBar } from './components/AvailabilityBar';
import { RoomCard, RoomAvailabilityState } from './components/RoomCard';
import { BookingModal } from './components/BookingModal';
import { ConfirmationSlip } from './components/ConfirmationSlip';
import { ExtensionPortalModal } from './components/ExtensionPortalModal';
import { ErrorBanner } from './components/ErrorBanner';
import { HotelGalleryModal } from './components/HotelGalleryModal';
import { BookingResponse, GalleryImage, Room, RoomType } from './types';
import { fetchAvailability, fetchRooms } from './services/api';
import { PROPERTY_DETAILS, INITIAL_ROOMS, HOTEL_GALLERY } from './data/roomDirectory';
import { MAX_STAY_NIGHTS, addDays, nightsBetween, todayIST } from './utils/dates';
import { Wifi, PhoneCall, Zap, Droplets, Clock, MapPin, Camera, ArrowRight, TrainFront } from 'lucide-react';

export const App: React.FC = () => {
  // Default dates: Today and Tomorrow, as calendar dates in India (not UTC)
  const todayStr = todayIST();
  const tomorrowStr = addDays(todayStr, 1);

  const [checkInDate, setCheckInDate] = useState<string>(todayStr);
  const [checkOutDate, setCheckOutDate] = useState<string>(tomorrowStr);
  const [selectedType, setSelectedType] = useState<RoomType | 'ALL'>('ALL');
  const [selectedFloor, setSelectedFloor] = useState<number | 'ALL'>('ALL');

  // Room & Availability State (Initialized with 32-room catalog)
  const [rooms, setRooms] = useState<Room[]>(INITIAL_ROOMS);
  // null = availability not known (not checked yet, dates just changed, or the last check failed).
  // We never assume "available" without an answer from the server.
  const [availableRoomNumbers, setAvailableRoomNumbers] = useState<Set<number> | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [availabilityError, setAvailabilityError] = useState<string | null>(null);
  // Guards against an older, slower response overwriting a newer one.
  const latestSearchId = useRef(0);

  // Modals & Active booking
  const [selectedRoomForBooking, setSelectedRoomForBooking] = useState<Room | null>(null);
  // The booking whose slip / tax invoice is on screen, plus the token that lets the guest cancel it from there.
  const [slip, setSlip] = useState<{ booking: BookingResponse; manageToken: string | null } | null>(null);
  const [isManageModalOpen, setIsManageModalOpen] = useState<boolean>(false);

  // Hotel photo gallery: null = closed. focusImages set = opened focused on one room's photos.
  const [galleryFocus, setGalleryFocus] = useState<{ images: GalleryImage[]; title: string } | null>(null);
  const [isGalleryOpen, setIsGalleryOpen] = useState<boolean>(false);
  const roomGridRef = useRef<HTMLDivElement | null>(null);

  const openFullGallery = () => {
    setGalleryFocus(null);
    setIsGalleryOpen(true);
  };

  const openRoomGallery = (room: Room) => {
    const images = HOTEL_GALLERY.filter((g) => room.images.includes(g.src));
    setGalleryFocus({ images, title: `Room ${room.roomNumber} Photos` });
    setIsGalleryOpen(true);
  };

  const scrollToRooms = () => {
    roomGridRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  // Ask the server which rooms are free for the given dates. Errors are surfaced, never masked.
  const runAvailabilitySearch = async (inDate: string, outDate: string) => {
    const searchId = ++latestSearchId.current;
    setIsLoading(true);
    setAvailabilityError(null);
    try {
      // Always fetch every room type; the category/floor tabs filter client-side, so switching
      // tabs can never make rooms look "unavailable" just because they weren't in the response.
      const avail = await fetchAvailability(inDate, outDate);
      if (searchId !== latestSearchId.current) return;
      setAvailableRoomNumbers(new Set(avail.availableRooms.map((r) => r.roomNumber)));
    } catch (err: any) {
      if (searchId !== latestSearchId.current) return;
      setAvailableRoomNumbers(null);
      setAvailabilityError(err?.message || 'Unable to check availability right now. Please try again.');
    } finally {
      if (searchId === latestSearchId.current) setIsLoading(false);
    }
  };

  // Load Room Catalog + first availability check on mount
  useEffect(() => {
    async function loadCatalog() {
      const catalog = await fetchRooms();
      if (catalog && catalog.length > 0) {
        setRooms(catalog);
      }
      await runAvailabilitySearch(checkInDate, checkOutDate);
    }
    loadCatalog();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleSearchAvailability = () => runAvailabilitySearch(checkInDate, checkOutDate);

  // Any change to the dates invalidates the previous answer.
  const handleCheckInChange = (value: string) => {
    setCheckInDate(value);
    // Keep the stay valid (1..MAX_STAY_NIGHTS nights) as the check-in moves.
    const nights = nightsBetween(value, checkOutDate);
    if (Number.isNaN(nights) || nights < 1 || nights > MAX_STAY_NIGHTS) {
      setCheckOutDate(addDays(value, 1));
    }
    setAvailableRoomNumbers(null);
  };

  const handleCheckOutChange = (value: string) => {
    setCheckOutDate(value);
    setAvailableRoomNumbers(null);
  };

  const availabilityFor = (roomNumber: number): RoomAvailabilityState => {
    if (availableRoomNumbers === null) return 'unknown';
    return availableRoomNumbers.has(roomNumber) ? 'available' : 'unavailable';
  };

  // Filtered rooms display
  const displayedRooms = rooms.filter((r) => {
    const matchesType = selectedType === 'ALL' || r.type === selectedType;
    const matchesFloor = selectedFloor === 'ALL' || r.floor === selectedFloor;
    return matchesType && matchesFloor;
  });

  return (
    <div className="relative min-h-screen bg-zinc-950 text-zinc-50 flex flex-col font-sans selection:bg-cyan-500/40 selection:text-white overflow-x-hidden">
      {/* Atmospheric ambient light orbs behind the whole page */}
      <div className="ambient-orb w-[36rem] h-[36rem] bg-cyan-500/10 -top-40 -right-40" />
      <div className="ambient-orb w-[30rem] h-[30rem] bg-emerald-500/5 top-[40rem] -left-40" />
      <div className="fixed inset-0 bg-gradient-to-b from-transparent via-transparent to-[#0B0F17] pointer-events-none" />

      {/* Navbar */}
      <Navbar onOpenManageModal={() => setIsManageModalOpen(true)} onOpenGallery={openFullGallery} />

      {/* Main Content */}
      <main className="relative z-10 flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-8 space-y-8">
        {/* If Confirmation Slip Active */}
        {slip ? (
          <ConfirmationSlip
            booking={slip.booking}
            manageToken={slip.manageToken}
            onReset={() => {
              setSlip(null);
              handleSearchAvailability();
            }}
            onBookingChange={(updated) => {
              setSlip((prev) => (prev ? { ...prev, booking: updated } : prev));
              handleSearchAvailability(); // a cancelled room is bookable again
            }}
          />
        ) : (
          <>
            {/* Hero Section: cinematic full-bleed backdrop with a dark vignette */}
            <div className="relative rounded-3xl overflow-hidden shadow-2xl min-h-[460px] sm:min-h-[520px] flex items-end border border-white/10">
              <img
                src="/images/property/exterior-1.jpg"
                alt={`${PROPERTY_DETAILS.name} exterior`}
                className="absolute inset-0 w-full h-full object-cover"
              />
              <div className="absolute inset-0 bg-gradient-to-t from-zinc-950 via-zinc-950/70 to-transparent" />
              <div className="absolute inset-0 bg-gradient-to-r from-zinc-950/85 via-zinc-950/20 to-transparent" />
              <div className="ambient-orb w-72 h-72 bg-cyan-500/20 top-10 right-10" />

              <div className="relative z-10 max-w-2xl space-y-4 p-8 sm:p-12 text-white">
                {/* Floating illuminated badge with an animated cyan pulse indicator */}
                <div className="inline-flex items-center space-x-2 px-3 py-1.5 rounded-full bg-zinc-950/60 backdrop-blur-md border border-cyan-500/30 text-cyan-300 text-xs font-black shadow-lg">
                  <span className="relative flex items-center justify-center w-2 h-2">
                    <span className="absolute inline-flex h-full w-full rounded-full bg-cyan-400 gold-pulse-dot" />
                    <span className="relative inline-flex rounded-full h-2 w-2 bg-cyan-400" />
                  </span>
                  <TrainFront className="w-3.5 h-3.5" />
                  <span>{PROPERTY_DETAILS.amenities[0]}</span>
                </div>

                {/* Hero Title with an ice-cyan shimmer highlight on key words */}
                <h1 className="text-3xl sm:text-5xl font-black tracking-tight leading-tight drop-shadow-lg">
                  <span className="text-zinc-50">Experience </span>
                  <span className="gold-text">Warm Comfort</span>
                  <span className="text-zinc-50"> at {PROPERTY_DETAILS.name}</span>
                </h1>

                {/* Subtitle */}
                <p className="text-sm sm:text-base text-zinc-300 leading-relaxed font-normal max-w-xl">
                  A boutique stay on Station Road, Banda — reserve your room in seconds with instant 10-minute hold
                  locking, and settle the bill at check-in.
                </p>

                {/* Feature Highlights Bar */}
                <div className="pt-1 flex flex-wrap items-center gap-x-5 gap-y-2 text-xs font-semibold text-cyan-100/90">
                  <span className="flex items-center space-x-1.5">
                    <Wifi className="w-4 h-4 text-cyan-400" />
                    <span>Free High-Speed Wi-Fi</span>
                  </span>
                  <span className="flex items-center space-x-1.5">
                    <Droplets className="w-4 h-4 text-cyan-400" />
                    <span>24/7 Hot Water</span>
                  </span>
                  <span className="flex items-center space-x-1.5">
                    <Zap className="w-4 h-4 text-cyan-400" />
                    <span>100% Power Backup</span>
                  </span>
                  <span className="flex items-center space-x-1.5">
                    <PhoneCall className="w-4 h-4 text-cyan-400" />
                    <span>24/7 Front Desk</span>
                  </span>
                  <span className="flex items-center space-x-1.5">
                    <Clock className="w-4 h-4 text-cyan-400" />
                    <span>12:00 PM Checkout</span>
                  </span>
                </div>

                {/* Quick Action Buttons */}
                <div className="pt-3 flex flex-wrap items-center gap-3">
                  <button
                    onClick={scrollToRooms}
                    className="flex items-center space-x-2 px-5 py-2.5 rounded-xl bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white font-black text-sm shadow-lg shadow-cyan-500/30 transition-all active:scale-[0.98]"
                  >
                    <span>Book Your Stay</span>
                    <ArrowRight className="w-4 h-4 stroke-[2.5]" />
                  </button>
                  <button
                    onClick={openFullGallery}
                    className="flex items-center space-x-2 px-5 py-2.5 rounded-xl bg-white/10 hover:bg-white/20 backdrop-blur-md border border-white/20 text-white font-bold text-sm transition-all active:scale-[0.98]"
                  >
                    <Camera className="w-4 h-4" />
                    <span>Explore Photo Gallery ({HOTEL_GALLERY.length})</span>
                  </button>
                </div>
              </div>
            </div>

            {/* Availability Bar & Live Filter */}
            <AvailabilityBar
              checkInDate={checkInDate}
              checkOutDate={checkOutDate}
              onCheckInChange={handleCheckInChange}
              onCheckOutChange={handleCheckOutChange}
              selectedType={selectedType}
              onTypeChange={setSelectedType}
              selectedFloor={selectedFloor}
              onFloorChange={setSelectedFloor}
              onSearch={handleSearchAvailability}
              isLoading={isLoading}
              totalAvailable={availableRoomNumbers === null ? null : availableRoomNumbers.size}
            />

            {/* Availability error / retry state */}
            {availabilityError && (
              <ErrorBanner
                title="We couldn't check live availability."
                message={availabilityError}
                onRetry={handleSearchAvailability}
                retrying={isLoading}
              >
                <p>
                  You can also call the front desk on{' '}
                  <a href={`tel:${PROPERTY_DETAILS.phoneTel}`} className="font-bold underline">
                    {PROPERTY_DETAILS.phone}
                  </a>
                  .
                </p>
              </ErrorBanner>
            )}

            {/* Room Grid */}
            <div ref={roomGridRef} className="space-y-4">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 pb-2 border-b border-white/10">
                <div>
                  <h2 className="text-xl font-extrabold text-zinc-50">
                    {selectedType === 'ALL'
                      ? 'Room Directory'
                      : selectedType === 'DELUXE'
                      ? 'Deluxe Rooms'
                      : selectedType === 'SUPER_DELUXE'
                      ? 'Super Deluxe Rooms'
                      : 'AC Rooms'}
                    <span className="text-zinc-500 text-sm font-semibold ml-2">
                      ({displayedRooms.length} of 32 rooms)
                    </span>
                  </h2>
                  <p className="text-xs text-zinc-500 mt-0.5">
                    Select your room to lock it instantly for 10 minutes.
                  </p>
                </div>
                <div className="text-xs text-zinc-300 bg-white/5 px-3 py-1.5 rounded-lg border border-white/10 flex items-center space-x-1.5 self-start sm:self-auto">
                  <Clock className="w-3.5 h-3.5 text-cyan-400" />
                  <span>Check-in: <strong className="text-zinc-100">12:00 PM</strong> | Check-out: <strong className="text-zinc-100">12:00 PM</strong></span>
                </div>
              </div>

              {/* Room Cards Grid */}
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
                {displayedRooms.map((room) => (
                  <RoomCard
                    key={room.roomNumber}
                    room={room}
                    availability={availabilityFor(room.roomNumber)}
                    onBookNow={(r) => setSelectedRoomForBooking(r)}
                    onViewPhotos={openRoomGallery}
                  />
                ))}
              </div>
            </div>
          </>
        )}
      </main>

      {/* Booking Modal */}
      {selectedRoomForBooking && (
        <BookingModal
          room={selectedRoomForBooking}
          checkInDate={checkInDate}
          checkOutDate={checkOutDate}
          onClose={() => {
            setSelectedRoomForBooking(null);
            // A hold may have just been released (or expired): refresh so the grid is truthful.
            handleSearchAvailability();
          }}
          onSuccess={(booking) => {
            setSelectedRoomForBooking(null);
            setSlip({ booking, manageToken: booking.manageToken ?? null });
            // Re-fetch availability after confirmation
            handleSearchAvailability();
          }}
        />
      )}

      {/* Extension & Management Portal Modal */}
      {isManageModalOpen && (
        <ExtensionPortalModal
          onClose={() => setIsManageModalOpen(false)}
          onViewSlip={(booking, manageToken) => {
            setIsManageModalOpen(false);
            setSlip({ booking, manageToken });
          }}
          onInventoryChanged={handleSearchAvailability}
        />
      )}

      {/* Hotel Photo Gallery */}
      {isGalleryOpen && (
        <HotelGalleryModal
          allImages={HOTEL_GALLERY}
          focusImages={galleryFocus?.images}
          focusTitle={galleryFocus?.title}
          onClose={() => {
            setIsGalleryOpen(false);
            setGalleryFocus(null);
          }}
        />
      )}

      {/* Hospitality Footer */}
      <footer className="relative z-10 border-t border-white/10 bg-zinc-950/60 backdrop-blur-md py-10 mt-16 text-xs text-zinc-400">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 space-y-6">
          <div className="grid grid-cols-1 md:grid-cols-3 gap-6 pb-6 border-b border-white/10">
            {/* Column 1: Property */}
            <div className="space-y-2">
              <h3 className="font-bold text-zinc-50 text-sm">{PROPERTY_DETAILS.name}</h3>
              <p className="text-zinc-400 leading-relaxed">
                {PROPERTY_DETAILS.address}
                <br />
                <span className="text-cyan-300 font-medium">Landmark: {PROPERTY_DETAILS.landmark}</span>
              </p>
              <a
                href={`https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(
                  `${PROPERTY_DETAILS.name} ${PROPERTY_DETAILS.address}`
                )}`}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center space-x-1.5 text-cyan-300 hover:text-cyan-200 font-semibold underline-offset-2 hover:underline"
              >
                <MapPin className="w-3.5 h-3.5" />
                <span>Get Directions on Google Maps</span>
              </a>
            </div>

            {/* Column 2: Inquiries & Help */}
            <div className="space-y-2">
              <h3 className="font-bold text-zinc-50 text-sm">24/7 Front Desk Inquiries</h3>
              <p className="text-zinc-400">
                Call: <a href={`tel:${PROPERTY_DETAILS.phoneTel}`} className="font-bold text-cyan-300 hover:underline">{PROPERTY_DETAILS.phone}</a>
              </p>
              <p className="text-zinc-400">
                Check-in: 12:00 PM | Check-out: 12:00 PM
              </p>
            </div>

            {/* Column 3: Amenities */}
            <div className="space-y-2">
              <h3 className="font-bold text-zinc-50 text-sm">Property Amenities</h3>
              <ul className="text-zinc-400 space-y-1">
                {PROPERTY_DETAILS.amenities.map((amenity) => (
                  <li key={amenity}>• {amenity}</li>
                ))}
              </ul>
            </div>
          </div>

          <div className="flex flex-col sm:flex-row items-center justify-between gap-3 text-zinc-500 text-[11px]">
            <div>
              © {new Date().getFullYear()} {PROPERTY_DETAILS.name}, Banda. All rights reserved.
            </div>
            <div className="flex items-center space-x-3">
              <span>Station Road, Gular Naka, Banda, Uttar Pradesh - 210001</span>
              <a href="/staff" className="text-zinc-500 hover:text-zinc-300 underline-offset-2 hover:underline">
                Staff Portal
              </a>
            </div>
          </div>
        </div>
      </footer>
    </div>
  );
};

export default App;

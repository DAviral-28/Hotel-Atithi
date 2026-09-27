import { GalleryImage, Room } from '../types';

/**
 * Tax-invoice identity. The registered legal name and GSTIN belong to the hotel, so they are taken
 * from build-time environment variables (VITE_HOTEL_LEGAL_NAME, VITE_HOTEL_GSTIN) rather than invented
 * here. When no GSTIN is configured the invoice simply omits the GSTIN line.
 */
const LEGAL_NAME = (import.meta.env.VITE_HOTEL_LEGAL_NAME || '').trim() || 'Hotel Atithi (Near Railway Station, Banda)';
const GSTIN = (import.meta.env.VITE_HOTEL_GSTIN || '').trim();

export const PROPERTY_DETAILS = {
  name: 'Hotel Atithi',
  legalName: LEGAL_NAME,
  gstin: GSTIN,
  /** SAC (Services Accounting Code) for accommodation services in hotels / inns / guest houses. */
  sacCode: '996311',
  state: 'Uttar Pradesh',
  tagline: 'Comfortable & affordable lodging on Station Road',
  address: 'Station Road, Gular Naka, Banda, Uttar Pradesh - 210001',
  landmark: 'Near Banda Railway Station',
  phone: '+91 94523 14397',
  phoneTel: '+919452314397',
  checkInTime: '12:00 PM',
  checkOutTime: '12:00 PM',
  /** Highlight badges shown across the Hero, Navbar and footer. Exactly these 7, in this order. */
  amenities: [
    '5 Min Walk from Banda Railway Station',
    'Both Indian & Western Bathrooms',
    '24/7 Hot Water Facility',
    'Comfortable Sofa & Seating',
    '100% 24/7 Power Backup',
    'Free High-Speed Wi-Fi',
    '24/7 Front Desk & Room Service',
  ],
};

/** Actual, real photo sets by room category, used to populate each room's `images[]` (gallery/lightbox) below. */
const ROOM_IMAGES = {
  DELUXE_DOUBLE: ['/images/rooms/deluxe-double-1.jpg', '/images/rooms/deluxe-double-2.jpg'],
  DELUXE_SINGLE: ['/images/rooms/deluxe-single-1.jpg'],
  SUPER_DELUXE: ['/images/rooms/super-deluxe-1.jpg', '/images/rooms/super-deluxe-2.jpg'],
  AC: ['/images/rooms/ac-room-1.jpg', '/images/rooms/ac-room-2.jpg'],
} as const;

/**
 * Polished, high-resolution Unsplash cover photo per room category, used ONLY for the room card's
 * thumbnail on the listing grid (never in the gallery/lightbox, which always shows the real photos above).
 * One classic/aesthetic stock photo per category, chosen to read as a boutique hotel room of that kind.
 */
const ROOM_COVER_IMAGES = {
  // Classic clean boutique hotel double bed.
  DELUXE_DOUBLE: 'https://images.unsplash.com/photo-1590490360182-c33d57733427?auto=format&fit=crop&w=800&q=80',
  // Aesthetic cozy single bedroom.
  DELUXE_SINGLE: 'https://images.unsplash.com/photo-1505693416388-ac5ce068fe85?auto=format&fit=crop&w=800&q=80',
  // Spacious premium hotel suite with seating.
  SUPER_DELUXE: 'https://images.unsplash.com/photo-1582719478250-c89cae4dc85b?auto=format&fit=crop&w=800&q=80',
  // Modern air-conditioned executive hotel room.
  AC: 'https://images.unsplash.com/photo-1618773928121-c32242e63f39?auto=format&fit=crop&w=800&q=80',
} as const;

/** Picks the right (real) photo set for a room from its type + bed configuration. */
function imagesFor(type: Room['type'], bedType: Room['bedType']): string[] {
  if (type === 'AC') return [...ROOM_IMAGES.AC];
  if (type === 'SUPER_DELUXE') return [...ROOM_IMAGES.SUPER_DELUXE];
  return bedType === 'SINGLE' ? [...ROOM_IMAGES.DELUXE_SINGLE] : [...ROOM_IMAGES.DELUXE_DOUBLE];
}

/** Picks the right polished cover photo for a room from its type + bed configuration. */
function coverImageFor(type: Room['type'], bedType: Room['bedType']): string {
  if (type === 'AC') return ROOM_COVER_IMAGES.AC;
  if (type === 'SUPER_DELUXE') return ROOM_COVER_IMAGES.SUPER_DELUXE;
  return bedType === 'SINGLE' ? ROOM_COVER_IMAGES.DELUXE_SINGLE : ROOM_COVER_IMAGES.DELUXE_DOUBLE;
}

/** 32-room catalog, each tagged with its gallery photos via `imagesFor`. */
const ROOM_SHELL: Array<Pick<Room, 'roomNumber' | 'floor' | 'type' | 'bedType' | 'baseRate' | 'extraPersonRate' | 'taxRatePercent' | 'baseOccupancy' | 'maxOccupancy'>> = [
  // Floor 1 (Rooms 101 - 116)
  { roomNumber: 101, floor: 1, type: 'DELUXE', bedType: 'DOUBLE', baseRate: 600, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 102, floor: 1, type: 'DELUXE', bedType: 'DOUBLE', baseRate: 600, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 103, floor: 1, type: 'DELUXE', bedType: 'DOUBLE', baseRate: 600, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 104, floor: 1, type: 'DELUXE', bedType: 'SINGLE', baseRate: 600, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 1, maxOccupancy: 2 },
  { roomNumber: 105, floor: 1, type: 'DELUXE', bedType: 'DOUBLE', baseRate: 600, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 106, floor: 1, type: 'DELUXE', bedType: 'DOUBLE', baseRate: 600, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 107, floor: 1, type: 'DELUXE', bedType: 'DOUBLE', baseRate: 600, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 108, floor: 1, type: 'DELUXE', bedType: 'DOUBLE', baseRate: 600, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 109, floor: 1, type: 'SUPER_DELUXE', bedType: 'DOUBLE', baseRate: 700, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 110, floor: 1, type: 'AC', bedType: 'DOUBLE', baseRate: 1100, extraPersonRate: 200, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 111, floor: 1, type: 'AC', bedType: 'DOUBLE', baseRate: 1100, extraPersonRate: 200, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 112, floor: 1, type: 'AC', bedType: 'DOUBLE', baseRate: 1100, extraPersonRate: 200, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 113, floor: 1, type: 'AC', bedType: 'DOUBLE', baseRate: 1100, extraPersonRate: 200, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 114, floor: 1, type: 'SUPER_DELUXE', bedType: 'DOUBLE', baseRate: 700, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 115, floor: 1, type: 'SUPER_DELUXE', bedType: 'DOUBLE', baseRate: 700, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 116, floor: 1, type: 'SUPER_DELUXE', bedType: 'DOUBLE', baseRate: 700, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },

  // Floor 2 (Rooms 201 - 216)
  { roomNumber: 201, floor: 2, type: 'DELUXE', bedType: 'DOUBLE', baseRate: 600, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 202, floor: 2, type: 'DELUXE', bedType: 'DOUBLE', baseRate: 600, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 203, floor: 2, type: 'DELUXE', bedType: 'DOUBLE', baseRate: 600, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 204, floor: 2, type: 'DELUXE', bedType: 'SINGLE', baseRate: 600, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 1, maxOccupancy: 2 },
  { roomNumber: 205, floor: 2, type: 'DELUXE', bedType: 'DOUBLE', baseRate: 600, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 206, floor: 2, type: 'DELUXE', bedType: 'DOUBLE', baseRate: 600, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 207, floor: 2, type: 'DELUXE', bedType: 'DOUBLE', baseRate: 600, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 208, floor: 2, type: 'DELUXE', bedType: 'DOUBLE', baseRate: 600, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 209, floor: 2, type: 'SUPER_DELUXE', bedType: 'DOUBLE', baseRate: 700, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 210, floor: 2, type: 'AC', bedType: 'DOUBLE', baseRate: 1100, extraPersonRate: 200, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 211, floor: 2, type: 'AC', bedType: 'DOUBLE', baseRate: 1100, extraPersonRate: 200, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 212, floor: 2, type: 'AC', bedType: 'DOUBLE', baseRate: 1100, extraPersonRate: 200, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 213, floor: 2, type: 'AC', bedType: 'DOUBLE', baseRate: 1100, extraPersonRate: 200, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 214, floor: 2, type: 'SUPER_DELUXE', bedType: 'DOUBLE', baseRate: 700, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 215, floor: 2, type: 'SUPER_DELUXE', bedType: 'DOUBLE', baseRate: 700, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
  { roomNumber: 216, floor: 2, type: 'SUPER_DELUXE', bedType: 'DOUBLE', baseRate: 700, extraPersonRate: 100, taxRatePercent: 5, baseOccupancy: 2, maxOccupancy: 3 },
];

export const INITIAL_ROOMS: Room[] = ROOM_SHELL.map((r) => ({
  ...r,
  images: imagesFor(r.type, r.bedType),
  coverImage: coverImageFor(r.type, r.bedType),
}));

/**
 * The 11 hotel photos supplied for the site: 7 room photos (one set per category/bed type) plus
 * reception/lobby and exterior shots. Feeds the "View Hotel Photos" gallery in the Navbar/Hero.
 */
export const HOTEL_GALLERY: GalleryImage[] = [
  { src: '/images/rooms/deluxe-double-1.jpg', category: 'ROOMS', roomType: 'DELUXE', title: 'Deluxe Double Room', description: 'Comfortable deluxe room with double bed, ideal for couples or friends travelling together.' },
  { src: '/images/rooms/deluxe-double-2.jpg', category: 'ROOMS', roomType: 'DELUXE', title: 'Deluxe Double Room', description: 'A second view of the deluxe double room, furnished with modern hospitality amenities.' },
  { src: '/images/rooms/deluxe-single-1.jpg', category: 'ROOMS', roomType: 'DELUXE', title: 'Deluxe Single Room', description: 'Cosy single-bed deluxe room (Rooms 104 & 204), perfect for solo travellers.' },
  { src: '/images/rooms/super-deluxe-1.jpg', category: 'ROOMS', roomType: 'SUPER_DELUXE', title: 'Super Deluxe Room', description: 'Spacious super deluxe room with premium comfort and extra seating.' },
  { src: '/images/rooms/super-deluxe-2.jpg', category: 'ROOMS', roomType: 'SUPER_DELUXE', title: 'Super Deluxe Room', description: 'A second view of the super deluxe room, showing the seating and layout.' },
  { src: '/images/rooms/ac-room-1.jpg', category: 'ROOMS', roomType: 'AC', title: 'AC Room', description: 'Climate-controlled air-conditioned room with round-the-clock power backup.' },
  { src: '/images/rooms/ac-room-2.jpg', category: 'ROOMS', roomType: 'AC', title: 'AC Room', description: 'A second view of the air-conditioned room and its furnishings.' },
  { src: '/images/property/reception-1.jpg', category: 'RECEPTION', title: 'Reception & Lobby', description: 'Our 24/7 front desk, ready to welcome guests at any hour.' },
  { src: '/images/property/reception-2.jpg', category: 'RECEPTION', title: 'Reception & Lobby', description: 'A second view of the reception and lobby area.' },
  { src: '/images/property/exterior-1.jpg', category: 'EXTERIOR', title: 'Hotel Exterior', description: 'Hotel Atithi’s facade on Station Road, a 5-minute walk from Banda Railway Station.' },
  { src: '/images/property/exterior-2.jpg', category: 'EXTERIOR', title: 'Hotel Exterior', description: 'A second exterior view of the property and its entrance.' },
];

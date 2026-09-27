export type RoomType = 'DELUXE' | 'SUPER_DELUXE' | 'AC';
export type BedType = 'SINGLE' | 'DOUBLE';
export type BookingStatus =
  | 'HOLD'
  | 'CONFIRMED'
  | 'RELEASED'
  | 'CANCELLED'
  | 'COMPLETED'
  | 'CHECKED_IN'
  | 'CHECKED_OUT';
/** How the guest settles the bill. No payment gateway is involved (zero fees). */
export type PaymentMethod = 'PAY_AT_HOTEL' | 'UPI_QR';
/** PENDING = only a hold; PAID_ONLINE = nothing left to pay; DUE_AT_CHECKIN = a balance is owed at the desk. */
export type PaymentStatus = 'PENDING' | 'PAID_ONLINE' | 'DUE_AT_CHECKIN' | 'PAID_AT_DESK';

export interface Room {
  roomNumber: number;
  floor: number;
  type: RoomType;
  bedType: BedType;
  baseRate: number;
  extraPersonRate: number;
  taxRatePercent: number;
  baseOccupancy: number;
  maxOccupancy: number;
  /**
   * Actual, real photos of this room category, taken at the property (e.g. "/images/rooms/deluxe-double-1.jpg").
   * These are the photos shown in the "View Photos" gallery/lightbox — kept exactly as supplied, unpolished,
   * for transparency about what a guest will actually get.
   */
  images: string[];
  /**
   * A single polished, high-resolution editorial/stock photo (Unsplash) used ONLY as the room card's cover
   * thumbnail on the listing grid, so cards look consistently premium even where the real property photos
   * vary in quality. Never shown in the gallery/lightbox — that always uses `images` (the real photos).
   */
  coverImage: string;
}

/** Broad category tabs in the hotel photo gallery. */
export type GalleryCategory = 'ROOMS' | 'RECEPTION' | 'EXTERIOR';

/** One photo in the central hotel gallery (HOTEL_GALLERY). */
export interface GalleryImage {
  src: string;
  category: GalleryCategory;
  /** Set only when category is 'ROOMS' — lets the gallery offer per-room-type tabs (Deluxe / Super Deluxe / AC). */
  roomType?: RoomType;
  title: string;
  description: string;
}

export interface BookingResponse {
  bookingId: string;
  roomNumber: number;
  checkInDate: string;
  checkOutDate: string;
  nights?: number;
  guestName: string;
  guestPhone: string;
  guestEmail?: string;
  guestCount: number;
  baseRatePerNight?: number;
  extraGuestRatePerNight?: number;
  subtotal: number;
  gstAmount: number;
  totalAmount: number;
  status: BookingStatus;
  createdAt: string;
  expiresAt?: string | number;
  remainingHoldSeconds?: number;
  paymentReference?: string;
  confirmedAt?: string;
  checkoutTime: string;
  /** Short-lived (10 min) token that proves ownership of a HOLD. */
  bookingToken?: string;

  // ---- GST invoice breakdown (computed by the server) ----
  roomTariffSubtotal: number;
  extraGuestCount: number;
  extraGuestChargesSubtotal: number;
  cgstAmount: number;
  sgstAmount: number;

  // ---- Payment ----
  paymentMethod?: PaymentMethod | null;
  paymentStatus: PaymentStatus;
  amountPaid: number;
  balanceDue: number;
  paidAt?: string | null;

  // ---- Cancellation ----
  cancelledAt?: string | null;
  lateCancellation: boolean;
  cancellable: boolean;
  freeCancellationDeadline?: string | null;
  refundableAmount?: number | null;

  /** Present on confirm / phone-verified responses only. Authorises cancel, extend and pay-balance. */
  manageToken?: string | null;
}

/** Public UPI settings from GET /api/payments/config. */
export interface PaymentConfig {
  upiEnabled: boolean;
  upiVpa: string;
  payeeName: string;
  currency: string;
}

export interface AvailableRoomDto {
  roomNumber: number;
  floor: number;
  type: RoomType;
  bedType: BedType;
  baseRate: number;
  extraPersonRate: number;
  baseOccupancy: number;
  maxOccupancy: number;
}

export interface RoomAvailabilityResponse {
  checkInDate: string;
  checkOutDate: string;
  totalAvailableRooms: number;
  checkoutTime?: string;
  availableRooms: AvailableRoomDto[] | Room[];
}

export interface HoldBookingRequest {
  roomNumber: number;
  checkInDate: string;
  checkOutDate: string;
  guestCount: number;
  guestName: string;
  guestPhone: string;
  guestEmail: string;
}

export interface ConfirmBookingRequest {
  paymentMethod: PaymentMethod;
  /** The 12-character UPI UTR. Required for UPI_QR, omitted for PAY_AT_HOTEL. */
  paymentReference?: string;
  bookingToken?: string;
}

export interface ExtendBookingRequest {
  newCheckOutDate: string;
}

/**
 * What a phone number ALONE reveals about a booking: masked and unprivileged. No full booking ID (only
 * a hint to compare with the confirmation), no tokens, no e-mail, no room number, no amounts.
 */
export interface BookingSummary {
  /** First four characters of the booking ID followed by "...", e.g. "3a7b...". */
  bookingIdHint: string;
  status: BookingStatus;
  checkInDate: string;
  checkOutDate: string;
  /** Room category and bed type, e.g. "Air Conditioned Room, Double Bed". */
  roomLabel: string;
  /** e.g. "R*** K****" */
  guestNameMasked: string;
}

/** Result of the two-factor lookup (Booking ID + last 4 digits / full phone). Masked, no tokens. */
export interface VerifiedBookingSummary {
  bookingId: string;
  status: BookingStatus;
  checkInDate: string;
  checkOutDate: string;
  nights: number;
  roomLabel: string;
  guestCount: number;
  guestNameMasked: string;
  guestPhoneMasked: string;
  guestEmailMasked: string | null;
  totalAmount: number;
  amountPaid: number;
  balanceDue: number;
  paymentMethod: PaymentMethod | null;
  paymentStatus: PaymentStatus;
  cancellable: boolean;
  freeCancellationDeadline: string | null;
  lateCancellation: boolean;
}

// ---------------------------------------------------------------------------------------------
// Phase 4 - front-desk (staff) console
// ---------------------------------------------------------------------------------------------

/** A booking as the front desk sees it (full guest details; never contains a booking token). */
export interface StaffBooking {
  bookingId: string;
  roomNumber: number;
  checkInDate: string;
  checkOutDate: string;
  nights: number;
  guestName: string;
  guestPhone: string;
  guestEmail: string | null;
  guestCount: number;
  status: BookingStatus;
  totalAmount: number;
  amountPaid: number;
  balanceDue: number;
  paymentMethod: PaymentMethod | null;
  paymentStatus: PaymentStatus;
  paymentReference: string | null;
  /** Staff already reconciled the online payment / collected the balance. */
  paymentVerified: boolean;
  checkedInAt: string | null;
  checkedOutAt: string | null;
}

export type RoomLiveStatus = 'AVAILABLE' | 'RESERVED' | 'IN_HOUSE' | 'MAINTENANCE';

export interface StaffRoomStatus {
  roomNumber: number;
  floor: number;
  type: RoomType;
  status: RoomLiveStatus;
  bookingId: string | null;
  guestName: string | null;
  checkInDate: string | null;
  checkOutDate: string | null;
}

export interface StaffStats {
  totalRooms: number;
  roomsOccupied: number;
  roomsReserved: number;
  roomsAvailable: number;
  roomsMaintenance: number;
  arrivalsToday: number;
  departuresToday: number;
  pendingDeskDues: number;
  pendingDeskDuesAmount: number;
  unverifiedPayments: number;
}

export interface StaffDashboard {
  date: string;
  stats: StaffStats;
  arrivals: StaffBooking[];
  departures: StaffBooking[];
  inHouse: StaffBooking[];
  unverifiedPayments: StaffBooking[];
  rooms: StaffRoomStatus[];
}

export interface StaffLoginResponse {
  token: string;
  expiresAt: string;
  expiresInSeconds: number;
}

export interface RoomBlockResponse {
  roomNumber: number;
  startDate: string;
  endDate: string;
  nights: number;
  message: string;
}

export interface WalkInRequest {
  roomNumber: number;
  /** Omit for "today". */
  checkInDate?: string;
  checkOutDate: string;
  guestCount: number;
  guestName: string;
  guestPhone: string;
  guestEmail?: string;
  /** Rupees taken at the desk now (0 / omitted = everything due later). */
  amountCollected?: number;
  /** Default true: mark the guest checked in straight away (only applies to a same-day arrival). */
  checkInNow?: boolean;
}

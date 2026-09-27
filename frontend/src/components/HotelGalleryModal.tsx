import React, { useMemo, useState } from 'react';
import { Camera, ChevronLeft, ChevronRight, X } from 'lucide-react';
import { GalleryImage } from '../types';

interface HotelGalleryModalProps {
  /** The full 11-photo hotel gallery. */
  allImages: GalleryImage[];
  onClose: () => void;
  /**
   * When set (e.g. from a RoomCard's "View Photos" button), the modal opens focused on just this
   * room's photos instead of the full gallery, with an option to switch to "All Photos".
   */
  focusImages?: GalleryImage[];
  focusTitle?: string;
}

/** Fine-grained tabs: room photos are split by room type, not lumped into one "Rooms" bucket. */
type GalleryTab = 'ALL' | 'DELUXE' | 'SUPER_DELUXE' | 'AC' | 'RECEPTION' | 'EXTERIOR';

const TABS: { id: GalleryTab; label: string }[] = [
  { id: 'ALL', label: 'All Photos' },
  { id: 'DELUXE', label: 'Deluxe Rooms' },
  { id: 'SUPER_DELUXE', label: 'Super Deluxe' },
  { id: 'AC', label: 'AC Rooms' },
  { id: 'RECEPTION', label: 'Lobby & Reception' },
  { id: 'EXTERIOR', label: 'Exterior' },
];

function matchesTab(img: GalleryImage, tab: GalleryTab): boolean {
  if (tab === 'ALL') return true;
  if (tab === 'RECEPTION') return img.category === 'RECEPTION';
  if (tab === 'EXTERIOR') return img.category === 'EXTERIOR';
  return img.category === 'ROOMS' && img.roomType === tab;
}

export const HotelGalleryModal: React.FC<HotelGalleryModalProps> = ({
  allImages,
  onClose,
  focusImages,
  focusTitle,
}) => {
  const [showFullGallery, setShowFullGallery] = useState<boolean>(!focusImages || focusImages.length === 0);
  const [activeTab, setActiveTab] = useState<GalleryTab>('ALL');
  const [lightboxIndex, setLightboxIndex] = useState<number | null>(null);

  const counts = useMemo<Record<GalleryTab, number>>(
    () => ({
      ALL: allImages.length,
      DELUXE: allImages.filter((i) => matchesTab(i, 'DELUXE')).length,
      SUPER_DELUXE: allImages.filter((i) => matchesTab(i, 'SUPER_DELUXE')).length,
      AC: allImages.filter((i) => matchesTab(i, 'AC')).length,
      RECEPTION: allImages.filter((i) => matchesTab(i, 'RECEPTION')).length,
      EXTERIOR: allImages.filter((i) => matchesTab(i, 'EXTERIOR')).length,
    }),
    [allImages]
  );

  const inFocusMode = !showFullGallery && !!focusImages && focusImages.length > 0;

  const filtered = useMemo(() => {
    if (inFocusMode) return focusImages as GalleryImage[];
    return allImages.filter((i) => matchesTab(i, activeTab));
  }, [inFocusMode, focusImages, allImages, activeTab]);

  const openLightbox = (idx: number) => setLightboxIndex(idx);
  const closeLightbox = () => setLightboxIndex(null);
  const showNext = () =>
    setLightboxIndex((prev) => (prev === null || filtered.length === 0 ? prev : (prev + 1) % filtered.length));
  const showPrev = () =>
    setLightboxIndex((prev) => (prev === null || filtered.length === 0 ? prev : (prev - 1 + filtered.length) % filtered.length));

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center p-4 bg-black/85 backdrop-blur-2xl animate-in fade-in duration-200">
      <div className="relative w-full max-w-5xl max-h-[90vh] bg-zinc-900/90 backdrop-blur-xl rounded-3xl shadow-2xl border border-white/10 flex flex-col overflow-hidden">
        {/* Ambient glow */}
        <div className="ambient-orb w-72 h-72 bg-cyan-500/10 -top-20 -right-20" />

        {/* Header */}
        <div className="relative z-10 flex items-center justify-between px-5 sm:px-6 py-4 border-b border-white/10 shrink-0">
          <div className="flex items-center space-x-2.5">
            <Camera className="w-5 h-5 text-cyan-400" />
            <h2 className="text-lg font-bold text-zinc-50">
              {inFocusMode ? focusTitle || 'Room Photos' : 'Hotel Photo Gallery'}
            </h2>
          </div>
          <button
            onClick={onClose}
            aria-label="Close gallery"
            className="p-1.5 rounded-lg hover:bg-white/10 text-zinc-400 hover:text-white transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Category Tabs (full gallery mode) or a link back to the full gallery (focus mode) */}
        {inFocusMode ? (
          <div className="relative z-10 px-5 sm:px-6 py-3 border-b border-white/10 shrink-0">
            <button
              onClick={() => setShowFullGallery(true)}
              className="text-xs font-bold text-cyan-400 hover:text-cyan-300 underline"
            >
              View All Hotel Photos ({counts.ALL})
            </button>
          </div>
        ) : (
          <div className="relative z-10 flex items-center gap-2 px-5 sm:px-6 py-3 border-b border-white/10 overflow-x-auto shrink-0">
            {TABS.map((tab) => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                className={`px-3.5 py-1.5 rounded-full text-xs font-bold whitespace-nowrap transition-all ${
                  activeTab === tab.id
                    ? 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/40 shadow-lg shadow-cyan-500/10'
                    : 'bg-white/5 hover:bg-white/10 text-zinc-300 border border-white/10'
                }`}
              >
                {tab.label} ({counts[tab.id]})
              </button>
            ))}
          </div>
        )}

        {/* Grid */}
        <div className="relative z-10 flex-1 overflow-y-auto p-5 sm:p-6">
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-4">
            {filtered.map((img, idx) => (
              <button
                key={`${img.src}-${idx}`}
                onClick={() => openLightbox(idx)}
                className="group relative aspect-[4/3] rounded-2xl overflow-hidden border border-white/10 bg-zinc-800"
              >
                <img
                  src={img.src}
                  alt={img.title}
                  className="w-full h-full object-cover transition-transform duration-500 group-hover:scale-110"
                  loading="lazy"
                />
                <div className="absolute inset-0 bg-gradient-to-t from-black/80 via-black/10 to-transparent opacity-0 group-hover:opacity-100 transition-opacity flex items-end p-2.5">
                  <span className="text-white text-xs font-semibold text-left">{img.title}</span>
                </div>
              </button>
            ))}
          </div>
          {filtered.length === 0 && (
            <p className="text-center text-zinc-500 text-sm py-10">No photos in this category yet.</p>
          )}
        </div>
      </div>

      {/* Lightbox */}
      {lightboxIndex !== null && filtered[lightboxIndex] && (
        <div
          className="fixed inset-0 z-[70] flex items-center justify-center p-4 bg-black/95 backdrop-blur-sm"
          onClick={closeLightbox}
        >
          <button
            onClick={(e) => {
              e.stopPropagation();
              closeLightbox();
            }}
            aria-label="Close photo"
            className="absolute top-5 right-5 p-2 rounded-full bg-white/10 hover:bg-white/20 text-white transition-colors"
          >
            <X className="w-6 h-6" />
          </button>

          {filtered.length > 1 && (
            <>
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  showPrev();
                }}
                aria-label="Previous photo"
                className="absolute left-3 sm:left-6 p-2.5 rounded-full bg-white/10 hover:bg-white/20 text-white transition-colors"
              >
                <ChevronLeft className="w-6 h-6" />
              </button>
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  showNext();
                }}
                aria-label="Next photo"
                className="absolute right-3 sm:right-6 p-2.5 rounded-full bg-white/10 hover:bg-white/20 text-white transition-colors"
              >
                <ChevronRight className="w-6 h-6" />
              </button>
            </>
          )}

          <div className="max-w-4xl w-full flex flex-col items-center" onClick={(e) => e.stopPropagation()}>
            <img
              src={filtered[lightboxIndex].src}
              alt={filtered[lightboxIndex].title}
              className="max-w-full max-h-[65vh] rounded-2xl object-contain shadow-2xl border border-white/10"
            />
            <div className="mt-3 text-center text-white max-w-xl">
              <p className="font-bold text-sm">{filtered[lightboxIndex].title}</p>
              <p className="text-xs text-zinc-400 mt-0.5">{filtered[lightboxIndex].description}</p>
            </div>

            {/* Thumbnail navigation strip */}
            {filtered.length > 1 && (
              <div className="mt-4 flex items-center gap-2 overflow-x-auto max-w-full px-2 py-1">
                {filtered.map((img, idx) => (
                  <button
                    key={`${img.src}-thumb-${idx}`}
                    onClick={(e) => {
                      e.stopPropagation();
                      setLightboxIndex(idx);
                    }}
                    className={`shrink-0 w-14 h-14 rounded-lg overflow-hidden border-2 transition-all ${
                      idx === lightboxIndex ? 'border-cyan-400 shadow-lg shadow-cyan-500/30' : 'border-white/10 opacity-60 hover:opacity-100'
                    }`}
                  >
                    <img src={img.src} alt={img.title} className="w-full h-full object-cover" />
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

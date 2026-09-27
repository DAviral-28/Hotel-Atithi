import React, { useMemo } from 'react';
import { encodeQr } from '../utils/qr';

interface QrCodeSvgProps {
  value: string;
  size?: number;
  label?: string;
}

/** Renders `value` as a QR code (inline SVG, black on white, with the mandatory 4-module quiet zone). */
export const QrCodeSvg: React.FC<QrCodeSvgProps> = ({ value, size = 192, label = 'QR code' }) => {
  const modules = useMemo(() => {
    try {
      return encodeQr(value);
    } catch (err) {
      console.error('Could not build QR code', err);
      return null;
    }
  }, [value]);

  if (!modules) {
    return null;
  }

  const quiet = 4;
  const n = modules.length;
  let path = '';
  modules.forEach((row, y) =>
    row.forEach((dark, x) => {
      if (dark) path += `M${x + quiet},${y + quiet}h1v1h-1z`;
    })
  );

  return (
    <svg
      viewBox={`0 0 ${n + quiet * 2} ${n + quiet * 2}`}
      width={size}
      height={size}
      role="img"
      aria-label={label}
      shapeRendering="crispEdges"
    >
      <rect width={n + quiet * 2} height={n + quiet * 2} fill="#ffffff" />
      <path d={path} fill="#000000" />
    </svg>
  );
};

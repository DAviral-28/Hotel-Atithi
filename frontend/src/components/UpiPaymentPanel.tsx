import React, { useState } from 'react';
import { Copy, Check, Smartphone, ShieldCheck } from 'lucide-react';
import { PaymentConfig } from '../types';
import { buildUpiUri, isValidUtr } from '../utils/upi';
import { QrCodeSvg } from './QrCodeSvg';

interface UpiPaymentPanelProps {
  config: PaymentConfig;
  bookingId: string;
  /** Amount in rupees the guest must pay now. */
  amount: number;
  utr: string;
  onUtrChange: (value: string) => void;
  disabled?: boolean;
}

/**
 * Zero-fee UPI payment: a dynamic QR + "open UPI app" link for the exact amount, then the guest types the
 * 12-character UTR their UPI app shows. There is no gateway callback, so the UTR is self-declared and the
 * front desk checks it against the bank statement.
 */
export const UpiPaymentPanel: React.FC<UpiPaymentPanelProps> = ({
  config,
  bookingId,
  amount,
  utr,
  onUtrChange,
  disabled = false,
}) => {
  const [copied, setCopied] = useState(false);
  const uri = buildUpiUri({ vpa: config.upiVpa, payeeName: config.payeeName, amount, bookingId });
  const trimmed = utr.trim();
  const showUtrError = trimmed.length > 0 && !isValidUtr(trimmed);

  const copyVpa = async () => {
    try {
      await navigator.clipboard.writeText(config.upiVpa);
      setCopied(true);
      setTimeout(() => setCopied(false), 1800);
    } catch {
      /* clipboard unavailable: the VPA is still visible to copy by hand */
    }
  };

  return (
    <div className="p-4 rounded-2xl bg-white/5 border border-white/10 space-y-4">
      <div className="flex flex-col sm:flex-row items-center gap-4">
        <div className="bg-white p-2 rounded-xl border border-white/10 shrink-0">
          <QrCodeSvg value={uri} size={168} label={`UPI payment QR code for ₹${amount.toFixed(2)}`} />
        </div>
        <div className="text-xs text-zinc-300 space-y-2 w-full">
          <p className="font-bold text-zinc-50 text-sm">Pay ₹{amount.toFixed(2)} by UPI</p>
          <ol className="list-decimal list-inside space-y-1 text-zinc-400">
            <li>Scan this QR with any UPI app (GPay, PhonePe, Paytm, BHIM…), or tap the button below on your phone.</li>
            <li>The amount and booking reference are pre-filled. Complete the payment.</li>
            <li>Copy the 12-character <strong>UTR / Reference No.</strong> from the payment receipt and enter it below.</li>
          </ol>
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-zinc-500">UPI ID:</span>
            <span className="font-mono font-semibold text-zinc-100 break-all">{config.upiVpa}</span>
            <button
              type="button"
              onClick={copyVpa}
              className="inline-flex items-center space-x-1 px-2 py-1 rounded-lg border border-white/10 bg-white/5 hover:bg-white/10 text-[11px] font-semibold text-zinc-200"
            >
              {copied ? <Check className="w-3 h-3 text-emerald-400" /> : <Copy className="w-3 h-3" />}
              <span>{copied ? 'Copied' : 'Copy'}</span>
            </button>
          </div>
          <a
            href={uri}
            className="inline-flex items-center space-x-1.5 px-3 py-2 rounded-xl bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white font-bold text-xs sm:hidden"
          >
            <Smartphone className="w-3.5 h-3.5" />
            <span>Open UPI app</span>
          </a>
        </div>
      </div>

      <div className="space-y-1.5">
        <label htmlFor="utr-input" className="text-xs font-bold text-zinc-300 block">
          12-character UTR / Reference Number *
        </label>
        <input
          id="utr-input"
          type="text"
          inputMode="text"
          autoComplete="off"
          maxLength={12}
          placeholder="e.g. 402512345678"
          value={utr}
          disabled={disabled}
          onChange={(e) => onUtrChange(e.target.value.replace(/[^A-Za-z0-9]/g, '').toUpperCase())}
          aria-invalid={showUtrError}
          className={`w-full bg-white/5 border rounded-xl px-3.5 py-2 text-sm font-mono tracking-wider text-zinc-50 focus:outline-none focus:ring-2 focus:ring-cyan-400/70 ${
            showUtrError ? 'border-rose-400/60' : 'border-white/10'
          }`}
        />
        {showUtrError && (
          <p className="text-[11px] text-rose-400">The UTR must be exactly 12 letters or digits ({trimmed.length} entered).</p>
        )}
        <p className="text-[11px] text-zinc-500 flex items-start space-x-1.5">
          <ShieldCheck className="w-3.5 h-3.5 text-emerald-400 shrink-0 mt-px" />
          <span>Our front desk verifies this UTR against the hotel's UPI statement. Please enter it carefully.</span>
        </p>
      </div>
    </div>
  );
};

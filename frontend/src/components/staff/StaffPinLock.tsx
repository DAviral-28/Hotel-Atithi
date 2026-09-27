import React, { useState } from 'react';
import { Lock, Loader2 } from 'lucide-react';
import { staffLogin } from '../../services/staffApi';
import { PROPERTY_DETAILS } from '../../data/roomDirectory';
import { ErrorBanner } from '../ErrorBanner';
import { errorMessage } from './staffUtils';

interface StaffPinLockProps {
  onUnlocked: () => void;
  /** Shown when the previous session ended (expired / PIN changed). */
  notice?: string | null;
}

/** Simple PIN screen. The PIN is checked by the server; nothing about it is stored in the browser. */
export const StaffPinLock: React.FC<StaffPinLockProps> = ({ onUnlocked, notice }) => {
  const [pin, setPin] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!pin.trim() || busy) return;
    setBusy(true);
    setError(null);
    try {
      await staffLogin(pin);
      setPin('');
      onUnlocked();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="min-h-screen bg-stone-50 flex items-center justify-center px-4">
      <form onSubmit={submit} className="w-full max-w-sm bg-white rounded-3xl shadow-xl border border-stone-200 p-8 space-y-5">
        <div className="text-center space-y-2">
          <div className="w-14 h-14 rounded-full bg-amber-100 flex items-center justify-center mx-auto">
            <Lock className="w-6 h-6 text-amber-700" />
          </div>
          <h1 className="text-xl font-extrabold text-stone-900">{PROPERTY_DETAILS.name} - Front Desk</h1>
          <p className="text-xs text-stone-500">Staff only. Enter the front-desk PIN to continue.</p>
        </div>

        {notice && !error && (
          <p role="status" className="text-xs text-amber-800 bg-amber-50 border border-amber-200 rounded-xl px-3 py-2">
            {notice}
          </p>
        )}
        {error && <ErrorBanner message={error} />}

        <div className="space-y-1.5">
          <label htmlFor="staff-pin" className="text-xs font-semibold text-stone-700">
            Staff PIN
          </label>
          <input
            id="staff-pin"
            type="password"
            inputMode="numeric"
            autoComplete="off"
            autoFocus
            value={pin}
            onChange={(e) => setPin(e.target.value)}
            className="w-full px-4 py-3 rounded-xl border border-stone-300 focus:border-amber-500 focus:ring-2 focus:ring-amber-200 outline-none text-center text-lg tracking-[0.4em]"
            placeholder="••••"
            maxLength={64}
          />
        </div>

        <button
          type="submit"
          disabled={busy || !pin.trim()}
          className="w-full py-3 rounded-xl bg-amber-600 hover:bg-amber-700 disabled:opacity-50 text-white font-bold text-sm flex items-center justify-center space-x-2"
        >
          {busy && <Loader2 className="w-4 h-4 animate-spin" />}
          <span>{busy ? 'Checking...' : 'Unlock'}</span>
        </button>

        <a href="/" className="block text-center text-[11px] text-stone-400 hover:text-stone-600">
          Back to the guest website
        </a>
      </form>
    </div>
  );
};

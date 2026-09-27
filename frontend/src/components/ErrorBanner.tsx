import React from 'react';
import { AlertTriangle, RefreshCw } from 'lucide-react';

interface ErrorBannerProps {
  /** Short bold headline, e.g. "We couldn't confirm your booking." */
  title?: string;
  message: string;
  /** When provided, a "Try again" button is shown (only offer it for retryable failures). */
  onRetry?: () => void;
  retrying?: boolean;
  children?: React.ReactNode;
}

/**
 * The single way the app reports a failed server call: a clear message plus, when trying again can
 * help, a retry button. Used instead of any offline "pretend it worked" behaviour.
 */
export const ErrorBanner: React.FC<ErrorBannerProps> = ({ title, message, onRetry, retrying = false, children }) => (
  <div
    role="alert"
    className="p-3.5 rounded-xl bg-rose-400/10 border border-rose-400/30 text-rose-200 text-xs flex flex-col sm:flex-row sm:items-center justify-between gap-3 backdrop-blur-md"
  >
    <div className="flex items-start space-x-2.5">
      <AlertTriangle className="w-4 h-4 text-rose-400 shrink-0 mt-0.5" />
      <div className="space-y-0.5">
        {title && <p className="font-bold text-sm text-rose-100">{title}</p>}
        <p>{message}</p>
        {children}
      </div>
    </div>
    {onRetry && (
      <button
        type="button"
        onClick={onRetry}
        disabled={retrying}
        className="shrink-0 px-4 py-2 rounded-xl bg-rose-500 hover:bg-rose-600 text-white text-xs font-bold flex items-center justify-center space-x-1.5 disabled:opacity-50"
      >
        <RefreshCw className={`w-3.5 h-3.5 ${retrying ? 'animate-spin' : ''}`} />
        <span>Try again</span>
      </button>
    )}
  </div>
);

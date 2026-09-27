/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  /** Registered legal name printed on the GST invoice. */
  readonly VITE_HOTEL_LEGAL_NAME?: string;
  /** The hotel's GSTIN, printed on the GST invoice when set. */
  readonly VITE_HOTEL_GSTIN?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

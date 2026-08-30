import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { editionApi } from '../api';
import type { EditionStatus, FeatureKey, QuotaKey } from '../types';

/**
 * No link unless the deployment configures one. The UI renders the marker and the
 * explanation regardless; the link is the only part that is optional, and it is absent
 * by default so nothing ships pointing at a commercial site.
 */
const FALLBACK_UPGRADE_URL = '';

interface EditionContextValue {
  status: EditionStatus | null;
  /** Whether a paid capability is available in this build. */
  hasFeature: (feature: FeatureKey) => boolean;
  /** The cap for a quota, or null when it is unlimited. */
  limitOf: (quota: QuotaKey) => number | null;
  /** How many currently exist. */
  usageOf: (quota: QuotaKey) => number;
  /** Whether a quota is at or over its cap — the moment a create button locks. */
  atLimit: (quota: QuotaKey) => boolean;
  isCommunity: boolean;
  upgradeUrl: string;
  /** Re-reads usage after something is created or deleted. */
  refresh: () => Promise<void>;
}

const EditionContext = createContext<EditionContextValue>({
  status: null,
  hasFeature: () => true,
  limitOf: () => null,
  usageOf: () => 0,
  atLimit: () => false,
  isCommunity: false,
  upgradeUrl: FALLBACK_UPGRADE_URL,
  refresh: async () => {},
});

/**
 * Loads the edition capabilities once, for the whole app.
 *
 * <p>Defaults are deliberately permissive: until the fetch resolves — and forever, if it
 * fails — every feature reads as available. A failed request must not paint an
 * enterprise install as the open source edition and bury features the customer paid for.
 * The backend is the real gate, so an optimistic UI can only ever be caught out by a 402,
 * which the interceptor already turns into the same upgrade prompt.
 */
export function EditionProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<EditionStatus | null>(null);

  const load = useCallback(async () => {
    try {
      const res = await editionApi.get();
      if (res.data) setStatus(res.data);
    } catch {
      // Stay optimistic — see the note above.
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const value = useMemo<EditionContextValue>(() => {
    const hasFeature = (feature: FeatureKey) => status?.features?.[feature] ?? true;
    const limitOf = (quota: QuotaKey) => status?.limits?.[quota] ?? null;
    const usageOf = (quota: QuotaKey) => status?.usage?.[quota] ?? 0;

    return {
      status,
      hasFeature,
      limitOf,
      usageOf,
      atLimit: (quota: QuotaKey) => {
        const limit = limitOf(quota);
        return limit !== null && usageOf(quota) >= limit;
      },
      isCommunity: status?.edition === 'COMMUNITY',
      upgradeUrl: status?.upgradeUrl ?? FALLBACK_UPGRADE_URL,
      refresh: load,
    };
  }, [status, load]);

  return <EditionContext.Provider value={value}>{children}</EditionContext.Provider>;
}

export function useEdition() {
  return useContext(EditionContext);
}

/** Convenience for the common case of gating one thing. */
export function useFeature(feature: FeatureKey) {
  return useEdition().hasFeature(feature);
}

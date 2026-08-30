import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { brandingApi } from '../api';
import type { Branding } from '../types';

/**
 * The shipped marks, used wherever a slot has not been configured.
 *
 * <p>Each edition ships as itself: the open source build is OWASP Faction and says so on
 * the sign-in page and in the sidebar, while the paid build is Faction. Chosen at build
 * time from {@link __FACTION_EDITION__}, not at runtime — the sign-in page paints its logo
 * before anyone is authenticated, so there is no edition lookup to wait for and no moment
 * where the wrong mark flashes.
 *
 * <p>Both keep the standalone F for the collapsed sidebar and the favicon: it reads at
 * 16px, where a full lockup cannot, and carries no wordmark to be wrong about.
 *
 * <p>Any install can replace all of this under Administration → Branding.
 */
const COMMUNITY_MARKS = {
  loginLogo: '/owasp-faction-logo.png',
  menuLogoLarge: '/owasp-faction-logo.png',
} as const;

const ENTERPRISE_MARKS = {
  loginLogo: '/faction-white-logo.png',
  menuLogoLarge: '/faction-white-logo.png',
} as const;

const DEFAULTS = {
  ...(__FACTION_EDITION__ === 'enterprise' ? ENTERPRISE_MARKS : COMMUNITY_MARKS),
  menuLogoSmall: '/faction-small-logo.png',
  favicon: '/faction-small-logo.png',
} as const;

/**
 * Heights matching the shipped stylesheet, used until the config arrives.
 *
 * <p>Edition-specific for the same reason the marks are: the OWASP lockup stacks a badge
 * above FACTION, so roughly 40% of its height is not wordmark. Rendering it at the paid
 * edition's heights makes the wordmark noticeably smaller than the mark it sits beside in
 * every other Faction screenshot.
 */
const DEFAULT_HEIGHTS = __FACTION_EDITION__ === 'enterprise'
  ? { loginLogo: 120, menuLogoLarge: 70, menuLogoSmall: 76 } as const
  : { loginLogo: 150, menuLogoLarge: 88, menuLogoSmall: 76 } as const;

interface BrandingContextValue {
  /** Raw config, or null until the first load resolves. */
  branding: Branding | null;
  loginLogo: string;
  menuLogoLarge: string;
  menuLogoSmall: string;
  loginLogoHeight: number;
  menuLogoLargeHeight: number;
  menuLogoSmallHeight: number;
  /**
   * Whether a custom menu logo is in use. The sidebar crops the shipped wordmark with
   * negative margins to remove whitespace baked into that specific image — a crop that
   * would chop the bottom off an arbitrary customer logo.
   */
  hasCustomMenuLogoLarge: boolean;
  hasCustomMenuLogoSmall: boolean;
  /** Sign-in background URLs, in configured order. Empty means use the plain gradient. */
  loginBackgrounds: string[];
  /** Re-reads from the server; called by the admin page after an upload. */
  refresh: () => Promise<void>;
}

const BrandingContext = createContext<BrandingContextValue>({
  branding: null,
  loginLogo: DEFAULTS.loginLogo,
  menuLogoLarge: DEFAULTS.menuLogoLarge,
  menuLogoSmall: DEFAULTS.menuLogoSmall,
  loginLogoHeight: DEFAULT_HEIGHTS.loginLogo,
  menuLogoLargeHeight: DEFAULT_HEIGHTS.menuLogoLarge,
  menuLogoSmallHeight: DEFAULT_HEIGHTS.menuLogoSmall,
  hasCustomMenuLogoLarge: false,
  hasCustomMenuLogoSmall: false,
  loginBackgrounds: [],
  refresh: async () => {},
});

/**
 * Loads the white-label branding once, for the whole app.
 *
 * <p>Deliberately never blocks rendering: branding is decoration, and a slow or failed
 * fetch must not hold up the sign-in form. Until it resolves — and forever, if the request
 * fails — the shipped Faction defaults are what render.
 */
export function BrandingProvider({ children }: { children: ReactNode }) {
  const [branding, setBranding] = useState<Branding | null>(null);

  const load = useCallback(async () => {
    try {
      const res = await brandingApi.get();
      if (res.data) setBranding(res.data);
    } catch {
      // Leave the defaults in place. An instance that has never configured branding is
      // indistinguishable from one whose branding could not be fetched, and both should
      // look like Faction rather than like a broken page.
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const url = (assetId?: string | null, fallback?: string) =>
    assetId ? brandingApi.assetUrl(assetId) : (fallback ?? '');

  // Swapping the favicon means editing the document head — there is no React-owned
  // element for it. Keyed on the id so it re-runs when an admin uploads a new one.
  useEffect(() => {
    const href = url(branding?.faviconId, DEFAULTS.favicon);
    let link = document.querySelector<HTMLLinkElement>("link[rel~='icon']");
    if (!link) {
      link = document.createElement('link');
      link.rel = 'icon';
      document.head.appendChild(link);
    }
    // The shipped default is a PNG and so is every upload — the server only accepts
    // raster types, because an SVG favicon would be active content from this origin.
    link.type = 'image/png';
    link.href = href;
  }, [branding?.faviconId]);

  const value = useMemo<BrandingContextValue>(() => ({
    branding,
    loginLogo: url(branding?.loginLogoId, DEFAULTS.loginLogo),
    menuLogoLarge: url(branding?.menuLogoLargeId, DEFAULTS.menuLogoLarge),
    menuLogoSmall: url(branding?.menuLogoSmallId, DEFAULTS.menuLogoSmall),
    loginLogoHeight: branding?.loginLogoHeight ?? DEFAULT_HEIGHTS.loginLogo,
    menuLogoLargeHeight: branding?.menuLogoLargeHeight ?? DEFAULT_HEIGHTS.menuLogoLarge,
    menuLogoSmallHeight: branding?.menuLogoSmallHeight ?? DEFAULT_HEIGHTS.menuLogoSmall,
    hasCustomMenuLogoLarge: !!branding?.menuLogoLargeId,
    hasCustomMenuLogoSmall: !!branding?.menuLogoSmallId,
    loginBackgrounds: (branding?.loginBackgroundIds ?? []).map(id => brandingApi.assetUrl(id)),
    refresh: load,
  }), [branding, load]);

  return <BrandingContext.Provider value={value}>{children}</BrandingContext.Provider>;
}

export function useBranding() {
  return useContext(BrandingContext);
}

/**
 * Picks one sign-in background at random, once per mount of the sign-in page.
 *
 * <p>Chosen in a state initialiser rather than during render so it is stable across
 * re-renders — typing in the password field must not reshuffle the wallpaper. It is
 * re-picked when the list first arrives, because the page usually mounts before the
 * branding fetch resolves.
 */
export function useRandomLoginBackground(): string | null {
  const { loginBackgrounds } = useBranding();
  const [chosen, setChosen] = useState<string | null>(null);

  useEffect(() => {
    if (loginBackgrounds.length === 0) {
      setChosen(null);
      return;
    }
    setChosen(loginBackgrounds[Math.floor(Math.random() * loginBackgrounds.length)]);
    // Keyed on the joined list, not the array: a re-fetch producing the same images must
    // not count as a change and reshuffle what the visitor is already looking at.
  }, [loginBackgrounds.join('|')]);

  return chosen;
}

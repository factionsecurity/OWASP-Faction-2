/// <reference types="vite/client" />

/**
 * Which edition this bundle is, substituted at build time by vite.config.ts.
 * 'enterprise' when the paid overlay is compiled in, 'community' otherwise.
 */
declare const __FACTION_EDITION__: 'community' | 'enterprise';

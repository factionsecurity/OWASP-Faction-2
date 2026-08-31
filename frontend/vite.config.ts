import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath, URL } from 'node:url'

// The paid overlay is reached through one specifier, '@enterprise'.
//
// It resolves to a stub unless FACTION_ENTERPRISE_UI names the overlay package — so this
// project, on its own, is the open source edition, and there is no path by which a build
// here accidentally pulls in enterprise source. The private repo sets that variable to its
// enterprise/frontend/src. Mirrors the backend, where core is a standalone Maven project
// and the overlay is a separate module that depends on it.
const enterpriseDir = process.env.FACTION_ENTERPRISE_UI ?? './src/enterprise-stub'

// Which product this build is. Derived from the same signal that decides whether the
// overlay is compiled in, so the two can never disagree — a build carrying the paid
// screens always carries the paid identity with them.
//
// Build-time rather than runtime on purpose: the sign-in page paints its logo before
// anyone is authenticated, so there is no edition lookup to wait for.
const edition = process.env.FACTION_ENTERPRISE_UI ? 'enterprise' : 'community'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  define: {
    __FACTION_EDITION__: JSON.stringify(edition),
  },
  resolve: {
    // One copy of each, however it was reached. Without this the overlay's imports can
    // pull in a second React alongside the pre-bundled one.
    dedupe: ['react', 'react-dom'],
    alias: {
      '@enterprise': fileURLToPath(new URL(enterpriseDir, import.meta.url)),
      // The overlay lives outside this project, so it reaches core through an alias
      // rather than a '../' that would climb out of its own package.
      '@core': fileURLToPath(new URL('./src', import.meta.url)),

      // The overlay's sources sit outside this project, so Node resolution from them
      // never reaches our node_modules. These point its bare imports at the copy core
      // already has.
      //
      // React and react-dom are deliberately NOT in this list. Aliasing them to a raw
      // directory bypasses the package's `exports` map, so the alias resolves to a
      // different file than Vite's pre-bundled dependency — two React instances in one
      // page. Hooks and event delegation then break in a way that looks like nothing
      // more than "the dropdowns stopped working". `dedupe` below is the supported way
      // to guarantee a single copy.
      //
      // Adding another npm import to an overlay screen means adding it here; the build
      // fails with "Rollup failed to resolve" until you do.
      ...Object.fromEntries(
        ['lucide-react', 'recharts'].map((pkg) => [
          pkg,
          fileURLToPath(new URL(`./node_modules/${pkg}`, import.meta.url)),
        ]),
      ),
    },
  },
  server: {
    port: 3000,
    allowedHosts: 'all',
    // The overlay sits outside this project's root, and the dev server refuses to serve
    // files from outside it. Without this the paid screens 403 in `npm run dev` while the
    // production build is perfectly happy — a difference that only shows up at runtime.
    fs: {
      allow: process.env.FACTION_ENTERPRISE_UI
        ? ['.', fileURLToPath(new URL(process.env.FACTION_ENTERPRISE_UI, import.meta.url))]
        : ['.'],
    },
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // Swagger UI and its OpenAPI spec are served by the backend
      '/swagger-ui': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/v3/api-docs': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      }
    }
  }
})

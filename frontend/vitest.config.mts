import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Setup follows the guide shipped with this Next.js version
// (node_modules/next/dist/docs/01-app/02-guides/testing/vitest.md) rather than a
// remembered one: this is Next 16 on React 19, where the bundler has moved on too.
//
// The guide still lists `vite-tsconfig-paths`, but Vite 8 resolves tsconfig paths natively
// and warns that the plugin is redundant, so it is not used here. The `@/...` imports
// resolve through `resolve.tsconfigPaths` instead, and the dependency is not installed.
//
// Only synchronous components are unit tested. The same guide is explicit that Vitest
// cannot render async Server Components, so anything that awaits data belongs in the
// Playwright suite rather than being mocked into a shape that proves nothing.
export default defineConfig({
  plugins: [react()],
  resolve: {
    tsconfigPaths: true,
  },
  test: {
    environment: 'jsdom',
    globals: false,
    setupFiles: ['./vitest.setup.ts'],
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
    // The Playwright suite drives a real server, so it is kept out of `npm test` to keep
    // the unit loop fast.
    exclude: ['e2e/**', 'node_modules/**', '.next/**'],
  },
})

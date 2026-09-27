import { defineConfig, devices } from "@playwright/test";

/**
 * E2E for the one journey that matters: analyse a page, generate tests, run them, read
 * the per-test results, and act on a healing suggestion.
 *
 * The backend is stubbed at the network boundary rather than mocked in the page, so the
 * real `lib/*` clients, the real request bodies and the real response handling all run.
 * A journey test that stubs the API client itself would pass while the URL or the body
 * shape was wrong — which is exactly the class of bug B-033's migration could have
 * introduced and nothing would have caught.
 *
 * Not part of `npm test`: it needs a browser and a running server, so it is a separate
 * command wired into CI.
 */
const APP_PORT = Number(process.env.E2E_PORT ?? 3100);
const apiBase = process.env.E2E_API_BASE ?? `http://localhost:${Number(process.env.STUB_API_PORT ?? 3199)}`;
const STUB_API_PORT = Number(process.env.STUB_API_PORT ?? 3199);

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["github"], ["html", { open: "never" }]] : [["list"]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? `http://localhost:${APP_PORT}`,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    { name: "chromium", use: { ...devices["Desktop Chrome"] } },
  ],
  // Two servers, because the app is not a pure client: `app/projects/[id]` is a server
  // component that fetches during the server render, so a browser-only stub would leave
  // those pages empty and the suite would pass without exercising them.
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : [
        {
          command: "node e2e/stub-api.mjs",
          url: `http://localhost:${STUB_API_PORT}/api/v1/projects`,
          reuseExistingServer: !process.env.CI,
          timeout: 30_000,
        },
        {
          // `next build && next start` rather than `next dev`: dev recompiles on demand,
          // which makes a cold-start timeout look like a product failure and hides real
          // build errors from the suite.
          //
          // NEXT_PUBLIC_API_BASE_URL is passed at build time because Next inlines it; the
          // app also supports a runtime /config.js, but the build-time value is the one
          // that reaches the server render.
          // The variable is passed to BOTH commands. An env prefix applies only to the
          // command it precedes, and `/config.js` reads it at request time — so a build-only
          // prefix produces a client that falls back to localhost:8080 and every request
          // fails with "Failed to fetch" while the server render succeeds. That asymmetry
          // is exactly why this suite was worth writing against a real backend.
          command:
            `NEXT_PUBLIC_API_BASE_URL=${apiBase} npm run build` +
            ` && NEXT_PUBLIC_API_BASE_URL=${apiBase} npm run start -- --port ${APP_PORT}`,
          url: `http://localhost:${APP_PORT}/config.js`,
          reuseExistingServer: !process.env.CI,
          timeout: 240_000,
        },
      ],
});

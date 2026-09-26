/**
 * Runtime configuration.
 *
 * Resolution order for the API base URL:
 *   1. `window.__QALAB_CONFIG__.apiBaseUrl`, injected at runtime by the
 *      `/config.js` route (see `src/app/config.js/route.ts`).
 *   2. `NEXT_PUBLIC_API_BASE_URL`, inlined at build time.
 *   3. `http://localhost:8080`.
 *
 * Why runtime config exists: Next inlines `NEXT_PUBLIC_*` during `next build`, so
 * setting it on an already-built container is a no-op — one image could only ever
 * talk to the URL it was built with. The `/config.js` script is a plain
 * (non-deferred) script in the document head, so it executes before Next's
 * deferred bundles and before any client module is evaluated. That ordering is what
 * makes it safe for `API_BASE_URL` to be read at module scope below.
 */

declare global {
  interface Window {
    __QALAB_CONFIG__?: { apiBaseUrl?: string | null };
  }
}

const DEFAULT_API_BASE_URL = "http://localhost:8080";

function runtimeApiBaseUrl(): string {
  if (typeof window === "undefined") {
    return "";
  }
  const injected = window.__QALAB_CONFIG__?.apiBaseUrl;
  return typeof injected === "string" ? injected.trim() : "";
}

export const API_BASE_URL =
  runtimeApiBaseUrl() || process.env.NEXT_PUBLIC_API_BASE_URL || DEFAULT_API_BASE_URL;

/**
 * WebSocket endpoint for live agent status.
 *
 * Derived from the API base URL instead of hardcoded, so live progress works
 * against a remote backend. The scheme is mapped http->ws and https->wss,
 * because a page served over https cannot open a plaintext ws:// connection —
 * the browser blocks it as mixed content.
 */
export function agentWebSocketUrl(path = "/ws/agents", baseUrl: string = API_BASE_URL): string {
  const base = baseUrl.replace(/\/+$/, "");
  const wsBase = base.replace(/^http:/, "ws:").replace(/^https:/, "wss:");
  return `${wsBase}${path.startsWith("/") ? path : `/${path}`}`;
}

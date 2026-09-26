export const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080";

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

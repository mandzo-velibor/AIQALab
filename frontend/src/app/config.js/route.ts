import { NextResponse } from "next/server";

/**
 * Serves runtime configuration to the browser.
 *
 * Next.js inlines `NEXT_PUBLIC_*` at **build** time, so setting the variable on an
 * already-built container does nothing — the image always talked to the build-time
 * URL. This route is evaluated per request, so one image can be deployed to many
 * environments and configured with `QALAB_API_BASE_URL` (or the conventional
 * `API_BASE_URL`) at start time. A change requires only a container restart.
 *
 * Deliberately a route handler rather than a file written into `public/`: the
 * route is unambiguously dynamic, and does not depend on how the server treats
 * files added to `public/` after the build.
 */
export const dynamic = "force-dynamic";

export function GET() {
  const apiBaseUrl =
    process.env.QALAB_API_BASE_URL ||
    process.env.API_BASE_URL ||
    process.env.NEXT_PUBLIC_API_BASE_URL ||
    "";

  const body = `window.__QALAB_CONFIG__ = ${JSON.stringify({
    apiBaseUrl: apiBaseUrl || null,
  })};
`;

  return new NextResponse(body, {
    headers: {
      "Content-Type": "application/javascript; charset=utf-8",
      // Must not be cached, or a redeploy would keep serving the old backend URL.
      "Cache-Control": "no-store, no-cache, must-revalidate",
    },
  });
}

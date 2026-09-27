import { test, expect, type Page, type Request } from "@playwright/test";

/**
 * The main journeys, end to end, against a real stub backend (`e2e/stub-api.mjs`).
 *
 * The backend is a separate HTTP server rather than `page.route` interception, because
 * much of this app renders on the server: `app/projects/[id]/page.tsx` is a server
 * component that fetches the project, its history and its healing suggestions during the
 * server render. Browser-level interception never sees those requests, so a route-only
 * suite silently exercises an empty page and passes for the wrong reason.
 *
 * The assertions are on the wire as well as the pixels. A wrong URL or a missing `project`
 * object produces a 404 or a refusal at runtime while the page still renders, so a suite
 * that only checked for visible text would call that a pass — which is exactly the class
 * of bug B-033's migration could have introduced.
 */

const PAGE_URL = "https://the-internet.herokuapp.com/login";

/** Records API calls without intercepting them, so the stub server stays authoritative. */
function recordApiCalls(page: Page) {
  const calls: Array<{ method: string; url: string; body: unknown }> = [];
  page.on("request", (req: Request) => {
    const url = new URL(req.url());
    if (!url.pathname.startsWith("/api/")) return;
    let body: unknown = null;
    try {
      body = req.postData() ? JSON.parse(req.postData() as string) : null;
    } catch {
      body = req.postData();
    }
    calls.push({ method: req.method(), url: url.pathname, body });
  });
  return calls;
}

test.describe("the QA journey", () => {
  test("analyse a page, run the generated tests, then read the per-test results", async ({ page }) => {
    const calls = recordApiCalls(page);

    await page.goto(`/analyze?url=${encodeURIComponent(PAGE_URL)}&projectId=1`);
    await page.waitForLoadState("networkidle");

    // --- 1. analyse the page -------------------------------------------------
    // The waiter is armed before the click: `waitForRequest` only matches requests that
    // have not happened yet, so awaiting the click first races it and loses whenever the
    // request is fast.
    const analyzeCallPromise = page.waitForRequest((r) => r.url().includes("/api/v1/analyze"));
    await page.getByRole("button", { name: "Analyze" }).click();
    const analyzeCall = await analyzeCallPromise;

    const analyzeBody = JSON.parse(analyzeCall.postData() ?? "{}");
    expect(analyzeBody.url, "the analysed url must be the one the user was given").toBe(PAGE_URL);
    // The project object is what cost and usage are attributed to. A flat projectId is
    // silently ignored and the server refuses the request as missing context.
    expect(analyzeBody.project, "analyse must carry project identity").toBeTruthy();
    expect(analyzeBody.project.projectId, "v1 identifies a project by a string id").toBe("1");

    await expect(page.getByText(/login form/i).first()).toBeVisible();

    // --- 2. run the generated tests ------------------------------------------
    const runCallPromise = page.waitForRequest((r) => r.url().includes("/api/v1/run"));
    await page.getByRole("button", { name: "Run All Tests" }).first().click();
    const runCall = await runCallPromise;

    const runBody = JSON.parse(runCall.postData() ?? "{}");
    expect(runBody.runAll, "the run-all button must actually run everything").toBe(true);
    expect(runBody.project, "a run must carry project identity for budget attribution").toBeTruthy();

    // --- 3. read the per-test results ---------------------------------------
    // B-031's contribution, and the reason a user opens the dashboard at all: which test
    // broke, what it expected, and what it got.
    await expect(page.getByText("login.spec.ts").first()).toBeVisible();
    await page.getByRole("button", { name: "Details" }).first().click();

    await expect(page.getByText("should log in with valid credentials")).toBeVisible();
    await expect(page.getByText("should reject a bad password")).toBeVisible();
    await expect(page.getByText("expected 'Dashboard' but received 'Login'").first()).toBeVisible();
    // The artifact path is rendered rather than linked, because serving artifacts over
    // HTTP is still waiting on the access model (B-031).
    await expect(page.getByText("/tmp/run/report.html")).toBeVisible();

    expect(calls.some((c) => c.url === "/api/v1/executions/77/results")).toBe(true);
  });

  test("approve a healing suggestion from the project page", async ({ page }) => {
    // The healing dashboard is rendered on the project page, which is a server component
    // — this is the journey that a route-only stub cannot reach at all.
    const calls = recordApiCalls(page);
    await page.goto("/projects/1");
    await page.waitForLoadState("networkidle");

    // The old and new locator are on screen before any decision, so the change can be
    // judged rather than trusted.
    await expect(page.getByText("#email")).toBeVisible();
    await expect(page.getByText("getByLabel('Email')")).toBeVisible();

    const approveCallPromise = page.waitForRequest((r) =>
      r.url().includes("/api/v1/healing/suggestions/5/approve"),
    );
    await page.getByRole("button", { name: "Approve" }).first().click();
    await approveCallPromise;

    // Approve must not quietly become apply. Apply rewrites the generated test source, so
    // a human decision silently turning into a code change is the worst thing this panel
    // could do.
    expect(calls.some((c) => c.url.endsWith("/5/apply"))).toBe(false);
    expect(calls.some((c) => c.url.endsWith("/5/approve"))).toBe(true);
  });

  test("a failing analysis is reported rather than shown as an empty page", async ({ page }) => {
    // The one case that needs interception: injecting a failure into a real server would
    // mean the stub growing a fault-injection mode, and the point here is the UI's
    // behaviour, not the stub's flexibility.
    await page.route("**/api/v1/analyze", (route) =>
      route.fulfill({
        status: 400,
        contentType: "application/json",
        body: JSON.stringify({
          error: {
            code: "INVALID_REQUEST",
            message: "net::ERR_CONNECTION_REFUSED",
            operationId: "op-e2e-err",
          },
        }),
      }),
    );

    await page.goto(`/analyze?url=${encodeURIComponent(PAGE_URL)}&projectId=1`);
    const analyzePromise = page.waitForRequest((r) => r.url().includes("/api/v1/analyze"));
    await page.getByRole("button", { name: "Analyze" }).click();
    await analyzePromise;

    // A failure that renders as an empty result is indistinguishable from a blank page,
    // and the user has no reason to suspect the browser rather than the page.
    await expect(page.getByText(/ERR_CONNECTION_REFUSED/).first()).toBeVisible();
  });
});

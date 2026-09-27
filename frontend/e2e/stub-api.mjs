/**
 * Stub backend for the E2E suite.
 *
 * A real HTTP server rather than `page.route` interception, because most of this app's data
 * is fetched from **server components** — `app/projects/[id]/page.tsx` calls `getProject`,
 * `getProjectHistory` and `getSuggestions` during the server render. Browser-level route
 * interception never sees those requests, so a `page.route`-only suite silently exercises
 * a page with no data on it and passes for the wrong reason.
 *
 * The fixtures are the same ones the unit tests use, so a shape that drifts between the
 * two suites fails here rather than rendering an empty dashboard.
 */
import { createServer } from "node:http";

const PORT = Number(process.env.STUB_API_PORT ?? 3199);
const PAGE_URL = "https://the-internet.herokuapp.com/login";

const analysisBody = {
  pageType: "login",
  summary: "A login form with a submit button.",
  confidence: 88,
  forms: [{ name: "login", inputs: ["username", "password"] }],
  buttons: ["Login"],
  navigation: [],
  dialogs: [],
  tables: [],
  possibleFlows: [{ name: "Login", description: "enter credentials and submit" }],
  riskAreas: [],
  screenshotPath: "/tmp/shot.png",
};

const exploreResponse = {
  operationId: "op-e2e-1",
  status: "COMPLETED",
  projectId: "1",
  url: PAGE_URL,
  title: "Login Page",
  pageType: "login",
  buttonCount: 1,
  inputCount: 2,
  linkCount: 0,
  formCount: 1,
  screenshotBase64: null,
  createdAt: "2026-09-27T10:00:00",
  agentResults: {},
};

const project = {
  id: 1,
  name: "The Internet Tests",
  description: "Stub project",
  baseUrl: "https://the-internet.herokuapp.com",
  repositoryUrl: "",
  framework: "PLAYWRIGHT_TYPESCRIPT",
  workspacePath: null,
  createdAt: "2026-09-27T09:00:00",
  updatedAt: "2026-09-27T09:00:00",
};

const execution = {
  id: 77,
  projectId: 1,
  testFile: "login.spec.ts",
  status: "FAILED",
  duration: 4200,
  errorMessage: "expected 'Dashboard' but received 'Login'",
  screenshotPath: null,
  videoPath: null,
  tracePath: null,
  consoleLogs: null,
  createdAt: "2026-09-27T10:00:00",
};

const executionResults = {
  executionId: 77,
  status: "FAILED",
  durationMs: 4200,
  reportPath: "/tmp/run/report.json",
  htmlReport: "/tmp/run/report.html",
  totalCount: 2,
  passedCount: 1,
  failedCount: 1,
  skippedCount: 0,
  tests: [
    {
      ordinal: 1,
      file: "login.spec.ts",
      title: "should log in with valid credentials",
      status: "PASSED",
      durationMs: 1200,
      retries: 0,
      error: null,
      hasEvidence: false,
      screenshots: [],
      videos: [],
      traces: [],
    },
    {
      ordinal: 2,
      file: "login.spec.ts",
      title: "should reject a bad password",
      status: "FAILED",
      durationMs: 3000,
      retries: 1,
      error: "expected 'Dashboard' but received 'Login'",
      hasEvidence: true,
      screenshots: ["tests/2-bad-password/shot.png"],
      videos: [],
      traces: ["tests/2-bad-password/trace.zip"],
    },
  ],
};

const projectHistory = {
  executions: [execution],
  pageAnalyses: [],
  locatorHistory: [],
  failureAnalyses: [],
  healingSuggestions: [],
};

const suggestion = {
  id: 5,
  projectId: 1,
  elementName: "email",
  oldLocator: "#email",
  newLocator: "getByLabel('Email')",
  confidence: 91,
  status: "PENDING",
  reason: "the label is stable across renders",
  approvedBy: null,
  approvedAt: null,
};

/** Every stubbed route, so an unhandled path is a 501 with a message rather than a 404. */
function route(method, path) {
  if (method === "POST" && path === "/api/v1/analyze") return analysisBody;
  if (method === "POST" && path === "/api/v1/explore") return exploreResponse;

  if (path === "/api/v1/locators") {
    if (method === "GET") return [];
    return { generated: 0, locators: [], instruction: null, strategiesUsed: [] };
  }
  if (method === "POST" && path === "/api/v1/test-plan") {
    return { operationId: "op-plan", status: "COMPLETED", projectId: "1", url: PAGE_URL, scenarioCount: 0, scenarios: [], instruction: null, createdAt: "2026-09-27T10:00:00" };
  }
  if (path === "/api/v1/test-plans") return [];
  if (path === "/api/v1/tests") {
    if (method === "GET") return [];
    return { generated: 0, tests: [], instruction: null };
  }
  if (method === "POST" && path === "/api/v1/run") {
    return { operationId: "op-run", status: "COMPLETED", executionId: 77, testId: null, status_: undefined, state: "COMPLETED", result: { executionId: 77, status: "FAILED", durationMs: 4200, errorMessage: null, consoleLogs: "" }, healing: null, createdAt: "2026-09-27T10:00:00" };
  }
  if (path === "/api/v1/executions") return [execution];
  if (path === "/api/v1/executions/77/results") return executionResults;

  if (path === "/api/v1/healing/suggestions") return [suggestion];
  if (path === "/api/v1/healing/suggestions/5/approve") return { ...suggestion, status: "APPROVED" };
  if (path === "/api/v1/healing/suggestions/5/reject") return { ...suggestion, status: "REJECTED" };
  if (path === "/api/v1/healing/suggestions/5/apply") return { ...suggestion, status: "APPLIED" };
  if (path === "/api/v1/healing/suggestions/analyze/77") return suggestion;

  if (path === "/api/v1/projects") return [project];
  if (path === "/api/v1/projects/1") return project;
  if (path === "/api/v1/projects/1/history") return projectHistory;

  if (path === "/api/v1/account/usage") {
    return { totalInputTokens: 1200, totalOutputTokens: 400, totalCost: 0.01, callCount: 3, byModel: [] };
  }
  if (path === "/api/v1/account/budget-policy") {
    return { monthlyLimitUsd: 50, spentUsd: 0.01 };
  }
  if (path === "/api/v1/reports/77") {
    return { operationId: "op-report", status: "COMPLETED", executionId: 77, htmlReport: "/tmp/run/report.html" };
  }

  return undefined;
}

/**
 * CORS, because the browser talks to this server cross-origin (app on :3100, stub on
 * :3199). The real backend has Spring's CORS handling, so omitting it here would produce
 * a "Failed to fetch" that looks like a product bug and is not one. The requests carry
 * `Content-Type: application/json` and `Authorization`, so they are preflighted.
 */
const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET,POST,PUT,PATCH,DELETE,OPTIONS",
  "access-control-allow-headers": "Content-Type,Authorization,X-Operation-Id",
  "access-control-max-age": "600",
};

const server = createServer((req, res) => {
  const path = new URL(req.url ?? "/", `http://localhost:${PORT}`).pathname;
  const method = req.method ?? "GET";

  if (method === "OPTIONS") {
    res.writeHead(204, CORS);
    res.end();
    return;
  }

  for (const [header, value] of Object.entries(CORS)) {
    res.setHeader(header, value);
  }

  const body = route(method, path);
  if (body === undefined) {
    // Loud on purpose. A silent 404 here would surface in the UI as "no results", which
    // reads as a product state rather than as a missing stub.
    res.writeHead(501, { "content-type": "application/json" });
    res.end(JSON.stringify({ error: { code: "UNSTUBBED", message: `no stub for ${method} ${path}` } }));
    return;
  }

  res.writeHead(200, { "content-type": "application/json" });
  res.end(JSON.stringify(body));
});

server.listen(PORT, () => {
  process.stdout.write(`stub API listening on http://localhost:${PORT}\n`);
});

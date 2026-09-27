import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { ApiError, httpRequest, toApiError, friendlyErrorLabel, isAiFallbackError } from "@/lib/http"
import { getProjects, createProject, getProject } from "@/lib/project-api"
import { getExecutionResults, getExecutionHistory } from "@/lib/execution-api"
import { approveSuggestion, rejectSuggestion, applySuggestion, getSuggestions } from "@/lib/healing-api"
import { generateLocators, getLocators } from "@/lib/locator-api"
import { generateTestPlan } from "@/lib/testplan-api"
import { generateTests } from "@/lib/testgen-api"
import { analyzeUrl } from "@/lib/analysis-api"
import { exploreUrl } from "@/lib/api"

/**
 * The API clients are the layer where a mistake is invisible until a user clicks the
 * wrong button. B-033 moved every one of these from `/api/*` to `/api/v1/*` and changed
 * request bodies from a flat `projectId` to a `project` object, so these tests exist to
 * make that migration impossible to silently undo.
 *
 * A wrong path here is the failure mode with no safety net: the app builds, typechecks,
 * lints clean, and 404s at runtime.
 */

const BASE = "http://localhost:8080"

/** Captures what was actually sent, and replies with whatever is queued. */
function stubFetch(...responses: Array<{ status?: number; body?: unknown }>) {
  const calls: Array<{ url: string; init?: RequestInit }> = []
  const queue = [...responses]

  const impl = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
    calls.push({ url: String(url), init })
    const next = queue.length > 1 ? queue.shift()! : queue[0]!
    const status = next.status ?? 200
    return {
      ok: status >= 200 && status < 300,
      status,
      json: async () => next.body ?? {},
    } as Response
  })

  vi.stubGlobal("fetch", impl)
  return calls
}

function bodyOf(call: { init?: RequestInit }): Record<string, unknown> {
  return JSON.parse((call.init?.body as string) ?? "{}")
}

beforeEach(() => {
  vi.stubGlobal("window", { __QALAB_CONFIG__: { apiBaseUrl: BASE } })
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.resetModules()
})

describe("httpRequest", () => {
  it("returns the response untouched on success", async () => {
    stubFetch({ body: { ok: true } })
    const res = await httpRequest(`${BASE}/api/v1/projects`)
    expect(res.ok).toBe(true)
  })

  it("raises a structured ApiError carrying the code, message and operationId", async () => {
    stubFetch({
      status: 429,
      body: {
        error: {
          code: "AI_BUDGET_EXCEEDED",
          message: "Monthly budget reached",
          operationId: "op-abc123",
        },
      },
    })

    // The operationId is what lets a user hand a failure to whoever can trace it, so it
    // must survive the trip from the API into the error object the UI renders.
    await expect(httpRequest(`${BASE}/api/v1/run`)).rejects.toMatchObject({
      name: "ApiError",
      status: 429,
      code: "AI_BUDGET_EXCEEDED",
      message: "Monthly budget reached",
      operationId: "op-abc123",
    })
  })

  it("falls back to a status-derived message when the body is not JSON", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => ({
      ok: false,
      status: 502,
      json: async () => { throw new SyntaxError("Unexpected token < in JSON") },
    } as unknown as Response)))

    // A proxy returning an HTML error page must not turn into an unhandled rejection.
    await expect(httpRequest(`${BASE}/api/v1/run`)).rejects.toMatchObject({
      status: 502,
      code: "HTTP_502",
    })
  })
})

describe("error presentation", () => {
  it("maps known codes to something a QA user can act on", () => {
    expect(friendlyErrorLabel("AI_PROVIDER_NOT_CONFIGURED")).toBe("AI provider not configured")
    expect(friendlyErrorLabel("SOMETHING_NEW")).toBe("Request failed")
  })

  it("recognises the AI failures that should fall back rather than be retried blindly", () => {
    expect(isAiFallbackError("AI_RATE_LIMITED")).toBe(true)
    expect(isAiFallbackError("INVALID_REQUEST")).toBe(false)
  })

  it("wraps a non-API error without losing its message", () => {
    const wrapped = toApiError(new Error("network down"), "fallback")
    expect(wrapped).toBeInstanceOf(ApiError)
    expect(wrapped.message).toBe("network down")

    const alreadyApi = new ApiError(400, "INVALID_REQUEST", "bad")
    expect(toApiError(alreadyApi, "fallback")).toBe(alreadyApi)
  })
})

describe("project client", () => {
  it("reads projects from the v1 surface", async () => {
    const calls = stubFetch({ body: [{ id: 1, name: "The Internet Tests" }] })

    const projects = await getProjects()

    expect(calls[0].url).toBe(`${BASE}/api/v1/projects`)
    expect(projects).toHaveLength(1)
  })

  it("creates a project with the documented body", async () => {
    const calls = stubFetch({ body: { id: 2, name: "New" } })

    await createProject({ name: "New", baseUrl: "https://x.test", framework: "PLAYWRIGHT" } as never)

    expect(calls[0].url).toBe(`${BASE}/api/v1/projects`)
    expect(calls[0].init?.method).toBe("POST")
    expect(bodyOf(calls[0]).name).toBe("New")
  })

  it("fetches one project by id", async () => {
    const calls = stubFetch({ body: { id: 7 } })
    await getProject(7)
    expect(calls[0].url).toBe(`${BASE}/api/v1/projects/7`)
  })
})

describe("execution client", () => {
  it("reads per-test results from v1", async () => {
    // Shipped in B-031 on the legacy surface and moved in B-033. If it regressed to
    // /api/executions the dashboard would show an empty results table.
    const calls = stubFetch({ body: { total: 2, tests: [] } })

    await getExecutionResults(42)

    expect(calls[0].url).toBe(`${BASE}/api/v1/executions/42/results`)
  })

  it("scopes execution history to a project when one is given", async () => {
    const calls = stubFetch({ body: [] })

    await getExecutionHistory(9)
    expect(calls[0].url).toBe(`${BASE}/api/v1/executions?projectId=9`)

    await getExecutionHistory()
    expect(calls[1].url).toBe(`${BASE}/api/v1/executions`)
  })
})

describe("healing client", () => {
  it("sends the three review actions to the v1 suggestion endpoints", async () => {
    const calls = stubFetch({ body: { id: 1, status: "APPROVED" } })

    await approveSuggestion(1)
    await rejectSuggestion(1)
    await applySuggestion(1)

    // `apply` is the one capability v1's proposal surface has no equivalent for; it
    // rewrites the generated test source, so losing it silently disables the dashboard's
    // most consequential button.
    expect(calls.map((c) => c.url)).toEqual([
      `${BASE}/api/v1/healing/suggestions/1/approve`,
      `${BASE}/api/v1/healing/suggestions/1/reject`,
      `${BASE}/api/v1/healing/suggestions/1/apply`,
    ])
  })

  it("scopes the suggestion list to a project when given", async () => {
    const calls = stubFetch({ body: [] })
    await getSuggestions(5)
    expect(calls[0].url).toBe(`${BASE}/api/v1/healing/suggestions?projectId=5`)
  })
})

describe("write clients send a project object, not a flat projectId", () => {
  // v1 identifies a project by an object so cost and usage can be attributed. A flat
  // projectId is silently ignored by the server, which then refuses the request as
  // missing project context — the request looks valid and fails anyway.
  it("generate locators", async () => {
    const calls = stubFetch({ body: { generated: 1, locators: [] } })
    await generateLocators("https://x.test", 12, "focus on login")
    const body = bodyOf(calls[0])
    expect(calls[0].url).toBe(`${BASE}/api/v1/locators`)
    expect(body.project).toEqual({ projectId: "12", databaseId: 12 })
  })

  it("generate test plan", async () => {
    const calls = stubFetch({ body: { scenarios: [] } })
    await generateTestPlan("https://x.test", 12)
    expect(calls[0].url).toBe(`${BASE}/api/v1/test-plan`)
    expect(bodyOf(calls[0]).project).toEqual({ projectId: "12", databaseId: 12 })
  })

  it("generate tests", async () => {
    const calls = stubFetch({ body: { tests: [] } })
    await generateTests("https://x.test", 12)
    expect(calls[0].url).toBe(`${BASE}/api/v1/tests`)
    expect(bodyOf(calls[0]).project).toEqual({ projectId: "12", databaseId: 12 })
  })

  it("analyze sends the project and unwraps the v1 envelope", async () => {
    // v1 nests the payload under `analysis`; returning the envelope as though it were
    // the analysis made every field undefined, and the panel then threw on
    // `result.forms.length`. That went unnoticed because this stub also returned a flat
    // body — the test encoded the same wrong contract as the bug.
    const analysis = { pageType: "login", forms: [], buttons: ["Login"] };
    const calls = stubFetch({
      body: {
        operationId: "op-1",
        status: "COMPLETED",
        projectId: "12",
        url: "https://x.test",
        analysis,
        createdAt: "2026-09-27T10:00:00",
      },
    })

    const result = await analyzeUrl("https://x.test", false, 12)

    expect(calls[0].url).toBe(`${BASE}/api/v1/analyze`)
    expect(bodyOf(calls[0]).project).toEqual({ projectId: "12", databaseId: 12 })
    // Callers must receive the analysis, not the envelope.
    expect(result).toEqual(analysis)
  })

  it("analyze rejects an envelope with no analysis rather than returning undefined fields", async () => {
    stubFetch({ body: { operationId: "op-1", status: "COMPLETED" } })

    // Silently returning the envelope would put undefined arrays in front of a component
    // that calls .length on them, which is the crash this whole fix is about.
    await expect(analyzeUrl("https://x.test", false, 12))
      .rejects.toThrow(/no analysis payload/i)
  })

  it("falls back to an anonymous project when the caller has none", async () => {
    // The URL-only explore box has no project in context. The request must still carry a
    // project object, because the server rejects one that is missing outright.
    const calls = stubFetch({ body: { title: "t" } })
    await exploreUrl("https://x.test")
    expect(bodyOf(calls[0]).project).toEqual({ projectId: "default", databaseId: null })
  })
})

describe("read clients", () => {
  it("encodes the url into the query string", async () => {
    // An unencoded url would truncate the query at the first ? or & of the target page,
    // quietly fetching the wrong thing or nothing.
    const calls = stubFetch({ body: [] })
    await getLocators("https://x.test/a?b=c&d=e")
    expect(calls[0].url).toBe(`${BASE}/api/v1/locators?url=https%3A%2F%2Fx.test%2Fa%3Fb%3Dc%26d%3De`)
  })
})

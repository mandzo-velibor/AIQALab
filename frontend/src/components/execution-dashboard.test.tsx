import { describe, expect, it, vi, beforeEach } from "vitest"
import { render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { ExecutionDashboard } from "@/components/execution-dashboard"
import type { ExecutionResults, TestExecution } from "@/lib/execution-api"

vi.mock("@/lib/execution-api", () => ({
  getExecutionResults: vi.fn(),
}))

import { getExecutionResults } from "@/lib/execution-api"

const getResults = vi.mocked(getExecutionResults)

function execution(overrides: Partial<TestExecution> = {}): TestExecution {
  return {
    id: 1,
    projectId: 1,
    testFile: "login.spec.ts",
    status: "FAILED",
    duration: 4200,
    errorMessage: "expect(received).toBe('Dashboard')",
    screenshotPath: null,
    videoPath: null,
    tracePath: null,
    consoleLogs: null,
    createdAt: "2026-09-27T10:00:00",
    ...overrides,
  } as TestExecution
}

function results(overrides: Partial<ExecutionResults> = {}): ExecutionResults {
  return {
    executionId: 1,
    status: "FAILED",
    durationMs: 4200,
    reportPath: "/tmp/run/report.json",
    htmlReport: "/tmp/run/report.html",
    totalCount: 3,
    passedCount: 2,
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
    ...overrides,
  } as ExecutionResults
}

/** Renders with the props the component actually requires, so a missing one cannot be mistaken for a bug. */
function renderDashboard(executions: TestExecution[]) {
  return render(
    <ExecutionDashboard executions={executions} loading={false} onRunAll={vi.fn()} />,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  getResults.mockResolvedValue(results())
})

describe("ExecutionDashboard", () => {
  it("explains what to do when nothing has run yet", () => {
    // The copy wraps a quoted button name, so the text is split across elements and a node
    // matcher cannot see the sentence. Asserting on the rendered text is both robust and
    // closer to what the user actually reads.
    const { container } = renderDashboard([])
    expect(container.textContent).toContain("No executions yet")
  })

  it("does not fetch results until a row is expanded", () => {
    // A project with hundreds of executions would otherwise load every test of every run
    // on one page. Fetching is deliberately tied to the expand action.
    renderDashboard([execution()])
    expect(getResults).not.toHaveBeenCalled()
  })

  it("loads per-test results when a row is expanded", async () => {
    const user = userEvent.setup()
    renderDashboard([execution()])

    await user.click(screen.getByRole("button", { name: "Details" }))

    await waitFor(() => expect(getResults).toHaveBeenCalledWith(1))
    // This is B-031's contribution: the per-test table is what answers "which test broke".
    expect(await screen.findByText("should log in with valid credentials")).toBeDefined()
    expect(screen.getByText("should reject a bad password")).toBeDefined()
  })

  it("shows the assertion failure, which is the reason the user opened it", async () => {
    const user = userEvent.setup()
    renderDashboard([execution()])

    await user.click(screen.getByRole("button", { name: "Details" }))

    // Rendered twice on purpose: a truncated first line for scanning, then the full
    // message. Asserting a single match would fail on correct behaviour.
    const occurrences = await screen.findAllByText(/expected 'Dashboard' but received 'Login'/)
    expect(occurrences.length).toBeGreaterThan(0)
  })

  it("surfaces the report path so the artifact is reachable", async () => {
    const user = userEvent.setup()
    renderDashboard([execution()])

    await user.click(screen.getByRole("button", { name: "Details" }))

    // The path is shown rather than linked, because serving artifacts over HTTP is still
    // waiting on the access model (B-031). Rendering it is what makes the report findable.
    expect(await screen.findByText("/tmp/run/report.html")).toBeDefined()
  })

  it("reports a failure to load results rather than showing an empty panel", async () => {
    getResults.mockRejectedValue(new Error("404 Not Found"))
    const user = userEvent.setup()
    renderDashboard([execution()])

    await user.click(screen.getByRole("button", { name: "Details" }))

    // An empty panel would read as "this run had no tests", which is a different and wrong
    // conclusion from "the results could not be fetched".
    expect(await screen.findByText(/Could not load test results/i)).toBeDefined()
  })

  it("collapses and does not refetch", async () => {
    const user = userEvent.setup()
    renderDashboard([execution()])

    await user.click(screen.getByRole("button", { name: "Details" }))
    await waitFor(() => expect(getResults).toHaveBeenCalledTimes(1))

    await user.click(screen.getByRole("button", { name: "Collapse" }))

    expect(getResults).toHaveBeenCalledTimes(1)
    expect(screen.queryByText("should log in with valid credentials")).toBeNull()
  })

  it("renders one row per execution", () => {
    renderDashboard([
      execution({ id: 1, testFile: "a.spec.ts" }),
      execution({ id: 2, testFile: "b.spec.ts" }),
    ])
    expect(screen.getByText("a.spec.ts")).toBeDefined()
    expect(screen.getByText("b.spec.ts")).toBeDefined()
  })

  it("does not show a zero duration for a run that has none", () => {
    // `duration` is nullable, and rendering "0ms" would claim the run was instant.
    renderDashboard([execution({ duration: null })])
    expect(screen.queryByText("0ms")).toBeNull()
  })
})

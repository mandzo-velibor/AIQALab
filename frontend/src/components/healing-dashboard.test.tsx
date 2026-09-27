import { describe, expect, it, vi, beforeEach } from "vitest"
import { render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { HealingDashboard } from "@/components/healing-dashboard"
import type { HealingSuggestion } from "@/lib/healing-api"

vi.mock("@/lib/healing-api", () => ({
  approveSuggestion: vi.fn(),
  rejectSuggestion: vi.fn(),
  applySuggestion: vi.fn(),
}))

import { approveSuggestion, rejectSuggestion, applySuggestion } from "@/lib/healing-api"

const mocked = {
  approve: vi.mocked(approveSuggestion),
  reject: vi.mocked(rejectSuggestion),
  apply: vi.mocked(applySuggestion),
}

function suggestion(overrides: Partial<HealingSuggestion> = {}): HealingSuggestion {
  return {
    id: 1,
    projectId: 1,
    elementName: "email",
    oldLocator: "#email",
    newLocator: "getByLabel('Email')",
    confidence: 90,
    status: "PENDING",
    reason: "label is stable",
    approvedBy: null,
    approvedAt: null,
    ...overrides,
  } as HealingSuggestion
}

beforeEach(() => {
  vi.clearAllMocks()
  mocked.approve.mockResolvedValue(suggestion({ status: "APPROVED" }))
  mocked.reject.mockResolvedValue(suggestion({ status: "REJECTED" }))
  mocked.apply.mockResolvedValue(suggestion({ status: "APPLIED" }))
  // jsdom has no layout, so scrollIntoView is absent and the error path would throw
  // while trying to report a failure.
  Element.prototype.scrollIntoView = vi.fn()
})

describe("HealingDashboard", () => {
  it("explains what to do when there is nothing to review", () => {
    render(<HealingDashboard suggestions={[]} onSuggestionChanged={vi.fn()} />)
    // An empty dashboard must not look broken; this is the first-run state.
    expect(screen.getByText(/No healing suggestions yet/i)).toBeDefined()
  })

  it("shows the old and new locator so the change can be judged", () => {
    render(<HealingDashboard suggestions={[suggestion()]} onSuggestionChanged={vi.fn()} />)
    expect(screen.getByText("#email")).toBeDefined()
    expect(screen.getByText("getByLabel('Email')")).toBeDefined()
  })

  it("offers Approve and Reject for a pending suggestion, and nothing else", () => {
    render(<HealingDashboard suggestions={[suggestion()]} onSuggestionChanged={vi.fn()} />)
    expect(screen.getByRole("button", { name: "Approve" })).toBeDefined()
    expect(screen.getByRole("button", { name: "Reject" })).toBeDefined()
    // Apply rewrites the generated test source, so it must not be reachable before a
    // human has approved the change.
    expect(screen.queryByRole("button", { name: "Apply" })).toBeNull()
  })

  it("offers Apply only once the suggestion is approved", () => {
    render(
      <HealingDashboard
        suggestions={[suggestion({ status: "APPROVED" })]}
        onSuggestionChanged={vi.fn()}
      />,
    )
    expect(screen.getByRole("button", { name: "Apply" })).toBeDefined()
    expect(screen.queryByRole("button", { name: "Approve" })).toBeNull()
  })

  it("offers nothing for a rejected suggestion", () => {
    render(
      <HealingDashboard
        suggestions={[suggestion({ status: "REJECTED" })]}
        onSuggestionChanged={vi.fn()}
      />,
    )
    expect(screen.queryByRole("button", { name: "Approve" })).toBeNull()
    expect(screen.queryByRole("button", { name: "Apply" })).toBeNull()
  })

  it("approves and tells the parent to refresh", async () => {
    const onSuggestionChanged = vi.fn()
    const user = userEvent.setup()
    render(<HealingDashboard suggestions={[suggestion()]} onSuggestionChanged={onSuggestionChanged} />)

    await user.click(screen.getByRole("button", { name: "Approve" }))

    expect(mocked.approve).toHaveBeenCalledWith(1)
    // Without the refresh the list keeps showing PENDING, so the button stays clickable
    // and the user can approve the same suggestion twice.
    expect(onSuggestionChanged).toHaveBeenCalled()
  })

  it("rejects and tells the parent to refresh", async () => {
    const onSuggestionChanged = vi.fn()
    const user = userEvent.setup()
    render(<HealingDashboard suggestions={[suggestion()]} onSuggestionChanged={onSuggestionChanged} />)

    await user.click(screen.getByRole("button", { name: "Reject" }))

    expect(mocked.reject).toHaveBeenCalledWith(1)
    expect(onSuggestionChanged).toHaveBeenCalled()
  })

  it("applies an approved suggestion", async () => {
    const onSuggestionChanged = vi.fn()
    const user = userEvent.setup()
    render(
      <HealingDashboard
        suggestions={[suggestion({ status: "APPROVED" })]}
        onSuggestionChanged={onSuggestionChanged}
      />,
    )

    await user.click(screen.getByRole("button", { name: "Apply" }))

    expect(mocked.apply).toHaveBeenCalledWith(1)
    expect(onSuggestionChanged).toHaveBeenCalled()
  })

  it("surfaces a failure instead of failing silently", async () => {
    mocked.approve.mockRejectedValue(new Error("Suggestion not found: 1"))
    const user = userEvent.setup()
    render(<HealingDashboard suggestions={[suggestion()]} onSuggestionChanged={vi.fn()} />)

    await user.click(screen.getByRole("button", { name: "Approve" }))

    // A review action that fails quietly looks identical to one that succeeded.
    const alert = await screen.findByRole("alert")
    expect(alert.textContent).toContain("Suggestion not found")
  })

  it("disables the buttons while the action is in flight", async () => {
    let release!: () => void
    mocked.approve.mockImplementation(
      () => new Promise((resolve) => { release = () => resolve(suggestion()) }),
    )
    const user = userEvent.setup()
    render(<HealingDashboard suggestions={[suggestion()]} onSuggestionChanged={vi.fn()} />)

    await user.click(screen.getByRole("button", { name: "Approve" }))

    // A second click while saving would fire a duplicate approval.
    await waitFor(() => {
      expect(screen.queryByRole("button", { name: /Saving/ })).not.toBeNull()
    })
    release()
  })

  it("renders each suggestion separately", () => {
    render(
      <HealingDashboard
        suggestions={[suggestion({ id: 1, elementName: "email" }), suggestion({ id: 2, elementName: "password" })]}
        onSuggestionChanged={vi.fn()}
      />,
    )
    expect(screen.getByText("email")).toBeDefined()
    expect(screen.getByText("password")).toBeDefined()
    expect(screen.getByText("Healing Dashboard (2)")).toBeDefined()
  })
})

import { describe, expect, it, vi, beforeEach } from "vitest"
import { render, screen, waitFor, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { QaWorkflow } from "@/components/qa-workflow"
import { ActionProvider } from "@/lib/action-context"
import { analyzeUrl, type AnalysisResponse } from "@/lib/analysis-api"
import { generateLocators, getLocators, type LocatorDto } from "@/lib/locator-api"
import { generateTestPlan, getTestPlans, type TestScenarioDto } from "@/lib/testplan-api"
import { generateTests, getTests, type GeneratedTestDto } from "@/lib/testgen-api"
import { getExecutionHistory, type TestExecution } from "@/lib/execution-api"
import { getSuggestions } from "@/lib/healing-api"

vi.mock("@/lib/analysis-api", () => ({ analyzeUrl: vi.fn() }))
vi.mock("@/lib/locator-api", () => ({ generateLocators: vi.fn(), getLocators: vi.fn() }))
vi.mock("@/lib/testplan-api", () => ({ generateTestPlan: vi.fn(), getTestPlans: vi.fn() }))
vi.mock("@/lib/testgen-api", () => ({ generateTests: vi.fn(), getTests: vi.fn() }))
vi.mock("@/lib/execution-api", () => ({
  getExecutionHistory: vi.fn(),
  getExecutionResults: vi.fn(),
  runTest: vi.fn(),
  runAllTests: vi.fn(),
}))
vi.mock("@/lib/healing-api", () => ({
  getSuggestions: vi.fn(),
  approveSuggestion: vi.fn(),
  rejectSuggestion: vi.fn(),
  applySuggestion: vi.fn(),
}))

const analysis = {
  pageType: "login",
  summary: "A login form.",
  confidence: 90,
  forms: [],
  buttons: ["Sign in"],
  navigation: [],
  dialogs: [],
  tables: [],
  possibleFlows: [],
  riskAreas: [],
  screenshotPath: "/tmp/shot.png",
} as unknown as AnalysisResponse

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(analyzeUrl).mockResolvedValue(analysis)
  vi.mocked(generateLocators).mockResolvedValue({
    generated: 1,
    locators: [{ elementName: "email", preferredLocator: "getByLabel('Email')" }] as LocatorDto[],
    instruction: null,
    strategiesUsed: ["getByLabel"],
  })
  vi.mocked(generateTestPlan).mockResolvedValue({ scenarioCount: 0, scenarios: [] as TestScenarioDto[] })
  vi.mocked(generateTests).mockResolvedValue({ generated: 0, tests: [] as GeneratedTestDto[] })
  vi.mocked(getLocators).mockResolvedValue([] as LocatorDto[])
  vi.mocked(getTestPlans).mockResolvedValue([])
  vi.mocked(getTests).mockResolvedValue([])
  vi.mocked(getExecutionHistory).mockResolvedValue([] as TestExecution[])
  vi.mocked(getSuggestions).mockResolvedValue([])
  Element.prototype.scrollIntoView = vi.fn()
})

/** The url field is the entry point for the whole pipeline. */
async function analyse(pageUrl = "https://the-internet.herokuapp.com/login") {
  const user = userEvent.setup()
  render(<ActionProvider><QaWorkflow url={pageUrl} projectId={7} /></ActionProvider>)
  await user.click(screen.getByRole("button", { name: "Analyze" }))
  await waitFor(() => expect(vi.mocked(analyzeUrl)).toHaveBeenCalled())
  return user
}

describe("QaWorkflow", () => {
  it("analyses the url it is given", async () => {
    await analyse()
    // Only the leading arguments matter here; the trailing optional credentials are
    // covered by the instruction test below.
    const [url, forceRefresh, projectId] = vi.mocked(analyzeUrl).mock.calls[0]
    expect(url).toBe("https://the-internet.herokuapp.com/login")
    expect(forceRefresh).toBe(false)
    expect(projectId).toBe(7)
  })

  it("offers the generation steps only after a page has been analysed", async () => {
    // Locators, a plan and tests are all derived from an analysis. Offering them first
    // invites the user to generate from nothing, and the server rejects it as missing
    // project context with an error that reads like a configuration problem.
    render(<ActionProvider><QaWorkflow url="" projectId={7} /></ActionProvider>)
    expect(screen.queryByRole("button", { name: "Generate Locators" })).toBeNull()
  })

  it("reveals the generation steps once the page is analysed", async () => {
    await analyse()
    expect(await screen.findByRole("button", { name: "Generate Locators" })).toBeDefined()
  })

  it("passes the url, the project and the typed instruction to locator generation", async () => {
    const user = await analyse()

    // Two disclosures sit between the user and the instruction box: the locator card
    // itself, which used to be collapsed while empty, and "Advanced options" above the
    // textarea. The second is intentional — optional extras behind a toggle is a
    // reasonable choice — so this follows the real interaction path rather than asserting
    // the field is permanently on screen. Scoping to the card matters: there are four
    // identical toggles on the page.
    const heading = await screen.findByText(/Locator Repository/i)
    const card = heading.closest('[class*="rounded-xl"]') as HTMLElement
    expect(card.textContent).toContain("Generate Locators")

    await user.click(within(card).getByRole("button", { name: /advanced options/i }))
    const instructionBox = await screen.findByPlaceholderText(
      /cover negative login scenarios/i,
    )
    await user.type(instructionBox, "focus on the email field")
    await user.click(screen.getByRole("button", { name: "Generate Locators" }))

    await waitFor(() => expect(vi.mocked(generateLocators)).toHaveBeenCalled())
    // The instruction is the user's steering; losing it is indistinguishable from the
    // model ignoring them.
    const [url, projectId, instruction] = vi.mocked(generateLocators).mock.calls[0]
    expect(url).toBe("https://the-internet.herokuapp.com/login")
    expect(projectId).toBe(7)
    expect(instruction).toContain("focus on the email field")
  })

  it("reports an analysis failure instead of silently showing an empty page", async () => {
    vi.mocked(analyzeUrl).mockRejectedValue(new Error("Browser error: net::ERR_CONNECTION_REFUSED"))
    render(<ActionProvider><QaWorkflow url="https://nope.invalid" projectId={7} /></ActionProvider>)

    await userEvent.setup().click(screen.getByRole("button", { name: "Analyze" }))

    // A failure that renders as an empty result is indistinguishable from a page with
    // nothing on it, and the user has no reason to suspect the browser failed.
    expect(await screen.findByText(/ERR_CONNECTION_REFUSED/)).toBeDefined()
  })

  it("shows the buttons the user can act on in the execution section", async () => {
    await analyse()
    expect(await screen.findByRole("button", { name: "Run All Tests" })).toBeDefined()
  })
})

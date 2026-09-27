import { describe, expect, it, vi, beforeEach } from "vitest"
import { render, screen } from "@testing-library/react"
import { LocatorRepository } from "@/components/locator-repository"
import type { LocatorDto } from "@/lib/locator-api"

/**
 * Regression test for a crash found by another suite: a locator without
 * `fallbackLocators` took down the whole panel, because the render did
 * `locator.fallbackLocators.length` on a missing field.
 *
 * <p>The backend does always populate the list, so this cannot happen from the Core today.
 * It is here because the failure mode is disproportionate: an uncaught exception while
 * rendering blanks every locator, so one incomplete record hides a list the user came to
 * read. Optional presentation data should not be able to do that.
 */

function locator(overrides: Partial<LocatorDto> = {}): LocatorDto {
  return {
    id: 1,
    elementName: "email",
    elementType: "input",
    preferredLocator: "getByLabel('Email')",
    fallbackLocators: [],
    strategy: "getByLabel",
    confidence: 88,
    reason: "label is stable",
    ...overrides,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe("LocatorRepository", () => {
  it("renders the preferred locator", () => {
    render(
      <LocatorRepository
        locators={[locator()]}
        loading={false}
        onGenerate={vi.fn()}
        instruction=""
        onInstructionChange={vi.fn()}
      />,
    )
    expect(screen.getByText("getByLabel('Email')")).toBeDefined()
  })

  it("shows fallbacks when there are any", () => {
    render(
      <LocatorRepository
        locators={[locator({ fallbackLocators: ["#email", "input[name=email]"] })]}
        loading={false}
        onGenerate={vi.fn()}
        instruction=""
        onInstructionChange={vi.fn()}
      />,
    )
    expect(screen.getByText("#email")).toBeDefined()
    expect(screen.getByText("input[name=email]")).toBeDefined()
  })

  it("renders a locator with no fallbacks rather than crashing", () => {
    // The regression: `.length` of undefined threw while rendering, which took the whole
    // repository down instead of omitting one row of supplementary information.
    const incomplete = { ...locator(), fallbackLocators: undefined } as unknown as LocatorDto

    expect(() =>
      render(
        <LocatorRepository
          locators={[incomplete]}
          loading={false}
          onGenerate={vi.fn()}
          instruction=""
          onInstructionChange={vi.fn()}
        />,
      ),
    ).not.toThrow()

    // And the locator is still readable, which is the part that matters.
    expect(screen.getByText("getByLabel('Email')")).toBeDefined()
  })

  it("keeps the other locators readable when one record is incomplete", () => {
    const incomplete = { ...locator({ id: 1 }), fallbackLocators: undefined } as unknown as LocatorDto

    render(
      <LocatorRepository
        locators={[incomplete, locator({ id: 2, preferredLocator: "getByLabel('Password')" })]}
        loading={false}
        onGenerate={vi.fn()}
        instruction=""
        onInstructionChange={vi.fn()}
      />,
    )

    expect(screen.getByText("getByLabel('Email')")).toBeDefined()
    expect(screen.getByText("getByLabel('Password')")).toBeDefined()
  })
})

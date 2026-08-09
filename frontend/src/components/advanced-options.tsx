"use client";

import { useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";

export type TestTypeValue = "ui" | "e2e" | "api" | "";

interface AdvancedOptionsProps {
  /** Show the structured test type selector (Generate Tests / Run only). */
  showTestType?: boolean;
  instruction: string;
  testType: TestTypeValue;
  onInstructionChange: (value: string) => void;
  onTestTypeChange: (value: TestTypeValue) => void;
}

/**
 * User control options for AI operations (Sprint 14.6): a natural-language
 * instruction plus an optional structured test type. Both are optional; when
 * left empty the backend behaves exactly as before.
 */
export function AdvancedOptions({
  showTestType = false,
  instruction,
  testType,
  onInstructionChange,
  onTestTypeChange,
}: AdvancedOptionsProps) {
  const [open, setOpen] = useState(false);

  return (
    <div className="rounded-lg border border-border bg-muted/20">
      <button
        type="button"
        className="flex w-full items-center justify-between px-3 py-2 text-xs font-medium uppercase tracking-widest text-muted-foreground hover:text-foreground"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
      >
        <span>Advanced options</span>
        {open ? <ChevronDown className="size-3.5" aria-hidden /> : <ChevronRight className="size-3.5" aria-hidden />}
      </button>
      {open && (
        <div className="space-y-3 px-3 pb-3">
          <div className="space-y-1.5">
            <label htmlFor="ai-instruction" className="text-xs text-muted-foreground">
              Instruction (optional) — tells the AI what to focus on
            </label>
            <textarea
              id="ai-instruction"
              rows={2}
              value={instruction}
              onChange={(e) => onInstructionChange(e.target.value)}
              placeholder="e.g. cover negative login scenarios and XSS inputs"
              className="w-full resize-y rounded-lg border border-border bg-background/60 px-3 py-2 text-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            />
          </div>
          {showTestType && (
            <div className="space-y-1.5">
              <label htmlFor="ai-test-type" className="text-xs text-muted-foreground">
                Test type (optional)
              </label>
              <select
                id="ai-test-type"
                value={testType}
                onChange={(e) => onTestTypeChange(e.target.value as TestTypeValue)}
                className="h-9 w-full rounded-lg border border-border bg-background/60 px-3 py-1.5 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <option value="">All</option>
                <option value="ui">UI</option>
                <option value="e2e">E2E</option>
                <option value="api">API</option>
              </select>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

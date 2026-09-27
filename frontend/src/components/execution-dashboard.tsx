"use client";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { CollapsibleCard } from "@/components/ui/collapsible-card";
import { AdvancedOptions, type TestTypeValue } from "@/components/advanced-options";
import { getExecutionResults, type ExecutionResults, type TestExecution } from "@/lib/execution-api";
import { useEffect, useState } from "react";

interface ExecutionDashboardProps {
  executions: TestExecution[];
  loading: boolean;
  onRunAll: () => void;
  highlightExecutionId?: number | null;
  instruction?: string;
  testType?: TestTypeValue;
  onInstructionChange?: (value: string) => void;
  onTestTypeChange?: (value: TestTypeValue) => void;
}

function getStatusColor(status: string) {
  switch (status) {
    case "PASSED":
      return "bg-green-500";
    case "FAILED":
      return "bg-red-500";
    case "RUNNING":
      return "bg-blue-500";
    case "TIMEOUT":
      return "bg-yellow-500";
    default:
      return "bg-gray-500";
  }
}

/** Per-test statuses are lower case from Playwright; execution statuses are upper case. */
function testStatusVariant(status: string) {
  switch (status?.toLowerCase()) {
    case "passed":
      return "success" as const;
    case "failed":
      return "failed" as const;
    case "skipped":
      return "secondary" as const;
    default:
      return "outline" as const;
  }
}

function formatTestDuration(ms: number | null): string {
  if (ms === null || ms === undefined) return "—";
  if (ms < 1000) return `${ms} ms`;
  const seconds = ms / 1000;
  if (seconds < 60) return `${seconds.toFixed(seconds >= 10 ? 0 : 1)} s`;
  return `${Math.floor(seconds / 60)}m ${Math.round(seconds % 60)}s`;
}

/** Only the first line: the rest is behind a disclosure. */
function firstLine(text: string | null): string | null {
  if (!text) return null;
  const line = text.split("\n")[0];
  return line.length > 200 ? `${line.slice(0, 200)}…` : line;
}

export function ExecutionDashboard({ executions, loading, onRunAll, highlightExecutionId, instruction = "", testType = "", onInstructionChange, onTestTypeChange }: ExecutionDashboardProps) {
  const [expandedExec, setExpandedExec] = useState<number | null>(null);
  const [results, setResults] = useState<Record<number, ExecutionResults>>({});
  const [resultsError, setResultsError] = useState<Record<number, string>>({});
  const [loadingResults, setLoadingResults] = useState<number | null>(null);

  // Fetched on expand, not with the history list: inlining every test of every
  // execution would make this page unloadable on a real project.
  useEffect(() => {
    if (expandedExec === null || results[expandedExec]) return;
    let cancelled = false;
    setLoadingResults(expandedExec);
    setResultsError((prev) => ({ ...prev, [expandedExec]: "" }));
    getExecutionResults(expandedExec)
      .then((data) => {
        if (!cancelled) setResults((prev) => ({ ...prev, [expandedExec]: data }));
      })
      .catch((e: unknown) => {
        if (!cancelled) {
          setResultsError((prev) => ({
            ...prev,
            [expandedExec]: e instanceof Error ? e.message : "Could not load results",
          }));
        }
      })
      .finally(() => {
        if (!cancelled) setLoadingResults(null);
      });
    return () => {
      cancelled = true;
    };
  }, [expandedExec, results]);

  return (
    <CollapsibleCard
      title={`Execution History (${executions.length})`}
      // Open by default, for the same reason as LocatorRepository: a card collapsed while
      // empty hides the controls that fill it. The first-run guidance additionally lives in
      // the header so it is visible even if a user collapses the card.
      defaultOpen
      subtitle={
        executions.length === 0
          ? 'No executions yet. Click "Run All Tests" to execute generated tests.'
          : undefined
      }
      action={
        <Button onClick={onRunAll} disabled={loading} size="sm">
          {loading ? "Running..." : "Run All Tests"}
        </Button>
      }
    >
      {onInstructionChange && (
        <div className="mb-3">
          <AdvancedOptions
            showTestType
            instruction={instruction}
            testType={testType}
            onInstructionChange={onInstructionChange}
            onTestTypeChange={onTestTypeChange ?? (() => {})}
          />
        </div>
      )}
      {executions.length > 0 && (
          <div className="space-y-3">
            {executions.map((exec) => {
              const isHighlighted = exec.id === highlightExecutionId;
              const expanded = expandedExec === exec.id;
              const result = results[exec.id];
              const error = resultsError[exec.id];
              return (
                <div
                  key={exec.id}
                  className={`border rounded-lg p-4 space-y-2 transition-all duration-700 ${
                    isHighlighted
                      ? "border-primary bg-primary/5 ring-2 ring-primary/30 shadow-lg"
                      : "border-border hover:border-muted-foreground/40"
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <Badge className={getStatusColor(exec.status)}>{exec.status}</Badge>
                      <span className="text-sm font-medium">{exec.testFile}</span>
                    </div>
                    <div className="flex items-center gap-2">
                      {exec.duration && (
                        <span className="text-sm text-muted-foreground">{exec.duration}ms</span>
                      )}
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => setExpandedExec(expanded ? null : exec.id)}
                      >
                        {expanded ? "Collapse" : "Details"}
                      </Button>
                    </div>
                  </div>
                  <p className="text-xs text-muted-foreground">
                    {new Date(exec.createdAt).toLocaleString()}
                  </p>
                  {expanded && (
                    <div className="space-y-3 mt-3">
                      {loadingResults === exec.id && (
                        <p className="text-sm text-muted-foreground">Loading test results…</p>
                      )}

                      {error && (
                        <p className="text-sm text-red-500">Could not load test results: {error}</p>
                      )}

                      {result && <TestResultsPanel result={result} />}

                      {result?.htmlReport && (
                        <p className="text-xs text-muted-foreground break-all">
                          HTML report: <span className="font-mono">{result.htmlReport}</span>
                        </p>
                      )}

                      {exec.errorMessage && (
                        <div>
                          <p className="text-sm font-medium text-red-500 mb-1">Error:</p>
                          <pre className="bg-red-50 dark:bg-red-950/20 p-3 rounded text-xs overflow-x-auto">
                            <code>{exec.errorMessage}</code>
                          </pre>
                        </div>
                      )}
                      {exec.consoleLogs && (
                        <div>
                          <p className="text-sm font-medium text-muted-foreground mb-1">Console Logs:</p>
                          <pre className="bg-muted p-3 rounded text-xs overflow-x-auto max-h-48 overflow-y-auto">
                            <code>{exec.consoleLogs}</code>
                          </pre>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
    </CollapsibleCard>
  );
}

/**
 * Per-test outcome, grouped by status with failures first.
 *
 * <p>An empty list says so explicitly. Showing a zeroed counter row for a run that
 * recorded no results would read as "everything passed", which is the one conclusion a
 * QA view must never arrive at without evidence.</p>
 */
function TestResultsPanel({ result }: { result: ExecutionResults }) {
  const [showAll, setShowAll] = useState(false);

  if (result.tests.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        No per-test results were recorded for this run. Open the console log below for the
        raw Playwright output.
      </p>
    );
  }

  const ordered = [...result.tests].sort((a, b) => {
    const rank = (s: string) => (s?.toLowerCase() === "failed" ? 0 : s?.toLowerCase() === "skipped" ? 1 : 2);
    return rank(a.status) - rank(b.status) || a.ordinal - b.ordinal;
  });
  const visible = showAll ? ordered : ordered.slice(0, 10);

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2 text-xs">
        <Badge variant="success">{result.passedCount} passed</Badge>
        {result.failedCount > 0 && <Badge variant="failed">{result.failedCount} failed</Badge>}
        {result.skippedCount > 0 && <Badge variant="secondary">{result.skippedCount} skipped</Badge>}
        <span className="text-muted-foreground">{result.totalCount} total</span>
        <span className="text-muted-foreground">· {formatTestDuration(result.durationMs)}</span>
      </div>

      <ul className="space-y-1 border border-border rounded-md divide-y divide-border">
        {visible.map((test) => (
          <li key={test.ordinal} className="p-2 space-y-1">
            <div className="flex items-start justify-between gap-2">
              <div className="min-w-0">
                <p className="text-sm font-medium break-words">{test.title ?? `Test ${test.ordinal + 1}`}</p>
                {test.file && <p className="text-xs text-muted-foreground break-all">{test.file}</p>}
              </div>
              <div className="flex items-center gap-1 shrink-0">
                {test.retries > 0 && (
                  <span className="text-xs text-amber-600" title="Passed after retrying">
                    {test.retries}×
                  </span>
                )}
                <Badge variant={testStatusVariant(test.status)}>{test.status}</Badge>
                <span className="text-xs text-muted-foreground w-16 text-right">
                  {formatTestDuration(test.durationMs)}
                </span>
              </div>
            </div>
            {test.error && (
              <details className="text-xs">
                <summary className="cursor-pointer text-red-500 break-words">
                  {firstLine(test.error)}
                </summary>
                <pre className="bg-muted p-2 rounded mt-1 overflow-x-auto whitespace-pre-wrap break-words">
                  <code>{test.error}</code>
                </pre>
              </details>
            )}
            {test.hasEvidence && (
              <p className="text-xs text-muted-foreground">
                Evidence:{" "}
                {[
                  test.screenshots.length ? `${test.screenshots.length} screenshot(s)` : null,
                  test.videos.length ? `${test.videos.length} video(s)` : null,
                  test.traces.length ? `${test.traces.length} trace(s)` : null,
                ]
                  .filter(Boolean)
                  .join(", ")}
              </p>
            )}
          </li>
        ))}
      </ul>

      {ordered.length > 10 && (
        <Button variant="ghost" size="sm" onClick={() => setShowAll(!showAll)}>
          {showAll ? "Show fewer" : `Show all ${ordered.length} tests`}
        </Button>
      )}
    </div>
  );
}

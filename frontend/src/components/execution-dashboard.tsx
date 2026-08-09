"use client";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { CollapsibleCard } from "@/components/ui/collapsible-card";
import { AdvancedOptions, type TestTypeValue } from "@/components/advanced-options";
import type { TestExecution } from "@/lib/execution-api";
import { useState } from "react";

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

export function ExecutionDashboard({ executions, loading, onRunAll, highlightExecutionId, instruction = "", testType = "", onInstructionChange, onTestTypeChange }: ExecutionDashboardProps) {
  const [expandedExec, setExpandedExec] = useState<number | null>(null);

  return (
    <CollapsibleCard
      title={`Execution History (${executions.length})`}
      defaultOpen={executions.length > 0}
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
      {executions.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          No executions yet. Click &quot;Run All Tests&quot; to execute generated tests.
        </p>
      ) : (
          <div className="space-y-3">
            {executions.map((exec) => {
              const isHighlighted = exec.id === highlightExecutionId;
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
                        onClick={() => setExpandedExec(expandedExec === exec.id ? null : exec.id)}
                      >
                        {expandedExec === exec.id ? "Collapse" : "Details"}
                      </Button>
                    </div>
                  </div>
                  <p className="text-xs text-muted-foreground">
                    {new Date(exec.createdAt).toLocaleString()}
                  </p>
                  {expandedExec === exec.id && (
                    <div className="space-y-3 mt-3">
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

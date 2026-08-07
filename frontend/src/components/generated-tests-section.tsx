"use client";

import { Button } from "@/components/ui/button";
import { CollapsibleCard } from "@/components/ui/collapsible-card";
import type { GeneratedTestDto } from "@/lib/testgen-api";
import { useState } from "react";

interface GeneratedTestsSectionProps {
  tests: GeneratedTestDto[];
  loading: boolean;
  onGenerate: () => void;
  onRunTest?: (testId: number) => void;
}

export function GeneratedTestsSection({ tests, loading, onGenerate, onRunTest }: GeneratedTestsSectionProps) {
  const [expandedTest, setExpandedTest] = useState<number | null>(null);
  const [runningTest, setRunningTest] = useState<number | null>(null);

  return (
    <CollapsibleCard
      title={`Generated Tests (${tests.length})`}
      defaultOpen={tests.length > 0}
      action={
        <Button onClick={onGenerate} disabled={loading} size="sm">
          {loading ? "Generating..." : "Generate Tests"}
        </Button>
      }
    >
      {tests.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          No tests generated yet. Click &quot;Generate Tests&quot; to create Playwright tests.
        </p>
      ) : (
          <div className="space-y-3">
            {tests.map((test) => (
              <div key={test.id} className="border rounded-lg p-4 space-y-2">
                <div className="flex items-center justify-between">
                  <span className="font-medium">{test.scenarioName}</span>
                  <div className="flex items-center gap-2">
                    {onRunTest && (
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={runningTest === test.id}
                        onClick={async () => {
                          setRunningTest(test.id);
                          try {
                            await onRunTest(test.id);
                          } finally {
                            setRunningTest(null);
                          }
                        }}
                      >
                        {runningTest === test.id ? "Running..." : "Run"}
                      </Button>
                    )}
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => setExpandedTest(expandedTest === test.id ? null : test.id)}
                    >
                      {expandedTest === test.id ? "Collapse" : "Expand"}
                    </Button>
                  </div>
                </div>
                {expandedTest === test.id && (
                  <div className="space-y-3 mt-3">
                    {test.pageObjectCode && (
                      <div>
                        <p className="text-sm font-medium text-muted-foreground mb-1">Page Object:</p>
                        <pre className="bg-muted p-3 rounded text-xs overflow-x-auto">
                          <code>{test.pageObjectCode}</code>
                        </pre>
                      </div>
                    )}
                    <div>
                      <p className="text-sm font-medium text-muted-foreground mb-1">Test Code:</p>
                      <pre className="bg-muted p-3 rounded text-xs overflow-x-auto">
                        <code>{test.testCode}</code>
                      </pre>
                    </div>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </CollapsibleCard>
  );
}

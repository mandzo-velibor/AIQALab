"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
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
    <Card className="md:col-span-2 lg:col-span-3">
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle>Generated Tests ({tests.length})</CardTitle>
        <Button onClick={onGenerate} disabled={loading} size="sm">
          {loading ? "Generating..." : "Generate Tests"}
        </Button>
      </CardHeader>
      <CardContent>
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
      </CardContent>
    </Card>
  );
}

"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import type { ProjectHistoryResponse } from "@/lib/project-api";
import { useState } from "react";

interface ProjectHistoryProps {
  history: ProjectHistoryResponse | null;
  onGenerateHealing: (executionId: number) => void;
  onRefresh: () => void;
  healing: boolean;
}

function failureTypeColor(type: string) {
  switch (type) {
    case "TIMEOUT":
      return "bg-yellow-500";
    case "ELEMENT_NOT_FOUND":
      return "bg-orange-500";
    case "HTTP_ERROR":
      return "bg-red-500";
    case "LOCATOR_INVALID":
      return "bg-purple-500";
    default:
      return "bg-gray-500";
  }
}

export function ProjectHistory({
  history,
  onGenerateHealing,
  onRefresh,
  healing,
}: ProjectHistoryProps) {
  const [expandedAnalysis, setExpandedAnalysis] = useState<number | null>(null);

  return (
    <Card className="md:col-span-2 lg:col-span-3">
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle>Project History</CardTitle>
        <Button onClick={onRefresh} variant="outline" size="sm">
          Refresh
        </Button>
      </CardHeader>
      <CardContent className="space-y-6">
        {!history ? (
          <p className="text-sm text-muted-foreground">Loading project history...</p>
        ) : (
          <>
            <div>
              <h3 className="text-sm font-medium mb-2">Page Analyses ({history.pageAnalyses.length})</h3>
              {history.pageAnalyses.length === 0 ? (
                <p className="text-sm text-muted-foreground">No page analyses yet.</p>
              ) : (
                <div className="space-y-2">
                  {history.pageAnalyses.map((page) => (
                    <div key={page.id} className="flex items-center justify-between border rounded-lg p-3">
                      <div>
                        <p className="text-sm font-medium truncate max-w-[400px]">{page.url}</p>
                        <p className="text-xs text-muted-foreground">
                          {new Date(page.createdAt).toLocaleString()} · version {page.version}
                        </p>
                      </div>
                      {page.pageType && <Badge variant="secondary">{page.pageType}</Badge>}
                    </div>
                  ))}
                </div>
              )}
            </div>

            <div>
              <h3 className="text-sm font-medium mb-2">Locator History ({history.locatorHistory.length})</h3>
              {history.locatorHistory.length === 0 ? (
                <p className="text-sm text-muted-foreground">No locators generated yet.</p>
              ) : (
                <div className="space-y-2">
                  {history.locatorHistory.map((loc) => (
                    <div key={loc.id} className="border rounded-lg p-3 space-y-1">
                      <div className="flex items-center justify-between">
                        <p className="text-sm font-medium">{loc.elementName}</p>
                        <Badge
                          variant={loc.status === "ACTIVE" ? "success" : loc.status === "FAILED" ? "failed" : "secondary"}
                        >
                          {loc.status}
                        </Badge>
                      </div>
                      <p className="text-xs text-muted-foreground">
                        {loc.strategy}: {loc.locator}
                      </p>
                    </div>
                  ))}
                </div>
              )}
            </div>

            <div>
              <h3 className="text-sm font-medium mb-2">Failure Analyses ({history.failureAnalyses.length})</h3>
              {history.failureAnalyses.length === 0 ? (
                <p className="text-sm text-muted-foreground">
                  No failure analyses yet. Run a test that fails, then click &quot;Analyze&quot;.
                </p>
              ) : (
                <div className="space-y-2">
                  {history.failureAnalyses.map((f) => (
                    <div key={f.id} className="border rounded-lg p-3 space-y-2">
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-2">
                          <Badge className={failureTypeColor(f.failureType)}>{f.failureType}</Badge>
                          <span className="text-sm font-medium">Execution #{f.executionId}</span>
                        </div>
                        <div className="flex items-center gap-2">
                          {f.healingCandidate && (
                            <Button
                              variant="outline"
                              size="sm"
                              onClick={() => onGenerateHealing(f.executionId)}
                              disabled={healing}
                            >
                              {healing ? "Generating..." : "Generate Healing"}
                            </Button>
                          )}
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => setExpandedAnalysis(expandedAnalysis === f.id ? null : f.id)}
                          >
                            {expandedAnalysis === f.id ? "Collapse" : "Details"}
                          </Button>
                        </div>
                      </div>
                      {expandedAnalysis === f.id && (
                        <div className="space-y-1">
                          {f.summary && <p className="text-sm">{f.summary}</p>}
                          {f.affectedElement && (
                            <p className="text-xs text-muted-foreground">Element: {f.affectedElement}</p>
                          )}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>

            <div>
              <h3 className="text-sm font-medium mb-2">
                Healing Suggestions ({history.healingSuggestions.length})
              </h3>
              {history.healingSuggestions.length === 0 ? (
                <p className="text-sm text-muted-foreground">No healing suggestions yet.</p>
              ) : (
                <div className="space-y-2">
                  {history.healingSuggestions.map((h) => (
                    <div key={h.id} className="border rounded-lg p-3 space-y-1">
                      <div className="flex items-center justify-between">
                        <p className="text-sm font-medium">{h.elementName}</p>
                        <Badge
                          variant={h.status === "APPROVED" ? "success" : h.status === "REJECTED" ? "failed" : "running"}
                        >
                          {h.status}
                        </Badge>
                      </div>
                      <div className="text-xs space-y-0.5">
                        <p className="text-muted-foreground">Old: <code>{h.oldLocator}</code></p>
                        <p>New: <code>{h.newLocator}</code></p>
                        {h.confidence !== null && (
                          <p className="text-muted-foreground">Confidence: {h.confidence}%</p>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}

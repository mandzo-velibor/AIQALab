"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import type { HealingSuggestion } from "@/lib/healing-api";
import { approveSuggestion, rejectSuggestion, applySuggestion } from "@/lib/healing-api";
import { useEffect, useRef, useState } from "react";

interface HealingDashboardProps {
  suggestions: HealingSuggestion[];
  onSuggestionChanged: () => void;
}

function statusVariant(status: string): "success" | "failed" | "running" | "secondary" {
  switch (status) {
    case "APPROVED":
      return "success";
    case "REJECTED":
      return "failed";
    case "APPLIED":
      return "running";
    default:
      return "secondary";
  }
}

export function HealingDashboard({ suggestions, onSuggestionChanged }: HealingDashboardProps) {
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const errorRef = useRef<HTMLParagraphElement>(null);

  useEffect(() => {
    if (error) {
      errorRef.current?.scrollIntoView({ behavior: "smooth", block: "nearest" });
    }
  }, [error]);

  const handleApprove = async (id: number) => {
    setBusyId(id);
    setError(null);
    try {
      await approveSuggestion(id);
      onSuggestionChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Approval failed");
    } finally {
      setBusyId(null);
    }
  };

  const handleReject = async (id: number) => {
    setBusyId(id);
    setError(null);
    try {
      await rejectSuggestion(id);
      onSuggestionChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Rejection failed");
    } finally {
      setBusyId(null);
    }
  };

  const handleApply = async (id: number) => {
    setBusyId(id);
    setError(null);
    try {
      await applySuggestion(id);
      onSuggestionChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Apply failed");
    } finally {
      setBusyId(null);
    }
  };

  return (
    <Card className="md:col-span-2 lg:col-span-3">
      <CardHeader>
        <CardTitle>Healing Dashboard ({suggestions.length})</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {error && (
          <p ref={errorRef} role="alert" className="text-sm font-medium text-red-600 dark:text-red-400">
            {error}
          </p>
        )}
        {suggestions.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No healing suggestions yet. Analyze a failed execution to generate locator suggestions.
          </p>
        ) : (
          suggestions.map((s) => (
            <div key={s.id} className="border rounded-lg p-4 space-y-2">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Badge variant={statusVariant(s.status)}>{s.status}</Badge>
                  <span className="text-sm font-medium">{s.elementName}</span>
                </div>
                <div className="flex items-center gap-2">
                  {s.confidence !== null && (
                    <span className="text-sm text-muted-foreground">Confidence: {s.confidence}%</span>
                  )}
                  {s.status === "PENDING" && (
                    <>
                      <Button
                        variant="default"
                        size="sm"
                        disabled={busyId === s.id}
                        onClick={() => handleApprove(s.id)}
                      >
                        {busyId === s.id ? "Saving..." : "Approve"}
                      </Button>
                      <Button
                        variant="destructive"
                        size="sm"
                        disabled={busyId === s.id}
                        onClick={() => handleReject(s.id)}
                      >
                        Reject
                      </Button>
                    </>
                  )}
                  {s.status === "APPROVED" && (
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={busyId === s.id}
                      onClick={() => handleApply(s.id)}
                    >
                      {busyId === s.id ? "Applying..." : "Apply"}
                    </Button>
                  )}
                </div>
              </div>
              <div className="text-xs space-y-0.5">
                <p className="text-muted-foreground">
                  Old: <code className="bg-muted px-1 rounded">{s.oldLocator}</code>
                </p>
                <p>
                  New: <code className="bg-muted px-1 rounded">{s.newLocator}</code>
                </p>
                {s.reason && <p className="text-muted-foreground">Reason: {s.reason}</p>}
                {s.approvedBy && (
                  <p className="text-muted-foreground">
                    {s.status === "APPROVED" ? "Approved" : "Rejected"} by {s.approvedBy} at{" "}
                    {s.approvedAt ? new Date(s.approvedAt).toLocaleString() : "N/A"}
                  </p>
                )}
              </div>
            </div>
          ))
        )}
      </CardContent>
    </Card>
  );
}

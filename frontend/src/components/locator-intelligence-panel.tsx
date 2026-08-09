"use client";

import { useState } from "react";
import { CollapsibleCard } from "@/components/ui/collapsible-card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  analyzeLocator,
  type LocatorIntelligence,
} from "@/lib/locator-api";

interface LocatorIntelligencePanelProps {
  defaultUrl?: string;
  projectId?: number;
}

function healthColor(health: string) {
  switch (health) {
    case "HEALTHY":
      return "bg-green-500";
    case "WARNING":
      return "bg-yellow-500";
    case "FRAGILE":
      return "bg-orange-500";
    case "BROKEN":
      return "bg-red-500";
    default:
      return "bg-gray-500";
  }
}

function scoreColor(score: number) {
  if (score >= 80) return "bg-green-500";
  if (score >= 60) return "bg-yellow-500";
  if (score >= 40) return "bg-orange-500";
  return "bg-red-500";
}

function ScoreBar({ label, value, max }: { label: string; value: number; max: number }) {
  const pct = Math.min(100, Math.round((value / max) * 100));
  return (
    <div>
      <div className="flex items-center justify-between text-sm">
        <span className="text-muted-foreground">{label}</span>
        <span className="font-medium">
          {value}/{max}
        </span>
      </div>
      <div className="h-1.5 bg-muted rounded-full overflow-hidden">
        <div className={`h-full ${scoreColor(pct)}`} style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}

export function LocatorIntelligencePanel({ defaultUrl, projectId }: LocatorIntelligencePanelProps) {
  const [url, setUrl] = useState(defaultUrl ?? "");
  const [locator, setLocator] = useState("");
  const [result, setResult] = useState<LocatorIntelligence | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const analyze = async () => {
    if (!url.trim() || !locator.trim()) {
      setError("Both URL and locator are required.");
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const res = await analyzeLocator(url.trim(), locator.trim(), projectId);
      setResult(res.intelligence);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Locator analysis failed");
    } finally {
      setLoading(false);
    }
  };

  return (
    <CollapsibleCard title="Locator Intelligence" defaultOpen={false}>
      <div className="space-y-4">
        <div className="flex flex-wrap items-end gap-2">
          <div className="flex-1 min-w-[240px] space-y-1">
            <label className="text-xs text-muted-foreground">Page URL</label>
            <Input
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              placeholder="https://example.com"
            />
          </div>
          <div className="flex-1 min-w-[240px] space-y-1">
            <label className="text-xs text-muted-foreground">Locator</label>
            <Input
              value={locator}
              onChange={(e) => setLocator(e.target.value)}
              placeholder="getByRole('button', { name: 'Log in' })"
              className="font-mono text-xs"
            />
          </div>
          <Button onClick={analyze} disabled={loading}>
            {loading ? "Analyzing..." : "Analyze"}
          </Button>
        </div>

        {error && <p className="text-sm text-red-500">{error}</p>}

        {result && (
          <div className="border rounded-lg p-4 space-y-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2 flex-wrap">
                <Badge className={healthColor(result.health)}>{result.health}</Badge>
                <Badge variant="outline">{result.strategy}</Badge>
                {result.visible && <Badge variant="outline">visible</Badge>}
                {result.enabled && <Badge variant="outline">enabled</Badge>}
              </div>
              <div className="text-right">
                <span className="text-2xl font-bold">{result.overallScore}</span>
                <span className="text-muted-foreground">/100</span>
              </div>
            </div>

            <code className="block bg-muted px-2 py-1 rounded text-xs break-all">{result.locator}</code>

            <div className="grid grid-cols-2 gap-3">
              <ScoreBar label="Uniqueness" value={result.uniqueness} max={25} />
              <ScoreBar label="Semantic" value={result.semanticScore} max={25} />
              <ScoreBar label="Stability" value={result.stabilityScore} max={25} />
              <ScoreBar label="Maintainability" value={result.maintainability} max={15} />
              <ScoreBar label="Resilience" value={result.resilience} max={10} />
            </div>

            <div className="text-sm space-y-1">
              <p className="text-muted-foreground">Uniqueness: {result.uniquenessDetail}</p>
              <p className="text-muted-foreground">Semantic: {result.semanticReason}</p>
              <p className="text-muted-foreground">
                Health: {result.healthReason} (survival {Math.round(result.survivalRate * 100)}% over{" "}
                {result.observedCount} observations)
              </p>
            </div>

            {result.stabilityReasons.length > 0 && (
              <div>
                <span className="text-sm text-muted-foreground">Stability findings:</span>
                <ul className="mt-1 text-sm list-disc pl-5 space-y-1">
                  {result.stabilityReasons.map((r, i) => (
                    <li key={i}>{r}</li>
                  ))}
                </ul>
              </div>
            )}

            {result.comparison && (
              <div className="border-t pt-3 space-y-1 text-sm">
                <p className="font-medium">Comparison with historical locator</p>
                <p className="text-muted-foreground">
                  Strategy: {result.comparison.currentStrategy} → {result.comparison.previousStrategy}{" "}
                  {result.comparison.strategyChanged ? "(changed)" : "(same)"}
                </p>
                <p className="text-muted-foreground">
                  Semantic similarity: {Math.round(result.comparison.semanticSimilarity * 100)}%, target likely
                  same: {result.comparison.targetLikelySame ? "yes" : "no"}
                </p>
                <p>{result.comparison.recommendation}</p>
              </div>
            )}

            {result.history.length > 0 && (
              <div className="border-t pt-3">
                <p className="text-sm font-medium mb-2">Observation history</p>
                <div className="text-xs space-y-1 max-h-48 overflow-y-auto">
                  {result.history.map((h) => (
                    <div key={h.id} className="flex items-center justify-between border rounded px-2 py-1">
                      <code className="break-all">{h.locator}</code>
                      <div className="flex items-center gap-2 shrink-0 ml-2">
                        <Badge className={healthColor(h.health)}>{h.health}</Badge>
                        <span className="text-muted-foreground">{h.observedAt.slice(0, 16)}</span>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </CollapsibleCard>
  );
}

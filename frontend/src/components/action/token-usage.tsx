"use client";

import { useActions } from "@/lib/action-context";
import type { ActionState, TokenUsage } from "@/lib/action-types";
import { formatNumber } from "@/lib/utils";
import { cn } from "@/lib/utils";
import { AlertTriangle, RefreshCw, Sparkles } from "lucide-react";
import { Button } from "@/components/ui/button";

interface TokenUsagePanelProps {
  action?: ActionState | null;
  className?: string;
}

/**
 * AI token usage for an action. Uses the account budget + per-operation
 * breakdown from the backend when available; never shows fabricated numbers.
 */
export function TokenUsagePanel({ action, className }: TokenUsagePanelProps) {
  const { accountUsage, usageError, refreshUsage } = useActions();

  if (!action?.aiUsed && !accountUsage) {
    return null;
  }

  const budget = action?.tokenUsage ? toBudget(action.tokenUsage) : accountUsage?.budget;
  if (!budget) return null;

  const exhausted = budget.used >= budget.limit && budget.limit > 0;

  return (
    <div className={cn("rounded-lg border p-3 text-sm", exhausted ? "border-amber-500/60 bg-amber-500/10" : "border-border bg-muted/40", className)}>
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2 font-medium">
          {exhausted ? (
            <AlertTriangle className="size-4 text-amber-500" aria-hidden />
          ) : (
            <Sparkles className="size-4 text-violet-500" aria-hidden />
          )}
          <span>AI usage</span>
        </div>
        {accountUsage && (
          <Button variant="ghost" size="sm" className="size-8 p-0" onClick={() => void refreshUsage()} aria-label="Refresh AI usage">
            <RefreshCw className="size-3.5" aria-hidden />
          </Button>
        )}
      </div>

      {exhausted && (
        <p className="mt-2 font-medium text-amber-600 dark:text-amber-400">
          AI budget exhausted. AI evaluation may be skipped and replaced with deterministic logic.
        </p>
      )}

      <div className="mt-2 grid grid-cols-3 gap-3">
        <div>
          <p className="text-xs text-muted-foreground">Used</p>
          <p className="font-medium">{formatNumber(budget.used)}</p>
        </div>
        <div>
          <p className="text-xs text-muted-foreground">Budget</p>
          <p className="font-medium">{formatNumber(budget.limit)}</p>
        </div>
        <div>
          <p className="text-xs text-muted-foreground">Remaining</p>
          <p className="font-medium">{formatNumber(budget.remaining)}</p>
        </div>
      </div>

      {budget.limit > 0 && (
        <div className="mt-2 h-1.5 rounded-full bg-muted overflow-hidden" aria-hidden>
          <div
            className={cn("h-full transition-all", exhausted ? "bg-amber-500" : "bg-violet-500")}
            style={{ width: `${Math.min(100, (budget.used / budget.limit) * 100)}%` }}
          />
        </div>
      )}

      {usageError && <p className="mt-2 text-xs text-red-500">{usageError}</p>}

      {action?.aiUnavailable && (
        <p className="mt-2 rounded bg-muted p-2 text-xs">
          AI evaluation unavailable — continuing with deterministic logic.
        </p>
      )}
    </div>
  );
}

function toBudget(usage: TokenUsage) {
  return {
    used: usage.used,
    limit: usage.limit,
    remaining: usage.remaining,
  };
}

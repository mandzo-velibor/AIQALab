"use client";

import { useState } from "react";
import type { ActionState } from "@/lib/action-types";
import { useActions } from "@/lib/action-context";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { formatDuration, formatTime } from "@/lib/utils";
import { ActionProgress } from "@/components/action/action-progress";
import { cn } from "@/lib/utils";
import { Check, X } from "lucide-react";

function isFinished(action: ActionState): action is ActionState & { status: "completed" | "failed" } {
  return action.status === "completed" || action.status === "failed";
}

/**
 * Lightweight UI-level action history. Newest entries first; clicking one
 * reveals its full result/error details. Not the long-term project memory.
 */
export function ActionHistory({ className }: { className?: string }) {
  const { actions, clearAction } = useActions();
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const visible = actions.filter(isFinished);

  if (visible.length === 0) {
    return null;
  }

  const expandedAction = visible.find((a) => a.id === expandedId) ?? null;

  return (
    <Card className={cn(className)}>
      <CardHeader className="pb-2">
        <CardTitle>Recent Activity</CardTitle>
      </CardHeader>
      <CardContent className="space-y-1">
        {visible.map((action) => (
          <button
            key={action.id}
            type="button"
            className="flex w-full items-center gap-3 rounded-md px-2 py-2 text-left hover:bg-accent"
            onClick={() => setExpandedId(expandedId === action.id ? null : action.id)}
            aria-expanded={expandedId === action.id}
          >
            <span className="shrink-0" aria-hidden>
              <HistoryIcon status={action.status} />
            </span>
            <span className="min-w-0 flex-1">
              <span className="block truncate text-sm font-medium">{action.name}</span>
              {action.instruction && (
                <span className="block truncate text-xs italic text-muted-foreground" title={action.instruction}>
                  &quot;{action.instruction}&quot;
                </span>
              )}
              <span className="block truncate text-xs text-muted-foreground">
                {action.status === "completed"
                  ? `Completed ${formatTime(action.completedAt ?? action.startedAt)}`
                  : `Failed ${formatTime(action.completedAt ?? action.startedAt)}`}
              </span>
            </span>
            <span className="shrink-0 text-xs text-muted-foreground">{formatDuration(action.durationMs)}</span>
            {action.status === "failed" && action.error && (
              <Badge variant="failed" className="shrink-0 max-w-[140px]">
                <span className="truncate">{action.error.friendlyLabel}</span>
              </Badge>
            )}
          </button>
        ))}

        {expandedAction && (
          <div className="pt-2">
            <ActionProgress
              action={expandedAction}
              onClose={() => clearAction(expandedAction.id)}
            />
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function HistoryIcon({ status }: { status: "completed" | "failed" }) {
  if (status === "completed") return <Check className="size-4 text-green-500" aria-hidden />;
  return <X className="size-4 text-red-500" aria-hidden />;
}

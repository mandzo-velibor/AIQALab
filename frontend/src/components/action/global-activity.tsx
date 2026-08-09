"use client";

import { useState } from "react";
import { useActions } from "@/lib/action-context";
import { ActionProgress } from "@/components/action/action-progress";
import { Badge } from "@/components/ui/badge";
import { formatDuration, formatTime } from "@/lib/utils";
import { Check, History, Loader2, X } from "lucide-react";
/**
 * Global activity widget (fixed bottom-right). Shows the currently running
 * action live and the recent action history, so an operation never silently
 * vanishes when the user navigates or scrolls away.
 */
export function GlobalActivity() {
  const { actions, activeAction, clearAction } = useActions();
  const [open, setOpen] = useState(false);
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const recent = actions.filter((a) => a.status !== "starting" && a.status !== "running");

  return (
    <>
      <button
        type="button"
        className="fixed bottom-4 right-4 z-50 flex items-center gap-2 rounded-full border border-border bg-card px-3 py-2 text-sm font-medium shadow-lg hover:bg-accent"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        aria-label="Open recent activity"
      >
        <History className="size-4" aria-hidden />
        <span className="hidden sm:inline">Activity</span>
        {activeAction && (
          <Loader2 className="size-4 animate-spin text-primary" aria-hidden />
        )}
        {recent.length > 0 && (
          <Badge variant="secondary" className="tabular-nums">{recent.length}</Badge>
        )}
      </button>

      {open && (
        <div className="fixed bottom-16 right-4 z-50 w-full max-w-sm rounded-xl border border-border bg-card p-3 shadow-2xl">
          <div className="flex items-center justify-between px-1 pb-2">
            <p className="text-sm font-semibold">Recent activity</p>
            <button
              type="button"
              className="rounded p-1 text-muted-foreground hover:bg-accent"
              onClick={() => setOpen(false)}
              aria-label="Close activity panel"
            >
              <X className="size-4" aria-hidden />
            </button>
          </div>

          <div className="max-h-[60vh] space-y-3 overflow-y-auto pr-1">
            {activeAction && (
              <ActionProgress action={activeAction} className="border-primary/50" />
            )}

            {recent.length === 0 && !activeAction && (
              <p className="px-1 py-2 text-sm text-muted-foreground">
                No actions yet. Run a workflow to see its result here.
              </p>
            )}

            {recent.map((action) => (
              <div key={action.id} className="rounded-lg border border-border">
                <button
                  type="button"
                  className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left hover:bg-accent"
                  onClick={() => setExpandedId(expandedId === action.id ? null : action.id)}
                  aria-expanded={expandedId === action.id}
                >
                  {action.status === "completed" ? (
                    <Check className="size-4 shrink-0 text-green-500" aria-hidden />
                  ) : (
                    <X className="size-4 shrink-0 text-red-500" aria-hidden />
                  )}
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium">{action.name}</span>
                    <span className="block truncate text-xs text-muted-foreground">
                      {action.status === "completed" ? "Completed" : "Failed"} ·{" "}
                      {formatTime(action.completedAt ?? action.startedAt)}
                    </span>
                  </span>
                  <span className="shrink-0 text-xs text-muted-foreground">
                    {formatDuration(action.durationMs)}
                  </span>
                </button>
                {expandedId === action.id && (
                  <div className="border-t border-border p-2">
                    <ActionProgress
                      action={action}
                      onClose={() => clearAction(action.id)}
                    />
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}
    </>
  );
}

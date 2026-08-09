"use client";

import { useState } from "react";
import type { ActionState, ActionResultSummary, ActionStep } from "@/lib/action-types";
import { cn, formatDuration, formatTime } from "@/lib/utils";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { TokenUsagePanel } from "@/components/action/token-usage";
import {
  AlertTriangle,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Circle,
  Loader2,
  RefreshCw,
  X,
  XCircle,
} from "lucide-react";

interface ActionProgressProps {
  action: ActionState;
  onRetry?: () => void;
  onClose?: () => void;
  /** Custom result view for the completed state (preferred over summary). */
  renderResult?: (action: ActionState) => React.ReactNode;
  /** Compact summary view used when no custom result renderer is provided. */
  resultSummary?: (action: ActionState) => ActionResultSummary | null;
  className?: string;
}

/**
 * Reusable lifecycle panel for a single user action. Renders STARTING /
 * RUNNING progress (steps + activity), then a persistent COMPLETED result or
 * a first-class FAILED error with expandable technical details. Spinner is
 * never the only signal.
 */
export function ActionProgress({
  action,
  onRetry,
  onClose,
  renderResult,
  resultSummary,
  className,
}: ActionProgressProps) {
  const running = action.status === "starting" || action.status === "running";

  return (
    <Card
      className={cn(
        "overflow-hidden",
        action.status === "failed" && "border-red-500/60",
        action.status === "completed" && action.aiUnavailable && "border-amber-500/50",
        className,
      )}
    >
      <CardHeader className="flex flex-row items-start justify-between gap-2 pb-0">
        <div className="flex items-center gap-2 min-w-0">
          <ActionStatusIcon action={action} />
          <div className="min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <h3 className="text-sm font-bold uppercase tracking-widest text-primary">
                {action.name}
              </h3>
              <Badge variant={badgeVariant(action.status)} className="gap-1">
                {action.status === "starting" && <Loader2 className="size-3 animate-spin" aria-hidden />}
                {action.status === "running" && <Loader2 className="size-3 animate-spin" aria-hidden />}
                {action.status === "completed" && <Check className="size-3" aria-hidden />}
                {action.status === "failed" && <X className="size-3" aria-hidden />}
                {statusLabel(action)}
              </Badge>
            </div>
            {running && (
              <p className="mt-1 text-sm text-muted-foreground">
                {action.status === "starting" ? "Starting..." : action.currentStep || action.label}
              </p>
            )}
          </div>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {onRetry && action.status === "failed" && (
            <Button variant="outline" size="sm" onClick={onRetry}>
              <RefreshCw className="size-3.5" aria-hidden /> Retry
            </Button>
          )}
          {onClose && (
            <Button variant="ghost" size="sm" className="size-8 p-0" onClick={onClose} aria-label="Close result">
              <X className="size-4" aria-hidden />
            </Button>
          )}
        </div>
      </CardHeader>

      <CardContent className="space-y-4 pt-4" role="status" aria-live="polite">
        <div className="grid gap-2 text-sm text-muted-foreground md:grid-cols-3">
          <Meta label="Started" value={formatTime(action.startedAt)} />
          {action.completedAt && <Meta label="Finished" value={formatTime(action.completedAt)} />}
          {running ? (
            <Meta label="Duration" value="In progress" />
          ) : (
            <Meta label="Duration" value={formatDuration(action.durationMs)} />
          )}
          {action.meta &&
            Object.entries(action.meta).map(([key, value]) => <Meta key={key} label={key} value={value} />)}
        </div>

        {action.steps.length > 0 && (
          <div className="space-y-1.5">
            <p className="text-xs font-medium uppercase tracking-widest text-muted-foreground">Steps</p>
            <ActionTimeline steps={action.steps} />
          </div>
        )}

        {running && action.activity.length > 1 && <ActionActivity action={action} />}

        {(action.aiUsed || action.aiUnavailable) && <TokenUsagePanel action={action} />}

        {action.status === "completed" && (
          <ActionResult
            action={action}
            renderResult={renderResult}
            resultSummary={resultSummary}
          />
        )}

        {action.status === "failed" && action.error && <ActionError action={action} />}
      </CardContent>
    </Card>
  );
}

function ActionStatusIcon({ action }: { action: ActionState }) {
  switch (action.status) {
    case "starting":
    case "running":
      return <Loader2 className="size-6 shrink-0 animate-spin text-primary" aria-hidden />;
    case "completed":
      return action.aiUnavailable ? (
        <AlertTriangle className="size-6 shrink-0 text-amber-500" aria-hidden />
      ) : (
        <CheckCircle2 className="size-6 shrink-0 text-green-500" aria-hidden />
      );
    case "failed":
      return <XCircle className="size-6 shrink-0 text-red-500" aria-hidden />;
    default:
      return <Circle className="size-6 shrink-0 text-muted-foreground" aria-hidden />;
  }
}

function statusLabel(action: ActionState): string {
  switch (action.status) {
    case "starting":
      return "Starting";
    case "running":
      return "Running";
    case "completed":
      return action.aiUnavailable ? "Completed with fallback" : "Completed";
    case "failed":
      return "Failed";
    default:
      return "Idle";
  }
}

function badgeVariant(status: ActionState["status"]): "default" | "secondary" | "success" | "failed" | "running" {
  switch (status) {
    case "completed":
      return "success";
    case "failed":
      return "failed";
    case "starting":
    case "running":
      return "running";
    default:
      return "secondary";
  }
}

function Meta({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center gap-2 min-w-0">
      <span className="text-xs text-muted-foreground capitalize">{label}:</span>
      <span className="truncate text-foreground">{value}</span>
    </div>
  );
}

function ActionTimeline({ steps }: { steps: ActionStep[] }) {
  return (
    <ol className="space-y-1">
      {steps.map((step, i) => (
        <li key={i} className="flex items-center gap-2 text-sm">
          {step.status === "done" && <Check className="size-4 shrink-0 text-green-500" aria-hidden />}
          {step.status === "active" && (
            <Loader2 className="size-4 shrink-0 animate-spin text-primary" aria-hidden />
          )}
          {step.status === "error" && <XCircle className="size-4 shrink-0 text-red-500" aria-hidden />}
          {step.status === "pending" && <Circle className="size-4 shrink-0 text-muted-foreground/50" aria-hidden />}
          <span
            className={cn(
              "break-words",
              step.status === "done" && "text-muted-foreground",
              step.status === "active" && "font-medium",
              step.status === "pending" && "text-muted-foreground/60",
              step.status === "error" && "text-red-500 font-medium",
            )}
          >
            {step.name}
          </span>
        </li>
      ))}
    </ol>
  );
}

function ActionActivity({ action }: { action: ActionState }) {
  const [open, setOpen] = useState(false);
  const entries = [...action.activity].slice(1);
  if (entries.length === 0) return null;
  return (
    <div className="rounded-lg border border-border bg-muted/30">
      <button
        type="button"
        className="flex w-full items-center justify-between px-3 py-2 text-xs font-medium uppercase tracking-widest text-muted-foreground"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
      >
        <span>Activity</span>
        {open ? <ChevronDown className="size-3.5" aria-hidden /> : <ChevronRight className="size-3.5" aria-hidden />}
      </button>
      {open && (
        <ul className="space-y-1 px-3 pb-3 text-xs">
          {entries.map((entry, i) => (
            <li key={i} className="flex items-baseline gap-2">
              <span className="shrink-0 text-muted-foreground">{formatTime(entry.time)}</span>
              <span className={cn(entry.tone === "error" && "text-red-500", entry.tone === "success" && "text-green-600 dark:text-green-400")}>
                {entry.message}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function ActionResult({
  action,
  renderResult,
  resultSummary,
}: {
  action: ActionState;
  renderResult?: (action: ActionState) => React.ReactNode;
  resultSummary?: (action: ActionState) => ActionResultSummary | null;
}) {
  if (renderResult) {
    return <div className="rounded-lg border border-green-500/40 bg-green-500/5 p-3">{renderResult(action)}</div>;
  }
  const summary = resultSummary?.(action) ?? null;
  if (!summary) {
    return (
      <div className="rounded-lg border border-green-500/40 bg-green-500/5 p-3">
        <p className="flex items-center gap-2 font-medium text-green-600 dark:text-green-400">
          <CheckCircle2 className="size-4" aria-hidden /> Completed
        </p>
      </div>
    );
  }
  return (
    <div className="rounded-lg border border-green-500/40 bg-green-500/5 p-3">
      <p className="flex items-center gap-2 font-medium text-green-600 dark:text-green-400">
        <CheckCircle2 className="size-4" aria-hidden /> {summary.title}
      </p>
      <dl className="mt-2 grid gap-2 sm:grid-cols-2">
        {summary.lines.map((line) => (
          <div key={line.label} className="min-w-0">
            <dt className="text-xs text-muted-foreground">{line.label}</dt>
            <dd className="truncate text-sm font-medium" title={line.value}>
              {line.value}
            </dd>
          </div>
        ))}
      </dl>
      {summary.actions && summary.actions.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-2">
          {summary.actions.map((actionBtn) => (
            <Button key={actionBtn.label} variant={actionBtn.variant ?? "default"} size="sm" onClick={actionBtn.onClick}>
              {actionBtn.label}
            </Button>
          ))}
        </div>
      )}
    </div>
  );
}

function ActionError({ action }: { action: ActionState }) {
  const error = action.error!;
  const [detailsOpen, setDetailsOpen] = useState(false);
  const [stackOpen, setStackOpen] = useState(false);
  return (
    <div className="rounded-lg border border-red-500/50 bg-red-500/5 p-3">
      <p className="flex items-center gap-2 font-medium text-red-600 dark:text-red-400">
        <XCircle className="size-4" aria-hidden /> {error.friendlyLabel}
      </p>
      <p className="mt-1 text-sm">
        <span className="font-medium">What happened: </span>
        {error.message}
      </p>

      <button
        type="button"
        className="mt-2 flex items-center gap-1 text-xs font-medium text-muted-foreground hover:text-foreground"
        onClick={() => setDetailsOpen(!detailsOpen)}
        aria-expanded={detailsOpen}
      >
        {detailsOpen ? <ChevronDown className="size-3.5" aria-hidden /> : <ChevronRight className="size-3.5" aria-hidden />}
        Error details
      </button>
      {detailsOpen && (
        <dl className="mt-2 space-y-1 rounded bg-muted p-3 text-xs">
          <ErrorDetail label="Error code" value={error.code} mono />
          <ErrorDetail label="Operation" value={action.name} />
          {error.operationId && <ErrorDetail label="Correlation ID" value={error.operationId} mono />}
          {error.status ? <ErrorDetail label="HTTP status" value={String(error.status)} /> : null}
          {action.completedAt && <ErrorDetail label="Failed at" value={formatTime(action.completedAt)} />}
          {action.meta && Object.entries(action.meta).map(([k, v]) => <ErrorDetail key={k} label={k} value={v} />)}
        </dl>
      )}

      {error.message && (
        <button
          type="button"
          className="mt-2 flex items-center gap-1 text-xs font-medium text-muted-foreground hover:text-foreground"
          onClick={() => setStackOpen(!stackOpen)}
          aria-expanded={stackOpen}
        >
          {stackOpen ? <ChevronDown className="size-3.5" aria-hidden /> : <ChevronRight className="size-3.5" aria-hidden />}
          Technical details
        </button>
      )}
      {stackOpen && (
        <pre className="mt-2 max-h-48 overflow-y-auto rounded bg-muted p-3 text-xs whitespace-pre-wrap break-words">
          <code>{error.message}</code>
        </pre>
      )}
    </div>
  );
}

function ErrorDetail({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex items-baseline gap-2">
      <dt className="shrink-0 text-muted-foreground">{label}:</dt>
      <dd className={cn("break-all", mono && "font-mono")}>{value}</dd>
    </div>
  );
}

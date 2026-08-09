"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import type { ActionError, ActionState, ActionUpdate, RunActionOptions } from "@/lib/action-types";
import { isAiFallbackError, toApiError, friendlyErrorLabel } from "@/lib/http";
import { getAccountUsage, type AccountUsage } from "@/lib/account-api";

let nextId = 0;

function newActionId(name: string): string {
  nextId += 1;
  return `${name.toLowerCase().replace(/[^a-z0-9]+/g, "-")}-${Date.now().toString(36)}-${nextId}`;
}

export function actionErrorFrom(err: unknown, fallback: string): ActionError {
  const api = toApiError(err, fallback);
  return {
    code: api.code,
    message: api.message,
    friendlyLabel: friendlyErrorLabel(api.code),
    operationId: api.operationId,
    status: api.status,
    aiFallback: isAiFallbackError(api.code),
  };
}

export interface ActionStarter {
  (name: string, label: string, options?: RunActionOptions): string | null;
}

interface ActionContextValue {
  /** Completed/failed actions plus the currently active one, newest first. */
  actions: ActionState[];
  activeAction: ActionState | null;
  accountUsage: AccountUsage | null;
  usageError: string | null;
  startAction: ActionStarter;
  updateAction: (id: string, updater: (current: ActionState) => ActionUpdate) => void;
  completeAction: (id: string, result: unknown) => void;
  failAction: (id: string, error: ActionError) => void;
  clearAction: (id: string) => void;
  refreshUsage: () => Promise<void>;
}

const ActionContext = createContext<ActionContextValue | null>(null);

export function ActionProvider({ children }: { children: React.ReactNode }) {
  const [actions, setActions] = useState<ActionState[]>([]);
  const [accountUsage, setAccountUsage] = useState<AccountUsage | null>(null);
  const [usageError, setUsageError] = useState<string | null>(null);
  const activeIdRef = useRef<Record<string, string>>({});

  const refreshUsage = useCallback(async () => {
    try {
      const usage = await getAccountUsage();
      setAccountUsage(usage);
      setUsageError(null);
    } catch {
      setUsageError("Token usage could not be loaded.");
    }
  }, []);

  useEffect(() => {
    let ignore = false;
    getAccountUsage()
      .then((usage) => {
        if (ignore) return;
        setAccountUsage(usage);
        setUsageError(null);
      })
      .catch(() => {
        if (!ignore) setUsageError("Token usage could not be loaded.");
      });
    return () => {
      ignore = true;
    };
  }, []);

  const startAction = useCallback<ActionStarter>((name, label, options) => {
    // Prevent duplicate concurrent actions of the same kind.
    const runningId = activeIdRef.current[name];
    if (runningId) {
      return null;
    }
    const id = newActionId(name);
    const steps = (options?.steps ?? []).map((step, i) => ({
      name: step,
      status: i === 0 ? ("active" as const) : ("pending" as const),
    }));
    const action: ActionState = {
      id,
      name,
      label,
      status: "starting",
      currentStep: steps[0]?.name ?? label,
      steps,
      activity: [{ time: Date.now(), message: `Started ${name}`, tone: "neutral" }],
      startedAt: Date.now(),
      aiUsed: options?.aiUsed ?? false,
    };
    activeIdRef.current[name] = id;
    setActions((prev) => [action, ...prev]);
    return id;
  }, []);

  const patchAction = useCallback(
    (id: string, updater: (current: ActionState) => ActionUpdate) => {
      setActions((prev) =>
        prev.map((action) => (action.id === id ? { ...action, ...updater(action) } : action)),
      );
    },
    [],
  );

  const updateAction = useCallback(
    (id: string, updater: (current: ActionState) => ActionUpdate) => {
      patchAction(id, updater);
    },
    [patchAction],
  );

  const releaseActiveId = useCallback((id: string) => {
    for (const [name, activeId] of Object.entries(activeIdRef.current)) {
      if (activeId === id) {
        delete activeIdRef.current[name];
        return;
      }
    }
  }, []);

  const completeAction = useCallback(
    (id: string, result: unknown) => {
      setActions((prev) =>
        prev.map((action) => {
          if (action.id !== id) return action;
          const completedAt = Date.now();
          return {
            ...action,
            status: "completed",
            result,
            completedAt,
            durationMs: completedAt - action.startedAt,
            steps: action.steps.map((step) => ({ ...step, status: "done" as const })),
            currentStep: action.steps.length > 0 ? "All steps completed" : action.currentStep,
            activity: [...action.activity, { time: completedAt, message: `${action.name} completed`, tone: "success" }],
          };
        }),
      );
      releaseActiveId(id);
      void refreshUsage();
    },
    [releaseActiveId, refreshUsage],
  );

  const failAction = useCallback(
    (id: string, error: ActionError) => {
      setActions((prev) =>
        prev.map((action) => {
          if (action.id !== id) return action;
          const failedStep = action.steps.find((s) => s.status === "active");
          const completedAt = Date.now();
          return {
            ...action,
            status: "failed",
            error,
            completedAt,
            durationMs: completedAt - action.startedAt,
            steps: action.steps.map((step) =>
              step.name === failedStep?.name ? { ...step, status: "error" as const } : step,
            ),
            currentStep: failedStep ? `Failed at: ${failedStep.name}` : "Failed",
            activity: [...action.activity, { time: completedAt, message: `${action.name} failed`, tone: "error" }],
          };
        }),
      );
      releaseActiveId(id);
      void refreshUsage();
    },
    [releaseActiveId, refreshUsage],
  );

  const clearAction = useCallback((id: string) => {
    setActions((prev) => prev.filter((a) => a.id !== id));
  }, []);

  const activeAction = useMemo(() => actions.find((a) => a.status === "starting" || a.status === "running") ?? null, [actions]);

  const value = useMemo<ActionContextValue>(
    () => ({
      actions,
      activeAction,
      accountUsage,
      usageError,
      startAction,
      updateAction,
      completeAction,
      failAction,
      clearAction,
      refreshUsage,
    }),
    [actions, activeAction, accountUsage, usageError, startAction, updateAction, completeAction, failAction, clearAction, refreshUsage],
  );

  return <ActionContext.Provider value={value}>{children}</ActionContext.Provider>;
}

export function useActions(): ActionContextValue {
  const ctx = useContext(ActionContext);
  if (!ctx) {
    throw new Error("useActions must be used within an ActionProvider");
  }
  return ctx;
}

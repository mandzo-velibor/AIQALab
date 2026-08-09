"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { actionErrorFrom, useActions } from "@/lib/action-context";
import type { ActionState, RunActionOptions } from "@/lib/action-types";

export interface UseActionOptions extends RunActionOptions {
  /** Short action title, e.g. "Analyze". */
  name: string;
  /** Verb phrase shown while running, e.g. "Analyzing project...". */
  label: string;
}

/**
 * Drives one user action through the shared lifecycle (STARTING → RUNNING →
 * COMPLETED | FAILED) using the global ActionProvider. Callers get:
 *
 * - `run(fn)` – executes the operation inside the lifecycle.
 * - `state`  – the current ActionState bound to this action (for ActionProgress).
 * - `busy`   – true while starting/running (used to disable the trigger button).
 */
export function useAction(options: UseActionOptions) {
  const optionsRef = useRef(options);
  const timersRef = useRef<number[]>([]);
  const [stateId, setStateId] = useState<string | null>(null);

  useEffect(() => {
    optionsRef.current = options;
  });

  const { actions, startAction, updateAction, completeAction, failAction } = useActions();

  // Tidy up any pending timers when the component unmounts mid-flight.
  useEffect(
    () => () => {
      timersRef.current.forEach((t) => window.clearTimeout(t));
      timersRef.current.forEach((t) => window.clearInterval(t));
    },
    [],
  );

  const advancePhase = useCallback(
    (id: string) => {
      updateAction(id, (cur) => {
        if (cur.status !== "running" && cur.status !== "starting") return {};
        const idx = cur.steps.findIndex((s) => s.status === "active");
        const next = cur.steps.map((s, i) => {
          if (i <= idx) return { ...s, status: "done" as const };
          if (i === idx + 1) return { ...s, status: "active" as const };
          return s;
        });
        return { steps: next, currentStep: next[idx + 1]?.name ?? cur.currentStep };
      });
    },
    [updateAction],
  );

  const run = useCallback(
    async (runner: () => Promise<unknown>): Promise<unknown | null> => {
      const opts = optionsRef.current;
      const id = startAction(opts.name, opts.label, {
        steps: opts.steps,
        aiUsed: opts.aiUsed,
      });
      if (!id) {
        return null;
      }
      setStateId(id);

      const promote = window.setTimeout(() => {
        updateAction(id, (cur) => (cur.status === "starting" ? { status: "running" } : {}));
      }, 250);
      timersRef.current.push(promote);

      // Phase-level progress for synchronous backend calls: the phase list
      // reflects the real backend pipeline; we advance through it while the
      // request is in flight instead of showing a fake percentage.
      const interval = window.setInterval(() => advancePhase(id), 1400);
      timersRef.current.push(interval);

      try {
        const result = await runner();
        window.clearInterval(interval);
        completeAction(id, result);
        return result;
      } catch (err) {
        window.clearInterval(interval);
        failAction(id, actionErrorFrom(err, `${opts.name} failed`));
        throw err;
      }
    },
    [startAction, updateAction, advancePhase, completeAction, failAction],
  );

  const state: ActionState | null = stateId
    ? (actions.find((a) => a.id === stateId) ?? null)
    : null;

  const busy = state?.status === "starting" || state?.status === "running";

  return { run, state, busy };
}

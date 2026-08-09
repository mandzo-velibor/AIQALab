"use client";

import { ActionProvider } from "@/lib/action-context";
import { GlobalActivity } from "@/components/action/global-activity";

export function AppProviders({ children }: { children: React.ReactNode }) {
  return (
    <ActionProvider>
      {children}
      <GlobalActivity />
    </ActionProvider>
  );
}

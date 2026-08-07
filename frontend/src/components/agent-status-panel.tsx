"use client";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { AgentStatusEvent } from "@/lib/use-agent-websocket";

interface AgentStatusPanelProps {
  events: AgentStatusEvent[];
}

const AGENT_NAMES = ["Explorer", "Planner", "Executor", "Analyst", "Healing"];

function getStatusVariant(status: string) {
  switch (status) {
    case "COMPLETED":
      return "success" as const;
    case "RUNNING":
      return "running" as const;
    case "FAILED":
      return "failed" as const;
    default:
      return "secondary" as const;
  }
}

export function AgentStatusPanel({ events }: AgentStatusPanelProps) {
  const latestStatus = new Map<string, string>();
  for (const event of events) {
    latestStatus.set(event.agent, event.status);
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Agent Status</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {AGENT_NAMES.map((name) => {
          const status = latestStatus.get(name);
          return (
            <div key={name} className="flex items-center justify-between">
              <span className="text-sm font-medium">{name}</span>
              {status ? (
                <Badge variant={getStatusVariant(status)}>{status}</Badge>
              ) : (
                <Badge variant="outline">IDLE</Badge>
              )}
            </div>
          );
        })}
      </CardContent>
    </Card>
  );
}

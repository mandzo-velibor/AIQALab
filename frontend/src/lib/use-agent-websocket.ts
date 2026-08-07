"use client";

import { useEffect, useRef, useState, useCallback } from "react";

export interface AgentStatusEvent {
  agent: string;
  status: string;
  timestamp: number;
}

export function useAgentWebSocket() {
  const [events, setEvents] = useState<AgentStatusEvent[]>([]);
  const wsRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    const ws = new WebSocket("ws://localhost:8080/ws/agents");
    wsRef.current = ws;

    ws.onmessage = (e) => {
      try {
        const event: AgentStatusEvent = JSON.parse(e.data);
        setEvents((prev) => [...prev, event]);
      } catch {
        // ignore
      }
    };

    ws.onerror = () => {
      // silent
    };

    return () => {
      ws.close();
    };
  }, []);

  const clearEvents = useCallback(() => setEvents([]), []);

  return { events, clearEvents };
}

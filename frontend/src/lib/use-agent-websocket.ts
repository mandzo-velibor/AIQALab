"use client";

import { useEffect, useRef, useState, useCallback } from "react";
import { agentWebSocketUrl } from "@/lib/config";

export interface AgentStatusEvent {
  agent: string;
  status: string;
  timestamp: number;
}

export type ConnectionState = "connecting" | "open" | "closed";

const MAX_RETRY_MS = 15000;
const BASE_RETRY_MS = 1000;

/**
 * Subscribes to live agent status.
 *
 * Reconnects with bounded exponential backoff so a backend restart does not
 * require a page reload. The connection state is exposed so the UI can show
 * "reconnecting…" instead of silently appearing frozen — previously a failed
 * connection was swallowed by an empty onerror handler, which made live
 * progress look broken on any remote deployment.
 */
export function useAgentWebSocket() {
  const [events, setEvents] = useState<AgentStatusEvent[]>([]);
  const [connection, setConnection] = useState<ConnectionState>("connecting");
  const wsRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    let retryMs = BASE_RETRY_MS;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let closedByCaller = false;

    const connect = () => {
      if (closedByCaller) return;

      setConnection("connecting");
      let ws: WebSocket;
      try {
        ws = new WebSocket(agentWebSocketUrl());
      } catch {
        scheduleReconnect();
        return;
      }
      wsRef.current = ws;

      ws.onopen = () => {
        retryMs = BASE_RETRY_MS;
        setConnection("open");
      };

      ws.onmessage = (e) => {
        try {
          const event: AgentStatusEvent = JSON.parse(e.data);
          setEvents((prev) => [...prev, event]);
        } catch {
          // ignore malformed frames
        }
      };

      ws.onerror = () => {
        // onclose always follows; reconnect is handled there
      };

      ws.onclose = () => {
        wsRef.current = null;
        setConnection("closed");
        scheduleReconnect();
      };
    };

    const scheduleReconnect = () => {
      if (closedByCaller) return;
      timer = setTimeout(connect, retryMs);
      retryMs = Math.min(MAX_RETRY_MS, retryMs * 2);
    };

    connect();

    return () => {
      closedByCaller = true;
      if (timer) clearTimeout(timer);
      if (wsRef.current) {
        wsRef.current.onclose = null;
        wsRef.current.close();
        wsRef.current = null;
      }
    };
  }, []);

  const clearEvents = useCallback(() => setEvents([]), []);

  return { events, clearEvents, connection };
}

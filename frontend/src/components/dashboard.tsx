"use client";

import { useState, useCallback } from "react";
import { ExploreForm } from "@/components/explore-form";
import { ExploreResult } from "@/components/explore-result";
import { AgentStatusPanel } from "@/components/agent-status-panel";
import { ChatPanel } from "@/components/chat-panel";
import { exploreUrl, type ExploreResponse } from "@/lib/api";
import { useAgentWebSocket } from "@/lib/use-agent-websocket";
import { Button } from "@/components/ui/button";
import Link from "next/link";
import { useRouter } from "next/navigation";

interface ChatMessage {
  role: "user" | "system";
  content: string;
}

export function Dashboard() {
  const router = useRouter();
  const [result, setResult] = useState<ExploreResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const { events } = useAgentWebSocket();
  const [currentUrl, setCurrentUrl] = useState("");

  const handleExplore = useCallback(async (url: string) => {
    setCurrentUrl(url);
    setLoading(true);
    setResult(null);
    setMessages([{ role: "user", content: `Exploring ${url}` }]);

    try {
      const res = await exploreUrl(url);
      setResult(res);
      setMessages((prev) => [
        ...prev,
        { role: "system", content: `Exploration complete. Title: "${res.title}"` },
      ]);
    } catch (err) {
      setMessages((prev) => [
        ...prev,
        { role: "system", content: `Error: ${err instanceof Error ? err.message : "Unknown error"}` },
      ]);
    } finally {
      setLoading(false);
    }
  }, []);

  const handleGoToAnalyze = () => {
    if (currentUrl) {
      router.push(`/analyze?url=${encodeURIComponent(currentUrl)}`);
    } else {
      router.push("/analyze");
    }
  };

  return (
    <div className="flex-1 space-y-4 p-6">
      <div className="flex items-center justify-between">
        <h1 className="text-3xl font-bold tracking-tight">AI QA Lab</h1>
        <Button onClick={handleGoToAnalyze}>Analyze Page</Button>
      </div>

      <ExploreForm onExplore={handleExplore} loading={loading} />

      <div className="grid gap-4 md:grid-cols-3">
        <div className="md:col-span-2 space-y-4">
          <ExploreResult result={result} />
          <div className="min-h-[300px]">
            <ChatPanel messages={messages} />
          </div>
        </div>
        <div>
          <AgentStatusPanel events={events} />
        </div>
      </div>
    </div>
  );
}

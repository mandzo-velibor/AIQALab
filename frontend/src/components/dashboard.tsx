"use client";

import { useState, useCallback } from "react";
import { ExploreForm } from "@/components/explore-form";
import { ExploreResult } from "@/components/explore-result";
import { AgentStatusPanel } from "@/components/agent-status-panel";
import { ChatPanel } from "@/components/chat-panel";
import { exploreUrl, type ExploreResponse } from "@/lib/api";
import { useAgentWebSocket } from "@/lib/use-agent-websocket";
import { Button } from "@/components/ui/button";
import { Sparkles } from "lucide-react";
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
    <div className="flex-1 space-y-6 p-6">
      <div className="relative overflow-hidden rounded-2xl border border-border/70 bg-gradient-to-br from-indigo-500/15 via-violet-500/10 to-fuchsia-500/15 p-8">
        <div className="relative z-10 flex flex-col gap-6 md:flex-row md:items-center md:justify-between">
          <div className="space-y-2">
            <div className="flex items-center gap-2 text-xs font-medium uppercase tracking-widest text-primary">
              <span className="relative flex size-2">
                <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-violet-500 opacity-75" />
                <span className="relative inline-flex size-2 rounded-full bg-violet-500" />
              </span>
              AI-Powered QA
            </div>
            <h1 className="text-4xl font-bold tracking-tight">
              Explore any app with <span className="text-gradient">AI QA Lab</span>
            </h1>
            <p className="max-w-xl text-muted-foreground">
              Analyze pages, generate stable locators, plan and generate Playwright tests, run them,
              and let AI analyze failures and heal locators.
            </p>
            <div className="flex flex-wrap gap-2 pt-2">
              <Link href="/projects">
                <Button variant="outline" className="border-violet-500/40 bg-background/60">
                  View Projects
                </Button>
              </Link>
              <Button onClick={handleGoToAnalyze}>Analyze Page</Button>
            </div>
          </div>
          <div className="hidden shrink-0 md:block">
            <AsteriskOrbit />
          </div>
        </div>
      </div>

      <div className="grid gap-6 md:grid-cols-3">
        <div className="md:col-span-2 space-y-6">
          <ExploreForm onExplore={handleExplore} loading={loading} />
          <ExploreResult result={result} />
          <div className="min-h-[300px]">
            <ChatPanel messages={messages} />
          </div>
        </div>
        <div className="space-y-6">
          <AgentStatusPanel events={events} />
          <PipelinesCard />
        </div>
      </div>
    </div>
  );
}

function AsteriskOrbit() {
  return (
    <div className="relative flex size-40 items-center justify-center">
      <div className="absolute inset-0 rounded-full bg-gradient-to-br from-indigo-500 via-violet-500 to-fuchsia-500 opacity-20 blur-2xl animate-glow-pulse" />
      <div className="absolute inset-4 rounded-full border border-violet-500/30 animate-spin [animation-duration:8s]" />
      <div className="absolute inset-10 rounded-full border border-fuchsia-500/30 animate-spin [animation-duration:12s] [animation-direction:reverse]" />
      <div className="absolute inset-0 flex items-center justify-center">
        <span className="flex size-16 items-center justify-center rounded-2xl bg-gradient-to-br from-indigo-500 via-violet-500 to-fuchsia-500 shadow-xl shadow-violet-500/40 animate-float">
          <Sparkles className="size-8 text-white" />
        </span>
      </div>
    </div>
  );
}

function PipelinesCard() {
  const items = [
    { label: "Page Analysis", value: "Explore", color: "bg-indigo-500" },
    { label: "Locators", value: "Generate", color: "bg-violet-500" },
    { label: "Tests", value: "Create + Run", color: "bg-fuchsia-500" },
    { label: "Failures", value: "Heal", color: "bg-blue-500" },
  ];
  return (
    <div className="rounded-xl border border-border/70 bg-card/80 backdrop-blur-sm p-6">
      <p className="text-sm font-semibold">Agent Pipeline</p>
      <div className="mt-4 space-y-3">
        {items.map((item) => (
          <div key={item.label} className="flex items-center gap-3">
            <span className={`size-2.5 shrink-0 rounded-full ${item.color} animate-glow-pulse`} />
            <span className="flex-1 text-sm text-muted-foreground">{item.label}</span>
            <span className="rounded-md border border-border bg-muted/50 px-2 py-0.5 text-xs font-medium">
              {item.value}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

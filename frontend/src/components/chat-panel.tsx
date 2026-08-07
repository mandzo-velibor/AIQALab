"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

interface ChatMessage {
  role: "user" | "system";
  content: string;
}

interface ChatPanelProps {
  messages: ChatMessage[];
}

export function ChatPanel({ messages }: ChatPanelProps) {
  return (
    <Card className="flex flex-col h-full">
      <CardHeader>
        <CardTitle>Agent Log</CardTitle>
      </CardHeader>
      <CardContent className="flex-1 overflow-y-auto space-y-3">
        {messages.length === 0 && (
          <p className="text-sm text-muted-foreground">
            No activity yet. Enter a URL to start exploring.
          </p>
        )}
        {messages.map((msg, i) => (
          <div
            key={i}
            className={`rounded-lg p-3 text-sm ${
              msg.role === "user"
                ? "bg-primary text-primary-foreground ml-8"
                : "bg-muted mr-8"
            }`}
          >
            {msg.content}
          </div>
        ))}
      </CardContent>
    </Card>
  );
}

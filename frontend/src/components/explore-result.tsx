"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { ExploreResponse } from "@/lib/api";

interface ExploreResultProps {
  result: ExploreResponse | null;
}

export function ExploreResult({ result }: ExploreResultProps) {
  if (!result) return null;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Exploration Result</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-2 gap-4">
          <div>
            <p className="text-sm text-muted-foreground">Title</p>
            <p className="font-medium">{result.title || "N/A"}</p>
          </div>
          <div>
            <p className="text-sm text-muted-foreground">URL</p>
            <p className="font-medium text-xs break-all">{result.url}</p>
          </div>
          <div>
            <p className="text-sm text-muted-foreground">Buttons</p>
            <p className="font-medium text-2xl">{result.buttonCount}</p>
          </div>
          <div>
            <p className="text-sm text-muted-foreground">Inputs</p>
            <p className="font-medium text-2xl">{result.inputCount}</p>
          </div>
          <div>
            <p className="text-sm text-muted-foreground">Links</p>
            <p className="font-medium text-2xl">{result.linkCount}</p>
          </div>
          <div>
            <p className="text-sm text-muted-foreground">Forms</p>
            <p className="font-medium text-2xl">{result.formCount}</p>
          </div>
        </div>

        {result.screenshotBase64 && (
          <div>
            <p className="text-sm text-muted-foreground mb-2">Screenshot</p>
            <img
              src={`data:image/png;base64,${result.screenshotBase64}`}
              alt="Page screenshot"
              className="rounded-lg border w-full"
            />
          </div>
        )}

        {result.agentResults && (
          <div>
            <p className="text-sm text-muted-foreground mb-2">Agent Results</p>
            <div className="space-y-1">
              {Object.entries(result.agentResults).map(([agent, data]) => (
                <div key={agent} className="flex items-center justify-between text-sm">
                  <span>{agent}</span>
                  <span className={data.success ? "text-green-500" : "text-red-500"}>
                    {data.success ? "OK" : "FAIL"} - {data.message}
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

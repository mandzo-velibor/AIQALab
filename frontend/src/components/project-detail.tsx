"use client";

import { useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { getProjectHistory, type ProjectResponse, type ProjectHistoryResponse } from "@/lib/project-api";
import { getSuggestions, analyzeExecution, generateHealing, type HealingSuggestion } from "@/lib/healing-api";
import { ProjectHistory } from "@/components/project-history";
import { HealingDashboard } from "@/components/healing-dashboard";
import Link from "next/link";

interface ProjectDetailProps {
  projectId: number;
  project: ProjectResponse | null;
  history: ProjectHistoryResponse | null;
  suggestions: HealingSuggestion[];
}

export function ProjectDetail({ projectId, project, history: initialHistory, suggestions: initialSuggestions }: ProjectDetailProps) {
  const [history, setHistory] = useState<ProjectHistoryResponse | null>(initialHistory);
  const [suggestions, setSuggestions] = useState<HealingSuggestion[]>(initialSuggestions);
  const [error, setError] = useState<string | null>(null);
  const [analyzing, setAnalyzing] = useState(false);
  const [healing, setHealing] = useState(false);
  const [refreshing, setRefreshing] = useState(false);

  const refresh = async () => {
    setRefreshing(true);
    setError(null);
    try {
      const [hist, sugg] = await Promise.all([getProjectHistory(projectId), getSuggestions(projectId)]);
      setHistory(hist);
      setSuggestions(sugg);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to refresh project");
    } finally {
      setRefreshing(false);
    }
  };

  const handleAnalyzeFailure = async (executionId: number) => {
    setAnalyzing(true);
    setError(null);
    try {
      await analyzeExecution(executionId, projectId);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Analysis failed");
    } finally {
      setAnalyzing(false);
    }
  };

  const handleGenerateHealing = async (executionId: number) => {
    setHealing(true);
    setError(null);
    try {
      await generateHealing(executionId);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Healing generation failed");
    } finally {
      setHealing(false);
    }
  };

  return (
    <div className="flex-1 space-y-4 p-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold tracking-tight">{project?.name ?? "Project"}</h1>
          {project?.baseUrl && <p className="text-sm text-muted-foreground">{project.baseUrl}</p>}
        </div>
        <div className="flex gap-2">
          {project && <Badge variant="secondary">{project.framework}</Badge>}
          <Link href="/projects">
            <Button variant="outline">Back to Projects</Button>
          </Link>
        </div>
      </div>

      {error && (
        <Card className="border-red-500">
          <CardContent className="pt-6">
            <p className="text-red-500">{error}</p>
          </CardContent>
        </Card>
      )}

      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between">
            <CardTitle>Executions ({history?.executions.length ?? 0})</CardTitle>
            <Button variant="outline" size="sm" disabled={refreshing} onClick={refresh}>
              {refreshing ? "Refreshing..." : "Refresh"}
            </Button>
          </CardHeader>
          <CardContent className="space-y-2">
            {(history?.executions.length ?? 0) === 0 ? (
              <p className="text-sm text-muted-foreground">No executions for this project yet.</p>
            ) : (
              history?.executions.map((exec) => (
                <div key={exec.id} className="border rounded-lg p-3 space-y-2">
                  <div className="flex items-center justify-between gap-2">
                    <div className="flex items-center gap-2 min-w-0">
                      <Badge
                        variant={exec.status === "PASSED" ? "success" : exec.status === "FAILED" ? "failed" : "running"}
                      >
                        {exec.status}
                      </Badge>
                      <span className="text-sm font-medium truncate">{exec.testFile}</span>
                    </div>
                    <span className="text-xs text-muted-foreground">
                      {exec.duration ? `${exec.duration}ms` : ""}
                    </span>
                  </div>
                  <p className="text-xs text-muted-foreground">
                    {new Date(exec.createdAt).toLocaleString()}
                  </p>
                  {exec.status === "FAILED" && (
                    <div className="flex gap-2">
                      <Button variant="outline" size="sm" disabled={analyzing} onClick={() => handleAnalyzeFailure(exec.id)}>
                        {analyzing ? "Analyzing..." : "Analyze"}
                      </Button>
                      <Button variant="outline" size="sm" disabled={healing} onClick={() => handleGenerateHealing(exec.id)}>
                        {healing ? "Healing..." : "Generate Healing"}
                      </Button>
                    </div>
                  )}
                  {exec.errorMessage && (
                    <pre className="bg-red-50 dark:bg-red-950/20 p-2 rounded text-xs overflow-x-auto">
                      <code>{exec.errorMessage}</code>
                    </pre>
                  )}
                </div>
              ))
            )}
          </CardContent>
        </Card>

        <ProjectHistory
          history={history}
          onGenerateHealing={handleGenerateHealing}
          onRefresh={refresh}
          healing={healing}
        />

        <HealingDashboard suggestions={suggestions} onSuggestionChanged={refresh} />
      </div>
    </div>
  );
}

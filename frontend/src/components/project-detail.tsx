"use client";

import { useEffect, useRef, useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { getProjectHistory, type ProjectResponse, type ProjectHistoryResponse } from "@/lib/project-api";
import { getSuggestions, analyzeExecution, generateHealing, type HealingSuggestion } from "@/lib/healing-api";
import { ProjectHistory } from "@/components/project-history";
import { HealingDashboard } from "@/components/healing-dashboard";
import { QaWorkflow } from "@/components/qa-workflow";
import Link from "next/link";
import { useRouter } from "next/navigation";

interface ProjectDetailProps {
  projectId: number;
  project: ProjectResponse | null;
  history: ProjectHistoryResponse | null;
  suggestions: HealingSuggestion[];
}

export function ProjectDetail({ projectId, project, history: initialHistory, suggestions: initialSuggestions }: ProjectDetailProps) {
  const router = useRouter();
  const [history, setHistory] = useState<ProjectHistoryResponse | null>(initialHistory);
  const [suggestions, setSuggestions] = useState<HealingSuggestion[]>(initialSuggestions);
  const [error, setError] = useState<string | null>(null);
  const [analyzing, setAnalyzing] = useState(false);
  const [healing, setHealing] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const errorRef = useRef<HTMLDivElement>(null);

  // Errors are rendered in a banner near the top of the page, so surface it
  // even when the user is scrolled down at the point where they clicked.
  useEffect(() => {
    if (error) {
      errorRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
    }
  }, [error]);

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

  const handleGoToAnalyze = () => {
    const baseUrl = project?.baseUrl || "";
    router.push(`/analyze?url=${encodeURIComponent(baseUrl)}&projectId=${projectId}`);
  };

  return (
    <div className="flex-1 space-y-6 p-6">
      <div className="flex items-center justify-between">
        <div>
          <div className="text-xs font-medium uppercase tracking-widest text-primary">Project</div>
          <h1 className="text-3xl font-bold tracking-tight">{project?.name ?? "Project"}</h1>
          {project?.baseUrl && <p className="text-sm text-muted-foreground">{project.baseUrl}</p>}
        </div>
        <div className="flex gap-2">
          {project && <Badge variant="secondary">{project.framework}</Badge>}
          <Button onClick={handleGoToAnalyze} disabled={!project?.baseUrl}>
            Run QA Workflow
          </Button>
          <Link href="/projects">
            <Button variant="outline">Back to Projects</Button>
          </Link>
        </div>
      </div>

      {error && (
        <div ref={errorRef} className="scroll-mt-4">
          <Card className="border-red-500 bg-red-50 dark:bg-red-950/20">
            <CardContent className="pt-6" role="alert">
              <p className="text-red-600 dark:text-red-400">{error}</p>
            </CardContent>
          </Card>
        </div>
      )}

      {project?.baseUrl && (
        <Card>
          <CardHeader>
            <CardTitle>QA Workflow</CardTitle>
          </CardHeader>
          <CardContent>
            <QaWorkflow
              url={project.baseUrl}
              projectId={projectId}
              onHistoryChanged={refresh}
            />
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

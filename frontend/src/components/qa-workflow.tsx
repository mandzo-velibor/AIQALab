"use client";

import { useCallback, useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { analyzeUrl, type AnalysisResponse } from "@/lib/analysis-api";
import { generateLocators, getLocators, type LocatorDto } from "@/lib/locator-api";
import { generateTestPlan, getTestPlans, type TestScenarioDto } from "@/lib/testplan-api";
import { generateTests, getTests, type GeneratedTestDto } from "@/lib/testgen-api";
import { runTest, runAllTests, getExecutionHistory, type TestExecution } from "@/lib/execution-api";
import { LocatorRepository } from "@/components/locator-repository";
import { TestPlanSection } from "@/components/test-plan-section";
import { GeneratedTestsSection } from "@/components/generated-tests-section";
import { ExecutionDashboard } from "@/components/execution-dashboard";

interface QaWorkflowProps {
  url: string;
  projectId?: number;
  onHistoryChanged?: () => void;
}

export function QaWorkflow({ url: initialUrl, projectId, onHistoryChanged }: QaWorkflowProps) {
  const [url, setUrl] = useState(initialUrl);
  const [prevUrl, setPrevUrl] = useState(initialUrl);
  const [prevProjectId, setPrevProjectId] = useState(projectId);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<AnalysisResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [locators, setLocators] = useState<LocatorDto[]>([]);
  const [locatorsLoading, setLocatorsLoading] = useState(false);

  const [scenarios, setScenarios] = useState<TestScenarioDto[]>([]);
  const [scenariosLoading, setScenariosLoading] = useState(false);

  const [tests, setTests] = useState<GeneratedTestDto[]>([]);
  const [testsLoading, setTestsLoading] = useState(false);

  const [executions, setExecutions] = useState<TestExecution[]>([]);
  const [executionsLoading, setExecutionsLoading] = useState(false);

  if (initialUrl !== prevUrl || projectId !== prevProjectId) {
    setPrevUrl(initialUrl);
    setPrevProjectId(projectId);
    setUrl(initialUrl);
  }

  const loadSavedData = useCallback(
    async (pageUrl: string) => {
      try {
        const [loc, plans, savedTests, execs] = await Promise.all([
          getLocators(pageUrl),
          getTestPlans(pageUrl),
          getTests(pageUrl),
          getExecutionHistory(projectId),
        ]);
        setError(null);
        setLocators(loc);
        setScenarios(plans.length > 0 ? plans[0].scenarios : []);
        setTests(savedTests);
        setExecutions(execs);
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed to load saved data");
      }
    },
    [projectId],
  );

  useEffect(() => {
    if (initialUrl) {
      void Promise.resolve().then(() => loadSavedData(initialUrl));
    }
  }, [initialUrl, loadSavedData]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!url.trim()) return;

    setLoading(true);
    setError(null);
    setResult(null);
    setLocators([]);
    setScenarios([]);
    setTests([]);
    setExecutions([]);

    try {
      const response = await analyzeUrl(url.trim(), false, projectId);
      setResult(response);
      onHistoryChanged?.();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Analysis failed");
    } finally {
      setLoading(false);
    }
  };

  const handleGenerateLocators = async () => {
    if (!url.trim()) return;

    setLocatorsLoading(true);
    setError(null);
    try {
      const response = await generateLocators(url.trim(), projectId);
      setLocators(response.locators);
      onHistoryChanged?.();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Locator generation failed");
    } finally {
      setLocatorsLoading(false);
    }
  };

  const handleGenerateTestPlan = async () => {
    if (!url.trim()) return;

    setScenariosLoading(true);
    setError(null);
    try {
      const response = await generateTestPlan(url.trim(), projectId);
      setScenarios(response.scenarios);
      onHistoryChanged?.();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Test plan generation failed");
    } finally {
      setScenariosLoading(false);
    }
  };

  const handleGenerateTests = async () => {
    if (!url.trim()) return;

    setTestsLoading(true);
    setError(null);
    try {
      const response = await generateTests(url.trim(), projectId);
      setTests(response.tests);
      onHistoryChanged?.();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Test generation failed");
    } finally {
      setTestsLoading(false);
    }
  };

  const handleRunAllTests = async () => {
    setExecutionsLoading(true);
    setError(null);
    try {
      await runAllTests(projectId);
      const history = await getExecutionHistory(projectId);
      setExecutions(history);
      onHistoryChanged?.();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Execution failed");
    } finally {
      setExecutionsLoading(false);
    }
  };

  const handleRunTest = async (testId: number) => {
    setExecutionsLoading(true);
    setError(null);
    try {
      await runTest(testId, projectId);
      const history = await getExecutionHistory(projectId);
      setExecutions(history);
      onHistoryChanged?.();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Execution failed");
    } finally {
      setExecutionsLoading(false);
    }
  };

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Enter URL to Analyze</CardTitle>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit} className="flex gap-2">
            <Input
              type="url"
              placeholder="https://example.com"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              className="flex-1"
              required
            />
            <Button type="submit" disabled={loading}>
              {loading ? "Analyzing..." : "Analyze"}
            </Button>
          </form>
        </CardContent>
      </Card>

      {error && (
        <Card className="border-red-500">
          <CardContent className="pt-6">
            <p className="text-red-500">{error}</p>
          </CardContent>
        </Card>
      )}

      {result && (
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          <Card>
            <CardHeader>
              <CardTitle>Page Type</CardTitle>
            </CardHeader>
            <CardContent>
              <Badge variant="default" className="text-lg px-3 py-1">
                {result.pageType || "Unknown"}
              </Badge>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Summary</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground">{result.summary || "No summary available"}</p>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Confidence</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="flex items-center gap-2">
                <div className="flex-1 bg-muted rounded-full h-2">
                  <div
                    className="bg-primary h-2 rounded-full transition-all"
                    style={{ width: `${result.confidence}%` }}
                  />
                </div>
                <span className="text-sm font-medium">{result.confidence}%</span>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Forms ({result.forms.length})</CardTitle>
            </CardHeader>
            <CardContent>
              {result.forms.length === 0 ? (
                <p className="text-sm text-muted-foreground">No forms detected</p>
              ) : (
                <div className="space-y-2">
                  {result.forms.map((form, i) => (
                    <div key={i} className="text-sm">
                      <p className="font-medium">{form.name}</p>
                      <p className="text-muted-foreground text-xs">
                        Inputs: {form.inputs.join(", ")}
                      </p>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Buttons ({result.buttons.length})</CardTitle>
            </CardHeader>
            <CardContent>
              {result.buttons.length === 0 ? (
                <p className="text-sm text-muted-foreground">No buttons detected</p>
              ) : (
                <div className="flex flex-wrap gap-2">
                  {result.buttons.map((btn, i) => (
                    <Badge key={i} variant="secondary">{btn}</Badge>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Navigation ({result.navigation.length})</CardTitle>
            </CardHeader>
            <CardContent>
              {result.navigation.length === 0 ? (
                <p className="text-sm text-muted-foreground">No navigation detected</p>
              ) : (
                <div className="space-y-1">
                  {result.navigation.map((nav, i) => (
                    <div key={i} className="text-sm">
                      <span className="font-medium">{nav.name}</span>
                      <span className="text-muted-foreground"> → {nav.target}</span>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Possible Flows ({result.possibleFlows.length})</CardTitle>
            </CardHeader>
            <CardContent>
              {result.possibleFlows.length === 0 ? (
                <p className="text-sm text-muted-foreground">No flows detected</p>
              ) : (
                <div className="space-y-2">
                  {result.possibleFlows.map((flow, i) => (
                    <div key={i} className="text-sm">
                      <p className="font-medium">{flow.name}</p>
                      <p className="text-muted-foreground text-xs">{flow.description}</p>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Risk Areas ({result.riskAreas.length})</CardTitle>
            </CardHeader>
            <CardContent>
              {result.riskAreas.length === 0 ? (
                <p className="text-sm text-muted-foreground">No risk areas detected</p>
              ) : (
                <div className="space-y-2">
                  {result.riskAreas.map((risk, i) => (
                    <div key={i} className="text-sm">
                      <Badge variant="destructive" className="mr-2">{risk.name}</Badge>
                      <span className="text-muted-foreground text-xs">{risk.reason}</span>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>

          <Card className="md:col-span-2 lg:col-span-3">
            <CardHeader>
              <CardTitle>Screenshot</CardTitle>
            </CardHeader>
            <CardContent>
              {result.screenshotBase64 ? (
                <img
                  src={`data:image/png;base64,${result.screenshotBase64}`}
                  alt="Page screenshot"
                  className="rounded-lg border w-full"
                />
              ) : (
                <p className="text-sm text-muted-foreground">No screenshot available</p>
              )}
            </CardContent>
          </Card>
        </div>
      )}

      {url.trim() && (
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          <LocatorRepository
            locators={locators}
            loading={locatorsLoading}
            onGenerate={handleGenerateLocators}
          />

          <TestPlanSection
            scenarios={scenarios}
            loading={scenariosLoading}
            onGenerate={handleGenerateTestPlan}
          />

          <GeneratedTestsSection
            tests={tests}
            loading={testsLoading}
            onGenerate={handleGenerateTests}
            onRunTest={handleRunTest}
          />

          <ExecutionDashboard
            executions={executions}
            loading={executionsLoading}
            onRunAll={handleRunAllTests}
          />
        </div>
      )}
    </div>
  );
}

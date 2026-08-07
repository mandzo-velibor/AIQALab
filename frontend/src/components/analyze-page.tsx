"use client";

import { Suspense, useState, useEffect } from "react";
import { useSearchParams } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { analyzeUrl, type AnalysisResponse } from "@/lib/analysis-api";
import { generateLocators, type LocatorDto } from "@/lib/locator-api";
import { generateTestPlan, type TestScenarioDto } from "@/lib/testplan-api";
import { generateTests, type GeneratedTestDto } from "@/lib/testgen-api";
import { runAllTests, getExecutionHistory, type TestExecution } from "@/lib/execution-api";
import { LocatorRepository } from "@/components/locator-repository";
import { TestPlanSection } from "@/components/test-plan-section";
import { GeneratedTestsSection } from "@/components/generated-tests-section";
import { ExecutionDashboard } from "@/components/execution-dashboard";
import Link from "next/link";

function AnalyzePageContent() {
  const searchParams = useSearchParams();
  const urlFromQuery = searchParams.get("url") || "";

  const [url, setUrl] = useState(urlFromQuery);
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

  useEffect(() => {
    if (urlFromQuery) {
      setUrl(urlFromQuery);
    }
  }, [urlFromQuery]);

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
      const response = await analyzeUrl(url.trim());
      setResult(response);
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
      const response = await generateLocators(url.trim());
      setLocators(response.locators);
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
      const response = await generateTestPlan(url.trim());
      setScenarios(response.scenarios);
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
      const response = await generateTests(url.trim());
      setTests(response.tests);
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
      await runAllTests();
      const history = await getExecutionHistory();
      setExecutions(history);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Execution failed");
    } finally {
      setExecutionsLoading(false);
    }
  };

  return (
    <div className="flex-1 space-y-4 p-6">
      <div className="flex items-center justify-between">
        <h1 className="text-3xl font-bold tracking-tight">Analyze Page</h1>
        <Link href="/">
          <Button variant="outline">Back to Dashboard</Button>
        </Link>
      </div>

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

export function AnalyzePage() {
  return (
    <Suspense fallback={<div className="flex-1 p-6">Loading...</div>}>
      <AnalyzePageContent />
    </Suspense>
  );
}

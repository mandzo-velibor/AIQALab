"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { analyzeUrl, type AnalysisResponse } from "@/lib/analysis-api";
import { generateLocators, getLocators, type LocatorDto } from "@/lib/locator-api";
import { generateTestPlan, getTestPlans, type TestScenarioDto } from "@/lib/testplan-api";
import { generateTests, getTests, type GeneratedTestDto } from "@/lib/testgen-api";
import { runTest, runAllTests, getExecutionHistory, type TestExecution } from "@/lib/execution-api";
import type { TestTypeValue } from "@/components/advanced-options";
import { useAction } from "@/lib/use-action";
import { ActionProgress } from "@/components/action/action-progress";
import { LocatorRepository } from "@/components/locator-repository";
import { TestPlanSection } from "@/components/test-plan-section";
import { GeneratedTestsSection } from "@/components/generated-tests-section";
import { ExecutionDashboard } from "@/components/execution-dashboard";

interface QaWorkflowProps {
  url: string;
  projectId?: number;
  onHistoryChanged?: () => void;
}

const ANALYZE_STEPS = ["Exploring page", "Detecting elements", "Generating analysis"];
const LOCATOR_STEPS = ["Scanning elements", "Extracting locators", "Scoring quality"];
const PLAN_STEPS = ["Mapping flows", "Drafting scenarios", "Assessing risk"];
const TEST_STEPS = ["Reading page", "Writing tests", "Validating assertions"];
const RUN_STEPS = ["Installing browsers", "Executing tests", "Collecting results"];

export function QaWorkflow({ url: initialUrl, projectId, onHistoryChanged }: QaWorkflowProps) {
  const [url, setUrl] = useState(initialUrl);
  const [prevUrl, setPrevUrl] = useState(initialUrl);
  const [prevProjectId, setPrevProjectId] = useState(projectId);
  const [result, setResult] = useState<AnalysisResponse | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [analyzeUsername, setAnalyzeUsername] = useState("");
  const [analyzePassword, setAnalyzePassword] = useState("");

  const [locators, setLocators] = useState<LocatorDto[]>([]);
  const [scenarios, setScenarios] = useState<TestScenarioDto[]>([]);
  const [tests, setTests] = useState<GeneratedTestDto[]>([]);
  const [executions, setExecutions] = useState<TestExecution[]>([]);
  const [highlightExecutionId, setHighlightExecutionId] = useState<number | null>(null);
  const executionSectionRef = useRef<HTMLDivElement>(null);

  const [locatorsInstruction, setLocatorsInstruction] = useState("");
  const [planInstruction, setPlanInstruction] = useState("");
  const [testsInstruction, setTestsInstruction] = useState("");
  const [testsTestType, setTestsTestType] = useState<TestTypeValue>("");
  const [runInstruction, setRunInstruction] = useState("");
  const [runTestType, setRunTestType] = useState<TestTypeValue>("");

  const analyzeAction = useAction({ name: "Analyze", label: "Analyzing page...", aiUsed: true, steps: ANALYZE_STEPS });
  const locatorsAction = useAction({ name: "Generate locators", label: "Generating locators...", aiUsed: true, steps: LOCATOR_STEPS, instruction: locatorsInstruction });
  const planAction = useAction({ name: "Generate test plan", label: "Generating test plan...", aiUsed: true, steps: PLAN_STEPS, instruction: planInstruction });
  const testsAction = useAction({ name: "Generate tests", label: "Generating tests...", aiUsed: true, steps: TEST_STEPS, instruction: testsInstruction, testType: testsTestType || undefined });
  const runAllAction = useAction({ name: "Run all tests", label: "Running all tests...", aiUsed: false, steps: RUN_STEPS, instruction: runInstruction, testType: runTestType || undefined });
  const runOneAction = useAction({ name: "Run test", label: "Running test...", aiUsed: false, steps: RUN_STEPS, instruction: runInstruction, testType: runTestType || undefined });

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
        setLoadError(null);
        setLocators(loc);
        setScenarios(plans.length > 0 ? plans[0].scenarios : []);
        setTests(savedTests);
        setExecutions(execs);
      } catch (err) {
        setLoadError(err instanceof Error ? err.message : "Failed to load saved data");
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

    setResult(null);
    setLocators([]);
    setScenarios([]);
    setTests([]);
    setExecutions([]);

    try {
      await analyzeAction.run(async () => {
        const response = await analyzeUrl(url.trim(), false, projectId, analyzeUsername.trim() || undefined, analyzePassword || undefined);
        setResult(response);
        onHistoryChanged?.();
        return response;
      });
    } catch {
      // Error is surfaced by ActionProgress.
    }
  };

  const handleGenerateLocators = async () => {
    if (!url.trim()) return;
    try {
      await locatorsAction.run(async () => {
        const response = await generateLocators(url.trim(), projectId, locatorsInstruction.trim() || undefined);
        setLocators(response.locators);
        onHistoryChanged?.();
        return response;
      });
    } catch {
      // Error is surfaced by ActionProgress.
    }
  };

  const handleGenerateTestPlan = async () => {
    if (!url.trim()) return;
    try {
      await planAction.run(async () => {
        const response = await generateTestPlan(url.trim(), projectId, planInstruction.trim() || undefined);
        setScenarios(response.scenarios);
        onHistoryChanged?.();
        return response;
      });
    } catch {
      // Error is surfaced by ActionProgress.
    }
  };

  const handleGenerateTests = async () => {
    if (!url.trim()) return;
    try {
      await testsAction.run(async () => {
        const response = await generateTests(
          url.trim(),
          projectId,
          testsInstruction.trim() || undefined,
          testsTestType || undefined,
        );
        setTests(response.tests);
        onHistoryChanged?.();
        return response;
      });
    } catch {
      // Error is surfaced by ActionProgress.
    }
  };

  const handleRunAllTests = async () => {
    try {
      await runAllAction.run(async () => {
        await runAllTests(projectId, runTestType || undefined, runInstruction.trim() || undefined);
        const history = await getExecutionHistory(projectId);
        setExecutions(history);
        highlightNewExecution(history);
        onHistoryChanged?.();
        return history;
      });
    } catch {
      // Error is surfaced by ActionProgress.
    }
  };

  const handleRunTest = async (testId: number) => {
    try {
      await runOneAction.run(async () => {
        await runTest(testId, projectId, runTestType || undefined, runInstruction.trim() || undefined);
        const history = await getExecutionHistory(projectId);
        setExecutions(history);
        highlightNewExecution(history);
        onHistoryChanged?.();
        return history;
      });
    } catch {
      // Error is surfaced by ActionProgress.
    }
  };

  const highlightNewExecution = (history: TestExecution[]) => {
    if (history.length === 0) return;
    const newest = history[0];
    setHighlightExecutionId(newest.id);
    setTimeout(() => {
      executionSectionRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
    }, 100);
  };

  const analyzeBusy = analyzeAction.busy;

  return (
    <div className="space-y-4">
      <Card className="relative overflow-hidden border-violet-500/30">
        <div className="pointer-events-none absolute inset-0 bg-gradient-to-r from-indigo-500/10 via-violet-500/10 to-fuchsia-500/10" />
        <CardHeader>
          <CardTitle className="relative">Enter URL to Analyze</CardTitle>
        </CardHeader>
        <CardContent className="relative space-y-3">
          <form onSubmit={handleSubmit} className="space-y-2">
            <Input
              type="url"
              placeholder="https://example.com"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              className="border-violet-500/30 bg-background/60"
              required
            />
            <div className="flex gap-2">
              <Input
                type="text"
                placeholder="Username (optional, for login)"
                value={analyzeUsername}
                onChange={(e) => setAnalyzeUsername(e.target.value)}
                className="flex-1 border-violet-500/30 bg-background/60"
              />
              <Input
                type="password"
                placeholder="Password (optional, for login)"
                value={analyzePassword}
                onChange={(e) => setAnalyzePassword(e.target.value)}
                className="flex-1 border-violet-500/30 bg-background/60"
              />
            </div>
            <Button type="submit" disabled={analyzeBusy} className="w-full">
              {analyzeBusy ? "Analyzing..." : "Analyze"}
            </Button>
          </form>

          {analyzeAction.state && <ActionProgress action={analyzeAction.state} />}

          {loadError && <p className="text-sm text-red-500">{loadError}</p>}
        </CardContent>
      </Card>

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
          <div className="space-y-3">
            {locatorsAction.state && <ActionProgress action={locatorsAction.state} />}
            <LocatorRepository
              locators={locators}
              loading={locatorsAction.busy}
              onGenerate={handleGenerateLocators}
              instruction={locatorsInstruction}
              onInstructionChange={setLocatorsInstruction}
            />
          </div>

          <div className="space-y-3">
            {planAction.state && <ActionProgress action={planAction.state} />}
            <TestPlanSection
              scenarios={scenarios}
              loading={planAction.busy}
              onGenerate={handleGenerateTestPlan}
              instruction={planInstruction}
              onInstructionChange={setPlanInstruction}
            />
          </div>

          <div className="space-y-3">
            {testsAction.state && <ActionProgress action={testsAction.state} />}
            <GeneratedTestsSection
              tests={tests}
              loading={testsAction.busy}
              onGenerate={handleGenerateTests}
              onRunTest={handleRunTest}
              instruction={testsInstruction}
              testType={testsTestType}
              onInstructionChange={setTestsInstruction}
              onTestTypeChange={setTestsTestType}
            />
          </div>

          <div ref={executionSectionRef} className="md:col-span-2 lg:col-span-3 scroll-mt-4 space-y-3">
            {(runAllAction.state || runOneAction.state) && (
              <ActionProgress action={runAllAction.state ?? runOneAction.state!} />
            )}
            <ExecutionDashboard
              executions={executions}
              loading={runAllAction.busy || runOneAction.busy}
              onRunAll={handleRunAllTests}
              highlightExecutionId={highlightExecutionId}
              instruction={runInstruction}
              testType={runTestType}
              onInstructionChange={setRunInstruction}
              onTestTypeChange={setRunTestType}
            />
          </div>
        </div>
      )}
    </div>
  );
}

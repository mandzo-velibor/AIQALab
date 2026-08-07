export interface ExecutionResponse {
  executionId: number;
  status: string;
  duration: number;
  errorMessage: string | null;
  consoleLogs: string;
}

export interface TestExecution {
  id: number;
  testFile: string;
  status: string;
  duration: number | null;
  errorMessage: string | null;
  screenshotPath: string | null;
  videoPath: string | null;
  tracePath: string | null;
  consoleLogs: string | null;
  createdAt: string;
}

export async function runTest(testId: number): Promise<ExecutionResponse> {
  const res = await fetch("http://localhost:8080/api/executions/run", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ testId }),
  });

  if (!res.ok) {
    const error = await res.json().catch(() => ({ error: "Unknown error" }));
    throw new Error(error.message || error.error || `Execution failed: ${res.status}`);
  }

  return res.json();
}

export async function runAllTests(): Promise<ExecutionResponse> {
  const res = await fetch("http://localhost:8080/api/executions/run", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ runAll: true }),
  });

  if (!res.ok) {
    const error = await res.json().catch(() => ({ error: "Unknown error" }));
    throw new Error(error.message || error.error || `Execution failed: ${res.status}`);
  }

  return res.json();
}

export async function getExecutionHistory(): Promise<TestExecution[]> {
  const res = await fetch("http://localhost:8080/api/executions/history");

  if (!res.ok) {
    throw new Error(`Failed to fetch execution history: ${res.status}`);
  }

  return res.json();
}

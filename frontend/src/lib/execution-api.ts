import { API_BASE_URL } from "@/lib/config";
import { httpRequest } from "@/lib/http";
export interface ExecutionResponse {
  executionId: number;
  status: string;
  duration: number;
  errorMessage: string | null;
  consoleLogs: string;
}

export interface TestExecution {
  id: number;
  projectId: number | null;
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

export async function runTest(testId: number, projectId?: number): Promise<ExecutionResponse> {
  const res = await httpRequest(`${API_BASE_URL}/api/executions/run`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ testId, projectId: projectId ?? null }),
  });

  return res.json();
}

export async function runAllTests(projectId?: number): Promise<ExecutionResponse> {
  const res = await httpRequest(`${API_BASE_URL}/api/executions/run`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ runAll: true, projectId: projectId ?? null }),
  });

  return res.json();
}

export async function getExecutionHistory(projectId?: number): Promise<TestExecution[]> {
  const url = projectId
    ? `${API_BASE_URL}/api/executions/history?projectId=${projectId}`
    : `${API_BASE_URL}/api/executions/history`;

  const res = await httpRequest(url);

  return res.json();
}

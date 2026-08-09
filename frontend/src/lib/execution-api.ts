import { API_BASE_URL } from "@/lib/config";
import { httpRequest } from "@/lib/http";
import type { TestType } from "@/lib/testgen-api";

export interface ExecutionResponse {
  executionId: number;
  status: string;
  durationMs: number;
  errorMessage: string | null;
  consoleLogs: string;
  testType?: string | null;
  instruction?: string | null;
  note?: string | null;
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

export async function runTest(testId: number, projectId?: number, testType?: TestType, instruction?: string): Promise<ExecutionResponse> {
  const res = await httpRequest(`${API_BASE_URL}/api/executions/run`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ testId, projectId: projectId ?? null, testType: testType ?? null, instruction: instruction ?? null }),
  });

  return res.json();
}

export async function runAllTests(projectId?: number, testType?: TestType, instruction?: string): Promise<ExecutionResponse> {
  const res = await httpRequest(`${API_BASE_URL}/api/executions/run`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ runAll: true, projectId: projectId ?? null, testType: testType ?? null, instruction: instruction ?? null }),
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

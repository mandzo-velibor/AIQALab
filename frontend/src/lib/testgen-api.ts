import { API_BASE_URL } from "@/lib/config";
import { httpRequest } from "@/lib/http";
export type TestType = "ui" | "e2e" | "api";

export interface GeneratedTestDto {
  id: number;
  scenarioName: string;
  testType: string | null;
  testCode: string;
  pageObjectCode: string;
}

export interface TestGenResponse {
  generated: number;
  tests: GeneratedTestDto[];
  instruction?: string | null;
  testType?: string | null;
  note?: string | null;
}

export async function generateTests(url: string, projectId?: number, instruction?: string, testType?: TestType): Promise<TestGenResponse> {
  const res = await httpRequest(`${API_BASE_URL}/api/tests/generate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url, projectId: projectId ?? null, instruction: instruction ?? null, testType: testType ?? null }),
  });

  return res.json();
}

export async function getTests(url: string): Promise<GeneratedTestDto[]> {
  const res = await httpRequest(`${API_BASE_URL}/api/tests?url=${encodeURIComponent(url)}`);

  return res.json();
}

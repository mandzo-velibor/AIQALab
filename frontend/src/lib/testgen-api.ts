import { API_BASE_URL } from "@/lib/config";
import { httpRequest } from "@/lib/http";
export interface GeneratedTestDto {
  id: number;
  scenarioName: string;
  testCode: string;
  pageObjectCode: string;
}

export interface TestGenResponse {
  generated: number;
  tests: GeneratedTestDto[];
}

export async function generateTests(url: string, projectId?: number): Promise<TestGenResponse> {
  const res = await httpRequest(`${API_BASE_URL}/api/tests/generate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url, projectId }),
  });

  return res.json();
}

export async function getTests(url: string): Promise<GeneratedTestDto[]> {
  const res = await httpRequest(`${API_BASE_URL}/api/tests?url=${encodeURIComponent(url)}`);

  return res.json();
}

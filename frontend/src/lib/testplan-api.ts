import { v1Project } from "@/lib/v1-project";
import { API_BASE_URL } from "@/lib/config";
import { httpRequest } from "@/lib/http";
export interface TestScenarioDto {
  id: number;
  name: string;
  type: string;
  priority: string;
  description: string;
  steps: string[];
  requiredElements: string[];
}

export interface TestPlanResponse {
  scenarioCount: number;
  scenarios: TestScenarioDto[];
  instruction?: string | null;
}

export async function generateTestPlan(url: string, projectId?: number, instruction?: string): Promise<TestPlanResponse> {
  const res = await httpRequest(`${API_BASE_URL}/api/v1/test-plan`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ project: v1Project(projectId), url, instruction: instruction ?? null }),
  });

  return res.json();
}

export async function getTestPlans(url: string): Promise<TestPlanResponse[]> {
  const res = await httpRequest(`${API_BASE_URL}/api/v1/test-plans?url=${encodeURIComponent(url)}`);

  return res.json();
}

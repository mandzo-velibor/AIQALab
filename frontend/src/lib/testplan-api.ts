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
}

export async function generateTestPlan(url: string, projectId?: number): Promise<TestPlanResponse> {
  const res = await httpRequest(`${API_BASE_URL}/api/test-plans/generate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url, projectId }),
  });

  return res.json();
}

export async function getTestPlans(url: string): Promise<TestPlanResponse[]> {
  const res = await httpRequest(`${API_BASE_URL}/api/test-plans?url=${encodeURIComponent(url)}`);

  return res.json();
}

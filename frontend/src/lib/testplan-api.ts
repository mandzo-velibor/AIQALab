import { API_BASE_URL } from "@/lib/config";
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
  const res = await fetch(`${API_BASE_URL}/api/test-plans/generate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url, projectId }),
  });

  if (!res.ok) {
    const error = await res.json().catch(() => ({ error: "Unknown error" }));
    throw new Error(error.message || error.error || `Test plan generation failed: ${res.status}`);
  }

  return res.json();
}

export async function getTestPlans(url: string): Promise<TestPlanResponse[]> {
  const res = await fetch(`${API_BASE_URL}/api/test-plans?url=${encodeURIComponent(url)}`);

  if (!res.ok) {
    throw new Error(`Failed to fetch test plans: ${res.status}`);
  }

  return res.json();
}

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

export async function generateTestPlan(url: string): Promise<TestPlanResponse> {
  const res = await fetch("http://localhost:8080/api/test-plans/generate", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url }),
  });

  if (!res.ok) {
    throw new Error(`Test plan generation failed: ${res.status}`);
  }

  return res.json();
}

export async function getTestPlans(url: string): Promise<TestPlanResponse[]> {
  const res = await fetch(`http://localhost:8080/api/test-plans?url=${encodeURIComponent(url)}`);

  if (!res.ok) {
    throw new Error(`Failed to fetch test plans: ${res.status}`);
  }

  return res.json();
}

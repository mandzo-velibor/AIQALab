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

export async function generateTests(url: string): Promise<TestGenResponse> {
  const res = await fetch("http://localhost:8080/api/tests/generate", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url }),
  });

  if (!res.ok) {
    const error = await res.json().catch(() => ({ error: "Unknown error" }));
    throw new Error(error.message || error.error || `Test generation failed: ${res.status}`);
  }

  return res.json();
}

export async function getTests(url: string): Promise<GeneratedTestDto[]> {
  const res = await fetch(`http://localhost:8080/api/tests?url=${encodeURIComponent(url)}`);

  if (!res.ok) {
    throw new Error(`Failed to fetch tests: ${res.status}`);
  }

  return res.json();
}

export interface ExploreRequest {
  url: string;
}

export interface ExploreResponse {
  title: string;
  url: string;
  screenshotBase64: string;
  buttonCount: number;
  inputCount: number;
  linkCount: number;
  formCount: number;
  agentResults: Record<string, { success: boolean; message: string }>;
}

export async function exploreUrl(url: string): Promise<ExploreResponse> {
  const res = await fetch("http://localhost:8080/api/explore", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url } satisfies ExploreRequest),
  });

  if (!res.ok) {
    throw new Error(`Explore failed: ${res.status}`);
  }

  return res.json();
}

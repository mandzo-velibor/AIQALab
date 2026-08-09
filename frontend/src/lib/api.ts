import { API_BASE_URL } from "@/lib/config";
import { httpRequest } from "@/lib/http";
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
  const res = await httpRequest(`${API_BASE_URL}/api/explore`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url } satisfies ExploreRequest),
  });

  return res.json();
}

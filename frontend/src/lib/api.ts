import { API_BASE_URL } from "@/lib/config";
import { httpRequest } from "@/lib/http";
import { v1Project, type V1Project } from "@/lib/v1-project";

export interface ExploreRequest {
  project: V1Project;
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
  const res = await httpRequest(`${API_BASE_URL}/api/v1/explore`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ project: v1Project(), url } satisfies ExploreRequest),
  });

  return res.json();
}

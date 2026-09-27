import { v1Project, type V1Project } from "@/lib/v1-project";
import { API_BASE_URL } from "@/lib/config";
import { httpRequest } from "@/lib/http";
export interface DetectedForm {
  name: string;
  inputs: string[];
}

export interface DetectedNavigation {
  name: string;
  target: string;
}

export interface DetectedDialog {
  name: string;
  trigger: string;
}

export interface DetectedTable {
  name: string;
  columns: string[];
}

export interface DetectedFlow {
  name: string;
  description: string;
}

export interface RiskArea {
  name: string;
  reason: string;
}

export interface AnalysisResponse {
  pageType: string;
  summary: string;
  confidence: number;
  forms: DetectedForm[];
  buttons: string[];
  navigation: DetectedNavigation[];
  dialogs: DetectedDialog[];
  tables: DetectedTable[];
  possibleFlows: DetectedFlow[];
  riskAreas: RiskArea[];
  screenshotBase64: string;
}

export interface AnalyzeRequest {
  // v1 identifies the project by object, not by a flat numeric id, so cost and usage can
  // be attributed per project.
  project: V1Project;
  url: string;
  forceRefresh?: boolean;
  username?: string | null;
  password?: string | null;
}

export async function analyzeUrl(
  url: string,
  forceRefresh = false,
  projectId?: number,
  username?: string,
  password?: string,
): Promise<AnalysisResponse> {
  const res = await httpRequest(`${API_BASE_URL}/api/v1/analyze`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      project: v1Project(projectId),
      url,
      forceRefresh,
      username,
      password,
    } satisfies AnalyzeRequest),
  });

  return res.json();
}

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
  url: string;
  forceRefresh?: boolean;
}

export async function analyzeUrl(url: string, forceRefresh = false): Promise<AnalysisResponse> {
  const res = await fetch("http://localhost:8080/api/analyze", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url, forceRefresh } satisfies AnalyzeRequest),
  });

  if (!res.ok) {
    throw new Error(`Analysis failed: ${res.status}`);
  }

  return res.json();
}

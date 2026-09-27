import { v1Project, type V1Project } from "@/lib/v1-project";
import { API_BASE_URL } from "@/lib/config";
import { ApiError, httpRequest } from "@/lib/http";
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
  /**
   * Path to the captured screenshot, not its bytes.
   *
   * <p>This was `screenshotBase64` until B-028. A full-page PNG does not fit the
   * `analysis_json varchar(10000)` column it was being persisted into, and base64 inflates
   * the payload by a third besides, so the API returns a path. The type kept claiming the
   * old field, which is how a screenshot preview stayed silently blank for a while: the
   * value was always `undefined` and the card quietly took its "no screenshot" branch.
   *
   * <p>Base64 survives only on the explore response, where it is genuinely needed because
   * that is the one image the dashboard renders inline.
   */
  screenshotPath: string;
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

  const body = (await res.json()) as V1AnalyzeEnvelope;
  // The v1 surface wraps every response in an envelope and nests the payload under
  // `analysis`. Returning the envelope as though it were the analysis made every field
  // undefined, and the panel then threw on `result.forms.length` — so the whole analysis
  // view has been blank since the v1 migration. Unwrapped here so callers keep the shape
  // they actually want.
  if (!body?.analysis) {
    throw new ApiError(0, "MALFORMED_RESPONSE",
      "The analyse response contained no analysis payload.");
  }
  return body.analysis;
}

/**
 * What `/api/v1/analyze` actually returns: an envelope with the analysis nested inside.
 * Declared rather than inlined so the mismatch is visible where the call happens.
 */
export interface V1AnalyzeEnvelope {
  operationId: string;
  status: string;
  projectId: string;
  url: string;
  analysis: AnalysisResponse;
  createdAt: string;
}

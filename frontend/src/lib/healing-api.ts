import { v1Project } from "@/lib/v1-project";
import { API_BASE_URL } from "@/lib/config";
import { httpRequest } from "@/lib/http";
export interface FailureAnalysis {
  id: number;
  projectId: number;
  executionId: number;
  failureType: string;
  confidence: number | null;
  summary: string | null;
  affectedElement: string | null;
  healingCandidate: boolean | null;
  analysisJson: string | null;
  createdAt: string;
}

export interface HealingSuggestion {
  id: number;
  projectId: number;
  executionId: number;
  failureAnalysisId: number | null;
  elementName: string;
  oldLocator: string;
  newLocator: string;
  confidence: number | null;
  reason: string | null;
  status: string;
  approvedBy: string | null;
  approvedAt: string | null;
  createdAt: string;
}

export async function analyzeExecution(executionId: number, projectId: number): Promise<FailureAnalysis> {
  const res = await httpRequest(`${API_BASE_URL}/api/v1/failures/analyze`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    // v1 takes project identity and the execution in the body; the legacy call smuggled
    // both through a query string.
    body: JSON.stringify({ project: v1Project(projectId), executionId }),
  });

  return res.json();
}

export async function generateHealing(executionId: number): Promise<HealingSuggestion> {
  const res = await httpRequest(`${API_BASE_URL}/api/v1/healing/suggestions/analyze/${executionId}`, {
    method: "POST",
  });

  return res.json();
}

export async function approveSuggestion(id: number): Promise<HealingSuggestion> {
  const res = await httpRequest(`${API_BASE_URL}/api/v1/healing/suggestions/${id}/approve`, {
    method: "POST",
  });

  return res.json();
}

export async function rejectSuggestion(id: number): Promise<HealingSuggestion> {
  const res = await httpRequest(`${API_BASE_URL}/api/v1/healing/suggestions/${id}/reject`, {
    method: "POST",
  });

  return res.json();
}

export async function applySuggestion(id: number): Promise<HealingSuggestion> {
  const res = await httpRequest(`${API_BASE_URL}/api/v1/healing/suggestions/${id}/apply`, {
    method: "POST",
  });

  return res.json();
}

export async function getSuggestions(projectId?: number): Promise<HealingSuggestion[]> {
  const url = projectId
    ? `${API_BASE_URL}/api/v1/healing/suggestions?projectId=${projectId}`
    : `${API_BASE_URL}/api/v1/healing/suggestions`;

  const res = await httpRequest(url);
  return res.json();
}

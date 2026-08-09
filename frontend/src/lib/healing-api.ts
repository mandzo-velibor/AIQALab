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
  const res = await httpRequest(
    `${API_BASE_URL}/api/executions/${executionId}/analyze?projectId=${projectId}`,
    { method: "POST" }
  );

  return res.json();
}

export async function generateHealing(executionId: number): Promise<HealingSuggestion> {
  const res = await httpRequest(`${API_BASE_URL}/api/healing/analyze/${executionId}`, {
    method: "POST",
  });

  return res.json();
}

export async function approveSuggestion(id: number): Promise<HealingSuggestion> {
  const res = await httpRequest(`${API_BASE_URL}/api/healing/${id}/approve`, {
    method: "POST",
  });

  return res.json();
}

export async function rejectSuggestion(id: number): Promise<HealingSuggestion> {
  const res = await httpRequest(`${API_BASE_URL}/api/healing/${id}/reject`, {
    method: "POST",
  });

  return res.json();
}

export async function applySuggestion(id: number): Promise<HealingSuggestion> {
  const res = await httpRequest(`${API_BASE_URL}/api/healing/${id}/apply`, {
    method: "POST",
  });

  return res.json();
}

export async function getSuggestions(projectId?: number): Promise<HealingSuggestion[]> {
  const url = projectId
    ? `${API_BASE_URL}/api/healing/suggestions?projectId=${projectId}`
    : `${API_BASE_URL}/api/healing/suggestions`;

  const res = await httpRequest(url);
  return res.json();
}

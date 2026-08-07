import { API_BASE_URL } from "@/lib/config";
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
  const res = await fetch(
    `${API_BASE_URL}/api/executions/${executionId}/analyze?projectId=${projectId}`,
    { method: "POST" }
  );

  if (!res.ok) {
    const error = await res.json().catch(() => ({ error: "Unknown error" }));
    throw new Error(error.message || error.error || `Analysis failed: ${res.status}`);
  }

  return res.json();
}

export async function generateHealing(executionId: number): Promise<HealingSuggestion> {
  const res = await fetch(`${API_BASE_URL}/api/healing/analyze/${executionId}`, {
    method: "POST",
  });

  if (!res.ok) {
    const error = await res.json().catch(() => ({ error: "Unknown error" }));
    throw new Error(error.message || error.error || `Healing generation failed: ${res.status}`);
  }

  return res.json();
}

export async function approveSuggestion(id: number): Promise<HealingSuggestion> {
  const res = await fetch(`${API_BASE_URL}/api/healing/${id}/approve`, {
    method: "POST",
  });

  if (!res.ok) {
    const error = await res.json().catch(() => ({ error: "Unknown error" }));
    throw new Error(error.message || error.error || `Approval failed: ${res.status}`);
  }

  return res.json();
}

export async function rejectSuggestion(id: number): Promise<HealingSuggestion> {
  const res = await fetch(`${API_BASE_URL}/api/healing/${id}/reject`, {
    method: "POST",
  });

  if (!res.ok) {
    const error = await res.json().catch(() => ({ error: "Unknown error" }));
    throw new Error(error.message || error.error || `Rejection failed: ${res.status}`);
  }

  return res.json();
}

export async function applySuggestion(id: number): Promise<HealingSuggestion> {
  const res = await fetch(`${API_BASE_URL}/api/healing/${id}/apply`, {
    method: "POST",
  });

  if (!res.ok) {
    const error = await res.json().catch(() => ({ error: "Unknown error" }));
    throw new Error(error.message || error.error || `Apply failed: ${res.status}`);
  }

  return res.json();
}

export async function getSuggestions(projectId?: number): Promise<HealingSuggestion[]> {
  const url = projectId
    ? `${API_BASE_URL}/api/healing/suggestions?projectId=${projectId}`
    : `${API_BASE_URL}/api/healing/suggestions`;

  const res = await fetch(url);
  if (!res.ok) throw new Error(`Failed to fetch suggestions: ${res.status}`);
  return res.json();
}

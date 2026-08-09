import { API_BASE_URL } from "@/lib/config";
import { httpRequest } from "@/lib/http";
export interface LocatorDto {
  id: number;
  elementName: string;
  elementType: string;
  preferredLocator: string;
  fallbackLocators: string[];
  strategy: string;
  confidence: number;
  reason: string;
}

export interface LocatorResponse {
  generated: number;
  locators: LocatorDto[];
}

export async function generateLocators(url: string, projectId?: number): Promise<LocatorResponse> {
  const res = await httpRequest(`${API_BASE_URL}/api/locators/generate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url, projectId }),
  });

  return res.json();
}

export async function getLocators(url: string): Promise<LocatorDto[]> {
  const res = await httpRequest(`${API_BASE_URL}/api/locators?url=${encodeURIComponent(url)}`);

  return res.json();
}

export interface LocatorDiff {
  currentLocator: string;
  previousLocator: string;
  strategyChanged: boolean;
  currentStrategy: string;
  previousStrategy: string;
  semanticChanged: boolean;
  semanticSimilarity: number;
  targetLikelySame: boolean;
  targetSimilarity: number;
  recommendation: string;
}

export interface HistoricalObservation {
  id: number;
  projectId: number | null;
  pageUrl: string;
  locator: string;
  strategy: string;
  score: number;
  stabilityScore: number;
  semanticScore: number;
  uniqueness: number;
  health: string;
  status: string;
  observedAt: string;
}

export interface LocatorIntelligence {
  url: string;
  locator: string;
  strategy: string;
  stabilityScore: number;
  stabilityLevel: string;
  stabilityReasons: string[];
  semanticScore: number;
  semanticReason: string;
  uniqueness: number;
  uniquenessDetail: string;
  visible: boolean;
  enabled: boolean;
  matchedElementCount: number;
  maintainability: number;
  resilience: number;
  overallScore: number;
  health: string;
  healthReason: string;
  survivalRate: number;
  observedCount: number;
  elementFingerprint: string | null;
  comparison: LocatorDiff | null;
  history: HistoricalObservation[];
}

export interface LocatorAnalyzeResponse {
  operationId: string;
  status: string;
  projectId: string | null;
  url: string;
  intelligence: LocatorIntelligence;
  createdAt: string;
}

export async function analyzeLocator(url: string, locator: string, projectId?: number): Promise<LocatorAnalyzeResponse> {
  const res = await httpRequest(`${API_BASE_URL}/api/v1/locators/analyze`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url, locator, projectId: projectId ?? null }),
  });

  return res.json();
}

export async function getLocatorHistory(projectId: number): Promise<HistoricalObservation[]> {
  const res = await httpRequest(`${API_BASE_URL}/api/v1/projects/${projectId}/locators/history`);

  return res.json();
}

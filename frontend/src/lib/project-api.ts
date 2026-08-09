import { API_BASE_URL } from "@/lib/config";
import { httpRequest } from "@/lib/http";
export interface ProjectResponse {
  id: number;
  name: string;
  description: string | null;
  baseUrl: string;
  repositoryUrl: string;
  framework: string;
  workspacePath: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateProjectRequest {
  name: string;
  description?: string;
  baseUrl: string;
  repositoryUrl?: string;
  framework: string;
}

export async function getProjects(): Promise<ProjectResponse[]> {
  const res = await httpRequest(`${API_BASE_URL}/api/projects`);
  return res.json();
}

export async function getProject(id: number): Promise<ProjectResponse> {
  const res = await httpRequest(`${API_BASE_URL}/api/projects/${id}`);
  return res.json();
}

export async function createProject(request: CreateProjectRequest): Promise<ProjectResponse> {
  const res = await httpRequest(`${API_BASE_URL}/api/projects`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
  return res.json();
}

export interface ProjectHistoryResponse {
  executions: ExecutionEntry[];
  pageAnalyses: PageAnalysisEntry[];
  locatorHistory: LocatorHistoryEntry[];
  failureAnalyses: FailureAnalysisEntry[];
  healingSuggestions: HealingSuggestionEntry[];
}

export interface ExecutionEntry {
  id: number;
  testFile: string;
  status: string;
  duration: number | null;
  errorMessage: string | null;
  screenshotPath: string | null;
  consoleLogs: string | null;
  createdAt: string;
}

export interface PageAnalysisEntry {
  id: number;
  url: string;
  pageType: string | null;
  version: number;
  createdAt: string;
}

export interface LocatorHistoryEntry {
  id: number;
  elementName: string;
  locator: string;
  strategy: string | null;
  status: string;
  createdAt: string;
}

export interface FailureAnalysisEntry {
  id: number;
  executionId: number;
  failureType: string;
  summary: string | null;
  affectedElement: string | null;
  healingCandidate: boolean | null;
  createdAt: string;
}

export interface HealingSuggestionEntry {
  id: number;
  elementName: string;
  oldLocator: string;
  newLocator: string;
  status: string;
  confidence: number | null;
  createdAt: string;
}

export async function getProjectHistory(id: number): Promise<ProjectHistoryResponse> {
  const res = await httpRequest(`${API_BASE_URL}/api/projects/${id}/history`);
  return res.json();
}

export async function deleteProject(id: number): Promise<void> {
  await httpRequest(`${API_BASE_URL}/api/projects/${id}`, {
    method: "DELETE",
  });
}

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
  const res = await fetch("http://localhost:8080/api/projects");
  if (!res.ok) throw new Error(`Failed to fetch projects: ${res.status}`);
  return res.json();
}

export async function getProject(id: number): Promise<ProjectResponse> {
  const res = await fetch(`http://localhost:8080/api/projects/${id}`);
  if (!res.ok) throw new Error(`Failed to fetch project: ${res.status}`);
  return res.json();
}

export async function createProject(request: CreateProjectRequest): Promise<ProjectResponse> {
  const res = await fetch("http://localhost:8080/api/projects", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
  if (!res.ok) {
    const error = await res.json().catch(() => ({ error: "Unknown error" }));
    throw new Error(error.message || error.error || `Failed to create project: ${res.status}`);
  }
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
  const res = await fetch(`http://localhost:8080/api/projects/${id}/history`);
  if (!res.ok) throw new Error(`Failed to fetch project history: ${res.status}`);
  return res.json();
}

export async function deleteProject(id: number): Promise<void> {
  const res = await fetch(`http://localhost:8080/api/projects/${id}`, {
    method: "DELETE",
  });
  if (!res.ok) {
    const error = await res.json().catch(() => ({ error: "Unknown error" }));
    throw new Error(error.message || error.error || `Failed to delete project: ${res.status}`);
  }
}

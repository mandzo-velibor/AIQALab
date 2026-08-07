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

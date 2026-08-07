"use client";

import { useState, useEffect } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { getProjects, createProject, deleteProject, type ProjectResponse, type CreateProjectRequest } from "@/lib/project-api";
import Link from "next/link";

export function ProjectsPage() {
  const [projects, setProjects] = useState<ProjectResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [formData, setFormData] = useState<CreateProjectRequest>({
    name: "",
    description: "",
    baseUrl: "",
    repositoryUrl: "",
    framework: "PLAYWRIGHT_TYPESCRIPT",
  });

  const [deleting, setDeleting] = useState<number | null>(null);

  useEffect(() => {
    loadProjects();
  }, []);

  const loadProjects = async () => {
    try {
      const data = await getProjects();
      setProjects(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load projects");
    } finally {
      setLoading(false);
    }
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    try {
      await createProject(formData);
      setShowForm(false);
      setFormData({ name: "", description: "", baseUrl: "", repositoryUrl: "", framework: "PLAYWRIGHT_TYPESCRIPT" });
      await loadProjects();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to create project");
    }
  };

  const handleDelete = async (id: number) => {
    if (!window.confirm("Are you sure you want to delete this project? This removes all executions, analyses, locators and healing suggestions.")) {
      return;
    }
    setDeleting(id);
    setError(null);
    try {
      await deleteProject(id);
      await loadProjects();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to delete project");
    } finally {
      setDeleting(null);
    }
  };

  return (
    <div className="flex-1 space-y-6 p-6">
      <div className="flex items-center justify-between">
        <div>
          <div className="text-xs font-medium uppercase tracking-widest text-primary">
            Test Projects
          </div>
          <h1 className="text-3xl font-bold tracking-tight">
            My <span className="text-gradient">Projects</span>
          </h1>
        </div>
        <div className="flex gap-2">
          <Link href="/">
            <Button variant="outline">Dashboard</Button>
          </Link>
          <Button onClick={() => setShowForm(!showForm)}>
            {showForm ? "Cancel" : "New Project"}
          </Button>
        </div>
      </div>

      {error && (
        <Card className="border-red-500">
          <CardContent className="pt-6">
            <p className="text-red-500">{error}</p>
          </CardContent>
        </Card>
      )}

      {showForm && (
        <Card>
          <CardHeader>
            <CardTitle>Create New Project</CardTitle>
          </CardHeader>
          <CardContent>
            <form onSubmit={handleCreate} className="space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="text-sm font-medium">Project Name *</label>
                  <Input
                    value={formData.name}
                    onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                    placeholder="My Project"
                    required
                  />
                </div>
                <div>
                  <label className="text-sm font-medium">Base URL *</label>
                  <Input
                    type="url"
                    value={formData.baseUrl}
                    onChange={(e) => setFormData({ ...formData, baseUrl: e.target.value })}
                    placeholder="https://example.com"
                    required
                  />
                </div>
                <div>
                  <label className="text-sm font-medium">Framework *</label>
                  <select
                    value={formData.framework}
                    onChange={(e) => setFormData({ ...formData, framework: e.target.value })}
                    className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
                  >
                    <option value="PLAYWRIGHT_TYPESCRIPT">Playwright TypeScript</option>
                    <option value="PLAYWRIGHT_JAVASCRIPT">Playwright JavaScript</option>
                    <option value="CYPRESS">Cypress</option>
                  </select>
                </div>
                <div>
                  <label className="text-sm font-medium">Repository URL</label>
                  <Input
                    type="url"
                    value={formData.repositoryUrl}
                    onChange={(e) => setFormData({ ...formData, repositoryUrl: e.target.value })}
                    placeholder="https://github.com/user/repo"
                  />
                </div>
              </div>
              <div>
                <label className="text-sm font-medium">Description</label>
                <textarea
                  value={formData.description}
                  onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                  placeholder="Project description..."
                  className="flex min-h-[80px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
                />
              </div>
              <Button type="submit">Create Project</Button>
            </form>
          </CardContent>
        </Card>
      )}

      {loading ? (
        <Card>
          <CardContent className="pt-6">
            <p className="text-muted-foreground">Loading projects...</p>
          </CardContent>
        </Card>
      ) : projects.length === 0 ? (
        <Card>
          <CardContent className="pt-6">
            <p className="text-muted-foreground">No projects yet. Create your first project to get started.</p>
          </CardContent>
        </Card>
      ) : (
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          {projects.map((project) => (
            <Card key={project.id} className="hover:shadow-lg transition-shadow">
              <CardHeader>
                <CardTitle className="flex items-center justify-between">
                  <span>{project.name}</span>
                  <Badge variant="secondary">{project.framework}</Badge>
                </CardTitle>
              </CardHeader>
              <CardContent className="space-y-2">
                <div>
                  <p className="text-sm text-muted-foreground">Base URL</p>
                  <p className="text-sm font-medium truncate">{project.baseUrl}</p>
                </div>
                {project.description && (
                  <div>
                    <p className="text-sm text-muted-foreground">Description</p>
                    <p className="text-sm">{project.description}</p>
                  </div>
                )}
                <div className="pt-2 flex gap-2">
                  <Link href={`/projects/${project.id}`} className="flex-1">
                    <Button size="sm" className="w-full">Open Project</Button>
                  </Link>
                  <Button
                    size="sm"
                    variant="destructive"
                    disabled={deleting === project.id}
                    onClick={() => handleDelete(project.id)}
                  >
                    {deleting === project.id ? "Deleting..." : "Delete"}
                  </Button>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}

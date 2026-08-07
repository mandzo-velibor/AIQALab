import { ProjectDetail } from "@/components/project-detail";
import { getProject, getProjectHistory } from "@/lib/project-api";
import { getSuggestions } from "@/lib/healing-api";

export default async function ProjectPage(props: PageProps<"/projects/[id]">) {
  const { id } = await props.params;
  const projectId = Number(id);

  const [project, history, suggestions] = await Promise.all([
    getProject(projectId).catch(() => null),
    getProjectHistory(projectId).catch(() => null),
    getSuggestions(projectId).catch(() => []),
  ]);

  return (
    <ProjectDetail
      projectId={projectId}
      project={project}
      history={history}
      suggestions={suggestions}
    />
  );
}

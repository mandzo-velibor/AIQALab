/**
 * Builds the `project` object the v1 API requires on write requests.
 *
 * The v1 surface identifies a project by a logical string id and treats the numeric id as
 * an optional pointer back into the Core database. B-013 moved auth, budgets and usage
 * accounting onto project identity, so a request without one has nowhere to attribute cost
 * to — which is why the object is required rather than optional here.
 *
 * `baseUrl`, `framework` and `language` are omitted on purpose: the Core resolves them
 * from `databaseId` via `ProjectContextResolver.enrichFromDatabase`. Sending a stale
 * `baseUrl` from the browser would override the stored value and quietly point analysis at
 * the wrong host.
 */
export interface V1Project {
  projectId: string;
  baseUrl?: string | null;
  framework?: string | null;
  language?: string | null;
  databaseId?: number | null;
}

/** Used when the caller has no project in context (e.g. the URL-only explore box). */
const ANONYMOUS_PROJECT = "default";

export function v1Project(databaseId?: number | null): V1Project {
  return {
    projectId: databaseId != null ? String(databaseId) : ANONYMOUS_PROJECT,
    databaseId: databaseId ?? null,
  };
}

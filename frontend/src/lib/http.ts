/**
 * Structured API errors surfaced by the backend. The v1 boundary returns
 * <pre>{ "error": { "code", "message", "operationId" } }</pre>
 * so the UI can render an understandable explanation plus an expandable
 * technical section instead of a raw stack trace.
 */
export class ApiError extends Error {
  readonly code: string;
  readonly operationId?: string | null;
  readonly status: number;

  constructor(status: number, code: string, message: string, operationId?: string | null) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.operationId = operationId;
  }
}

interface ApiErrorBody {
  error?: {
    code?: string;
    message?: string;
    operationId?: string | null;
  };
}

/**
 * Fetch wrapper that converts non-2xx responses into a structured {@link ApiError}.
 * Keeps error handling identical across every API module.
 */
export async function httpRequest(url: string, init?: RequestInit): Promise<Response> {
  const res = await fetch(url, init);
  if (!res.ok) {
    throw await parseApiError(res);
  }
  return res;
}

export async function parseApiError(res: Response): Promise<ApiError> {
  let code = "HTTP_" + res.status;
  let message = `Request failed (HTTP ${res.status})`;
  let operationId: string | null = null;
  try {
    const body = (await res.json()) as ApiErrorBody;
    if (body?.error) {
      code = body.error.code ?? code;
      message = body.error.message ?? message;
      operationId = body.error.operationId ?? null;
    } else if (body && typeof body === "object") {
      const anyBody = body as Record<string, unknown>;
      if (typeof anyBody.message === "string") message = anyBody.message;
      if (typeof anyBody.error === "string") message = anyBody.error;
      if (typeof anyBody.operationId === "string") operationId = anyBody.operationId;
    }
  } catch {
    // Non-JSON body; fall back to status-based message.
  }
  return new ApiError(res.status, code, message, operationId);
}

export function toApiError(err: unknown, fallback: string): ApiError {
  if (err instanceof ApiError) return err;
  return new ApiError(0, "UNKNOWN", err instanceof Error ? err.message : fallback);
}

/**
 * A short, user-facing label for an error code. Keeps the normal UI readable
 * to a QA user while the raw code stays available in the expandable details.
 */
export function friendlyErrorLabel(code: string): string {
  switch (code) {
    case "AI_BUDGET_EXCEEDED":
      return "AI token budget exceeded";
    case "AI_PROVIDER_UNAVAILABLE":
      return "AI provider unavailable";
    case "AI_PROVIDER_NOT_CONFIGURED":
      return "AI provider not configured";
    case "AI_CREDENTIAL_INVALID":
      return "AI credentials invalid";
    case "AI_RATE_LIMITED":
      return "AI provider rate limit reached";
    case "AI_OPERATION_NOT_ALLOWED":
      return "AI operation not allowed";
    case "INVALID_REQUEST":
      return "Invalid request";
    case "INVALID_PROJECT_CONTEXT":
      return "Missing project context";
    case "PROJECT_NOT_FOUND":
      return "Project not found";
    case "INTERNAL_ERROR":
      return "Internal error";
    default:
      return "Request failed";
  }
}

export function isAiFallbackError(code: string): boolean {
  return (
    code === "AI_BUDGET_EXCEEDED" ||
    code === "AI_PROVIDER_UNAVAILABLE" ||
    code === "AI_PROVIDER_NOT_CONFIGURED" ||
    code === "AI_CREDENTIAL_INVALID" ||
    code === "AI_RATE_LIMITED" ||
    code === "AI_OPERATION_NOT_ALLOWED"
  );
}

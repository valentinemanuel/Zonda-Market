export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly traceId: string | null,
    readonly code: string | null = null,
  ) {
    super(`La API respondió con HTTP ${status}`);
    this.name = "ApiError";
  }
}

export async function getJson(path: string, signal: AbortSignal): Promise<unknown> {
  return (await request(path, { signal })).json();
}

export async function request(path: string, init: RequestInit = {}, timeoutMs = 8_000): Promise<Response> {
  const timeout = AbortSignal.timeout(timeoutMs);
  const headers = new Headers(init.headers);
  if (!headers.has("Accept")) headers.set("Accept", "application/json, application/problem+json");
  const response = await fetch(path, {
    ...init,
    signal: init.signal ? AbortSignal.any([init.signal, timeout]) : timeout,
    headers,
  });
  if (response.ok) {
    return response;
  }

  const problem: unknown = response.headers.get("Content-Type")?.includes("application/problem+json")
    ? await response.json().catch(() => null)
    : null;
  const traceId = problem && typeof problem === "object" && "traceId" in problem && typeof problem.traceId === "string"
    ? problem.traceId
    : response.headers.get("X-Trace-Id");
  const code = problem && typeof problem === "object" && "code" in problem && typeof problem.code === "string"
    ? problem.code
    : null;
  throw new ApiError(response.status, traceId, code);
}

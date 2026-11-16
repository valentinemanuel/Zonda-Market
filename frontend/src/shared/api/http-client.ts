export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly traceId: string | null,
  ) {
    super(`La API respondió con HTTP ${status}`);
    this.name = "ApiError";
  }
}

export async function getJson(path: string, signal: AbortSignal): Promise<unknown> {
  const response = await fetch(path, {
    signal: AbortSignal.any([signal, AbortSignal.timeout(8_000)]),
    headers: { Accept: "application/json, application/problem+json" },
  });
  if (response.ok) {
    return response.json();
  }

  const problem: unknown = response.headers.get("Content-Type")?.includes("application/problem+json")
    ? await response.json().catch(() => null)
    : null;
  const traceId = problem && typeof problem === "object" && "traceId" in problem && typeof problem.traceId === "string"
    ? problem.traceId
    : response.headers.get("X-Trace-Id");
  throw new ApiError(response.status, traceId);
}

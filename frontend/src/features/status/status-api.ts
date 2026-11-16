type StatusResponse = {
  status: "UP";
  version: string;
};

export type Snapshot = {
  api: StatusResponse | null;
  readiness: "ready" | "down" | "unknown";
};

async function getApiStatus(signal: AbortSignal): Promise<StatusResponse> {
  const response = await fetch("/api/v1/status", { signal, cache: "no-store" });
  if (!response.ok) {
    throw new Error("No se pudo consultar la API");
  }

  const payload: unknown = await response.json();
  if (
    typeof payload !== "object" || payload === null ||
    !("status" in payload) || payload.status !== "UP" ||
    !("version" in payload) || typeof payload.version !== "string" || !payload.version
  ) {
    throw new Error("La API devolvió un estado inesperado");
  }

  return { status: payload.status, version: payload.version };
}

async function getReadiness(signal: AbortSignal): Promise<Snapshot["readiness"]> {
  const response = await fetch("/actuator/health/readiness", { signal, cache: "no-store" });
  const payload: unknown = await response.json();
  if (typeof payload === "object" && payload !== null && "status" in payload) {
    if (response.ok && payload.status === "UP") return "ready";
    if (response.status === 503 && (payload.status === "DOWN" || payload.status === "OUT_OF_SERVICE")) return "down";
  }
  return "unknown";
}

export async function fetchStatus(querySignal: AbortSignal): Promise<Snapshot> {
  const signal = AbortSignal.any([querySignal, AbortSignal.timeout(8_000)]);
  const [api, readiness] = await Promise.allSettled([
    getApiStatus(signal),
    getReadiness(signal),
  ]);
  return {
    api: api.status === "fulfilled" ? api.value : null,
    readiness: readiness.status === "fulfilled" ? readiness.value : "unknown",
  };
}

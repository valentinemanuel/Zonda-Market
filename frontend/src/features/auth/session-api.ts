import { ApiError, request } from "@/shared/api/http-client";

type Credentials = { email: string; password: string };
type Access = { accessToken: string; expiresIn: number; roles: string[] };
export type Account = { id: string; email: string; emailVerified: boolean; roles: string[]; displayName: string | null };

let accessToken: string | null = null;
let pendingRefresh: Promise<string> | undefined;
let sessionRevision = 0;
const listeners = new Set<() => void>();
const updates = new BroadcastChannel("zonda-session");

function sessionChanged(token: string | null) {
  accessToken = token;
  sessionRevision++;
  listeners.forEach((listener) => listener());
}

updates.onmessage = () => sessionChanged(null);

export function subscribeToSession(listener: () => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

export function getSessionRevision() {
  return sessionRevision;
}

function cookieCommand(action: string, body?: Credentials | { code: string }) {
  return request(`/api/v1/auth/${action}`, {
    method: "POST",
    headers: {
      "X-Zonda-CSRF": "1",
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
}

export function authenticate(action: "login" | "register", body: Credentials) {
  return establishSession(action, body);
}

export function exchangeGoogleCode(code: string) {
  return establishSession("oauth/exchange", { code });
}

function establishSession(action: string, body: Credentials | { code: string }) {
  return navigator.locks.request("zonda-session", async () => {
    const session: Access = await (await cookieCommand(action, body)).json();
    sessionChanged(session.accessToken);
    updates.postMessage("changed");
  });
}

export function logout() {
  return navigator.locks.request("zonda-session", async () => {
    await cookieCommand("logout");
    sessionChanged(null);
    updates.postMessage("changed");
  });
}

function checkRevision(revision: number) {
  if (revision !== sessionRevision) {
    throw new DOMException("La sesión cambió durante la solicitud.", "AbortError");
  }
}

function refresh(rejectedToken: string | null, revision: number): Promise<string> {
  checkRevision(revision);
  if (accessToken && accessToken !== rejectedToken) {
    return Promise.resolve(accessToken);
  }
  if (pendingRefresh) {
    return pendingRefresh;
  }
  pendingRefresh = navigator.locks.request("zonda-session", async () => {
    checkRevision(revision);
    if (accessToken && accessToken !== rejectedToken) {
      return accessToken;
    }
    try {
      const session: Access = await (await cookieCommand("refresh")).json();
      checkRevision(revision);
      accessToken = session.accessToken;
      return accessToken;
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        accessToken = null;
      }
      throw error;
    }
  }).finally(() => { pendingRefresh = undefined; });
  return pendingRefresh;
}

export function renewSession() {
  return refresh(accessToken, sessionRevision);
}

export async function authenticatedRequest(path: string, init: RequestInit = {}): Promise<Response> {
  if (!path.startsWith("/api/v1/")) {
    throw new TypeError("La solicitud autenticada debe usar una ruta local de la API.");
  }
  init.signal?.throwIfAborted();
  const revision = sessionRevision;
  const token = accessToken ?? await refresh(null, revision);
  const send = (bearer: string) => {
    init.signal?.throwIfAborted();
    checkRevision(revision);
    const headers = new Headers(init.headers);
    headers.set("Authorization", `Bearer ${bearer}`);
    return request(path, { ...init, headers });
  };
  try {
    return await send(token);
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401 || error.code === "INVALID_CREDENTIALS") {
      throw error;
    }
    init.signal?.throwIfAborted();
    return send(await refresh(token, revision));
  }
}

export async function currentAccount(signal: AbortSignal): Promise<Account> {
  return (await authenticatedRequest("/api/v1/me", { signal })).json();
}

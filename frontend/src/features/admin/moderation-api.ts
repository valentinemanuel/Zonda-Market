import { authenticatedRequest } from "@/features/auth/session-api";

export type Resource = "listings" | "users";
export type Action = "HIDE" | "RESTORE" | "SUSPEND" | "REACTIVATE";

type Target = { id: string; version: number };
export type Listing = Target & {
  title: string; slug: string; sellerEmail: string; status: string; hidden: boolean; visible: boolean; sellerSuspended: boolean;
};
export type Account = Target & { email: string; displayName: string | null; role: string; suspended: boolean };
export type Row = Listing | Account;
export type Audit = {
  actorId: string; actorEmail: string; action: Action; reason: string; beforeState: string;
  afterState: string; targetVersion: number; traceId: string; createdAt: string;
};
export type OrderCase = {
  orderId: string; subOrderId: string; orderStatus: string; subOrderStatus: string; sellerName: string; createdAt: string;
  requiresFollowUp: boolean;
};
export type Page<T> = { items: T[]; page: number; hasNext: boolean };

const base = "/api/v1/admin";

export async function listTargets(resource: Resource, q: string, state: string, page: number, signal: AbortSignal): Promise<Page<Row>> {
  const search = new URLSearchParams({ q, state, page: String(page) });
  return (await authenticatedRequest(`${base}/${resource}?${search}`, { signal })).json();
}

export async function targetDetails<T>(resource: Resource, id: string, section: "history" | "cases", page: number,
  signal: AbortSignal): Promise<Page<T>> {
  return (await authenticatedRequest(`${base}/${resource}/${id}/${section}?page=${page}`, { signal })).json();
}

export async function moderate(resource: Resource, row: Row, action: Action, reason: string): Promise<Audit> {
  const operation = resource === "listings" ? "moderation" : "status";
  return (await authenticatedRequest(`${base}/${resource}/${row.id}/${operation}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ action, reason, expectedVersion: row.version }),
  })).json();
}

import { authenticatedRequest } from "@/features/auth/session-api";

export const auditPath = "/admin/auditoria";
export const incidentsPath = "/admin/incidencias";
export const auditQueryKey = (revision: number) => ["private", "admin-audit", revision];
export const incidentsQueryKey = (revision: number) => ["private", "admin-payment-incidents", revision];

export type Page<T> = { items: T[]; page: number; hasNext: boolean };
export type AuditEvent = {
  id: string;
  actorId: string | null;
  role: string;
  action: string;
  resourceType: string;
  resourceId: string;
  orderId: string | null;
  disputeId: string | null;
  timestamp: string;
  traceId: string;
  result: string;
  diff: Record<string, string | number>;
};
export type PaymentIncident = {
  id: string;
  kind: "WEBHOOK" | "COMPENSATION" | "DISPUTE_REFUND";
  status: string;
  orderId: string | null;
  disputeId: string | null;
  traceId: string;
  attempts: number;
  updatedAt: string;
  canReconcile: boolean;
  reconcileAvailableAt: string | null;
};
export type Recovery = { id: string; traceId: string };

export async function fetchAudit(filters: URLSearchParams, signal: AbortSignal): Promise<Page<AuditEvent>> {
  return (await authenticatedRequest(`/api/v1/admin/audit-logs?${filters}`, { signal })).json();
}

export async function fetchIncidents(page: number, signal: AbortSignal): Promise<Page<PaymentIncident>> {
  return (await authenticatedRequest(`/api/v1/admin/payment-incidents?page=${page}`, { signal })).json();
}

export async function recoverIncident(id: string, reason: string, key: string): Promise<Recovery> {
  return (await authenticatedRequest(`/api/v1/admin/payment-incidents/${id}/reconcile`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "Idempotency-Key": key },
    body: JSON.stringify({ reason }),
  })).json();
}

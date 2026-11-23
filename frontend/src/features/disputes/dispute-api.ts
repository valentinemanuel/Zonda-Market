import { authenticatedRequest } from "@/features/auth/session-api";
import { ApiError } from "@/shared/api/http-client";

export const disputeStatuses = {
  ABIERTA: "Abierto",
  EN_MEDIACION: "En mediación",
  REEMBOLSO_PENDIENTE: "Reembolso pendiente",
  CERRADA_SIN_REEMBOLSO: "Cerrado sin reembolso",
  CERRADA_REEMBOLSADA: "Cerrado con reembolso",
} as const;

export type DisputeStatus = keyof typeof disputeStatuses;
type Participant = "BUYER" | "SELLER" | "ADMIN";
export type Reference = { id: string; status: DisputeStatus };
export type DisputeSummary = Reference & { subOrderId: string; reason: string; openedAt: string; assignedAt: string | null };
type Message = { id: string; authorRole: Participant; body: string; createdAt: string };
export type ResolutionOutcome = "SIN_REEMBOLSO" | "REEMBOLSO";
export type ResolutionRequest = { outcome: ResolutionOutcome; amount: string | null; reason: string; expectedVersion: number };
export type Resolution = {
  id: string; outcome: ResolutionOutcome; status: "CERRADA" | "PENDIENTE" | "EN_REVISION" | "CONFIRMADA" | "RECHAZADA";
  amount: string | null; reason: string; providerRefundId: string | null; simulated: boolean; updatedAt: string;
};
export type ResolutionBalance = { subOrderAvailable: string; paymentAvailable: string; available: string; currency: "ARS" };
export type DisputeDetail = DisputeSummary & {
  orderId: string; description: string; viewer: Participant; messages: Message[]; page: number; hasNext: boolean;
  version: number; resolution: Resolution | null;
};
type DisputeQueue = { content: DisputeSummary[]; hasNext: boolean };

export const disputePath = (id: string) => "/reclamos/" + id;
const apiPath = (id: string) => "/api/v1/disputes/" + id;
export const queuePath = "/api/v1/admin/disputes";
export const disputeKey = (revision: number, id: string) => ["private", "dispute", revision, id] as const;
export const disputeQueueKey = (revision: number) => ["private", "dispute-queue", revision] as const;
export const disputeOpenPath = (subOrder: string) => `/api/v1/me/sub-orders/${subOrder}/disputes`;
export const disputeMessagePath = (id: string) => apiPath(id) + "/messages";

export function disputeActive(status: DisputeStatus | undefined) {
  return status === "ABIERTA" || status === "EN_MEDIACION" || status === "REEMBOLSO_PENDIENTE";
}

export async function openDispute(subOrder: string, reason: string, description: string, expectedVersion: number): Promise<Reference> {
  return (await authenticatedRequest(disputeOpenPath(subOrder), {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ reason, description, expectedVersion }),
  })).json();
}

export async function disputeDetail(id: string, page: number, signal: AbortSignal): Promise<DisputeDetail> {
  return (await authenticatedRequest(`${apiPath(id)}?page=${page}`, { signal })).json();
}

export async function postDisputeMessage(id: string, body: string, key: string): Promise<Message> {
  return (await authenticatedRequest(disputeMessagePath(id), {
    method: "POST", headers: { "Content-Type": "application/json", "Idempotency-Key": key },
    body: JSON.stringify({ body }),
  })).json();
}

export async function fetchDisputeQueue(status: string, age: string, page: number, signal: AbortSignal): Promise<DisputeQueue> {
  const params = new URLSearchParams({ page: String(page) });
  if (status) params.set("status", status);
  if (age) params.set("maxAgeDays", age);
  return (await authenticatedRequest(`${queuePath}?${params}`, { signal })).json();
}

export async function assignDispute(id: string): Promise<Reference> {
  return (await authenticatedRequest(`${queuePath}/${id}/assign`, { method: "POST" })).json();
}

export const resolutionPath = (id: string) => `${queuePath}/${id}/resolve`;

export async function resolutionBalance(id: string, signal: AbortSignal): Promise<ResolutionBalance> {
  return (await authenticatedRequest(`${queuePath}/${id}/resolution-balance`, { signal })).json();
}

export async function resolveDispute(id: string, request: ResolutionRequest, key: string): Promise<Resolution> {
  return (await authenticatedRequest(resolutionPath(id), {
    method: "POST", headers: { "Content-Type": "application/json", "Idempotency-Key": key },
    body: JSON.stringify(request),
  })).json();
}

const errors: Record<string, string> = {
  DISPUTE_INELIGIBLE: "El estado o el plazo de esta suborden ya no permite abrir un reclamo.",
  DISPUTE_ALREADY_EXISTS: "Esta suborden ya tiene un reclamo.",
  DISPUTE_ASSIGNMENT_CONFLICT: "El reclamo cambió o ya tiene un mediador.",
  DISPUTE_VERSION_CONFLICT: "El reclamo cambió. Actualizá el estado antes de resolver.",
  DISPUTE_RESOLUTION_CONFLICT: "El reclamo debe estar en mediación para resolverlo.",
  REFUND_BALANCE_EXCEEDED: "El monto supera el saldo disponible. Volvé a consultar el saldo.",
  REFUND_CAPTURE_UNAVAILABLE: "El cobro ganador no está disponible para devoluciones.",
  DISPUTE_CLOSED: "El reclamo está cerrado.",
  SUB_ORDER_VERSION_CONFLICT: "La suborden cambió. Actualizá el pedido para continuar.",
  INVALID_REQUEST: "Revisá los campos y completá los datos obligatorios.",
};

export function disputeError(error: unknown, fallback: string) {
  return error instanceof ApiError && error.code ? errors[error.code] ?? fallback : fallback;
}

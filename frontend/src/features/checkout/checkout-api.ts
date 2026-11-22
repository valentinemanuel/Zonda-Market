import { authenticatedRequest } from "@/features/auth/session-api";
import type { Address } from "@/features/auth/profile-api";
import { ApiError } from "@/shared/api/http-client";

type Timed = { expiresAt: string; serverNow: string };
type Received<T> = T & { receivedAt: number };

export type Group = {
  sellerId: string;
  sellerName: string;
  items: { skuId: string; title: string; attributes: Record<string, string>; quantity: number; subtotal: string }[];
  merchandise: string;
  shipping: string;
  subtotal: string;
};

export type Quote = Received<Timed & {
  id: string; cartVersion: number; address: Address; groups: Group[]; total: string;
}>;

export type Order = Received<Timed & {
  id: string; status: string; subOrders: { id: string; status: string; address: Address; group: Group }[]; total: string;
  testPayment: { reference: string; result: PaymentResult | null } | null;
}>;

export type PaymentOutcome = "APPROVED" | "REJECTED";
type PaymentResult = PaymentOutcome | "LATE_APPROVAL" | "EXPIRED";

export type PaymentMethod = "CARD" | "CHECKOUT_PRO";
export type VerifiedAttemptOutcome = "PENDIENTE" | "APROBADO" | "RECHAZADO";
export type PaymentAttempt = {
  id: string; method: PaymentMethod; expiresAt: string;
  provider: "LOCAL" | "MERCADO_PAGO";
  status: VerifiedAttemptOutcome | "CREADO" | "ENVIADO" | "RESULTADO_DESCONOCIDO";
  nextAction: "INGRESAR_TARJETA" | "CONSULTAR" | "ELEGIR_MEDIO" | "NINGUNA";
};
export type CardConfig = { publicKey: string; amount: string; payerEmail: string };
export type PaymentOptions = { methods: PaymentMethod[]; latestAttempt: PaymentAttempt | null; card: CardConfig | null };
export type CardData = {
  token: string;
  paymentMethodId: string;
  paymentType: "credit_card" | "debit_card";
  installments: number;
  payer: { email: string; identification?: { type?: string; number?: string } };
};

export const activeOrderKey = (revision: number) => ["private", "active-order", revision] as const;
export const orderKey = (revision: number, id: string) => ["private", "order", revision, id] as const;
export const ordersKey = (revision: number) => ["private", "orders", revision] as const;
export const paymentOptionsKey = (revision: number, id: string) => ["private", "payment-options", revision, id] as const;
export const reservationPath = (id: string) => "/checkout/reserva/" + encodeURIComponent(id);

export type OrderPage = {
  content: { id: string; createdAt: string; expiresAt: string; status: string; total: string; currency: "ARS" }[];
  page: number;
  size: number;
  hasNext: boolean;
};

export function orderStatus(status: string) {
  switch (status) {
    case "RESERVADA": return "Reservada";
    case "PAGO_PENDIENTE": return "Pago pendiente";
    case "EXPIRADA": return "Vencida";
    case "PAGADA": return "Pagada";
    case "FALLIDA": return "Pago rechazado";
    default: return status.replaceAll("_", " ").toLowerCase();
  }
}

export async function fetchOrders(page: number, signal: AbortSignal): Promise<OrderPage> {
  return (await authenticatedRequest("/api/v1/orders?page=" + page, { signal })).json();
}

async function timed<T>(response: Response): Promise<T & { receivedAt: number }> {
  const receivedAt = performance.now();
  return { ...await response.json(), receivedAt };
}

function post(body: object, key?: string): RequestInit {
  return {
    method: "POST",
    headers: { "Content-Type": "application/json", ...(key ? { "Idempotency-Key": key } : {}) },
    body: JSON.stringify(body),
  };
}

export async function quoteCheckout(addressId: string, cartVersion: number): Promise<Quote> {
  return timed(await authenticatedRequest("/api/v1/checkout/quote", post({ addressId, cartVersion })));
}

export async function confirmCheckout(quoteId: string): Promise<Order> {
  return timed(await authenticatedRequest("/api/v1/checkout/confirm", post({ quoteId }, quoteId)));
}

export async function activeOrder(signal: AbortSignal): Promise<Order | null> {
  const response = await authenticatedRequest("/api/v1/orders/active", { signal });
  return response.status === 204 ? null : timed(response);
}

export async function fetchOrder(id: string, signal: AbortSignal): Promise<Order> {
  return timed(await authenticatedRequest("/api/v1/orders/" + encodeURIComponent(id), { signal }));
}

export async function simulatePayment(id: string, reference: string, outcome: PaymentOutcome): Promise<Order> {
  return timed(await authenticatedRequest("/api/v1/test-fixtures/orders/" + encodeURIComponent(id) + "/payment-outcome",
    post({ reference, outcome })));
}

export async function fetchPaymentOptions(id: string, signal: AbortSignal): Promise<PaymentOptions> {
  return (await authenticatedRequest("/api/v1/orders/" + encodeURIComponent(id) + "/payment-options", { signal })).json();
}

export async function startPayment(id: string, method: PaymentMethod, key: string): Promise<PaymentAttempt> {
  return (await authenticatedRequest("/api/v1/orders/" + encodeURIComponent(id) + "/payments", post({ method }, key))).json();
}

export async function submitPayment(id: string, attemptId: string): Promise<PaymentAttempt> {
  return (await authenticatedRequest("/api/v1/orders/" + encodeURIComponent(id) + "/payments/" + encodeURIComponent(attemptId)
    + "/submit", { method: "POST" })).json();
}

export async function submitCardPayment(id: string, attemptId: string, card: CardData): Promise<PaymentAttempt> {
  return (await authenticatedRequest("/api/v1/orders/" + encodeURIComponent(id) + "/payments/card",
    post({ attemptId, ...card }, attemptId))).json();
}

export async function reconcileCardPayment(id: string, attemptId: string): Promise<PaymentAttempt> {
  return (await authenticatedRequest("/api/v1/orders/" + encodeURIComponent(id) + "/payments/" + encodeURIComponent(attemptId)
    + "/reconcile-card", { method: "POST" })).json();
}

export async function simulateAttemptOutcome(id: string, attemptId: string,
  outcome: VerifiedAttemptOutcome): Promise<PaymentAttempt> {
  return (await authenticatedRequest("/api/v1/test-fixtures/orders/" + encodeURIComponent(id) + "/payments/"
    + encodeURIComponent(attemptId) + "/outcome", post({ outcome }))).json();
}

export function checkoutError(error: unknown) {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "ADDRESS_NOT_FOUND": return "La dirección cambió. Elegí una dirección vigente y volvé a cotizar.";
      case "CART_VERSION_CONFLICT": return "El carrito cambió. Revisalo y volvé a cotizar.";
      case "QUOTE_CHANGED": return "Cambió el precio o la dirección. Revisá la nueva cotización.";
      case "QUOTE_EXPIRED": return "La cotización venció. Volvé a cotizar.";
      case "STOCK_CHANGED": return "Cambió la disponibilidad. Revisá el carrito antes de continuar.";
      case "CART_EMPTY": return "Agregá productos al carrito para cotizar.";
      case "ORDER_ALREADY_ACTIVE": return "Ya tenés una reserva para esta cuenta.";
      case "CHECKOUT_EXPIRED": return "Tu reserva anterior venció.";
      case "QUOTE_ALREADY_CONFIRMED": return "Esta cotización ya generó una orden.";
      case "EMAIL_NOT_VERIFIED": return "Verificá tu correo desde Mi cuenta.";
      case "CHECKOUT_TOTAL_LIMIT": return "El importe supera el máximo de una orden. Revisá el carrito.";
    }
  }
  return "No pudimos completar la solicitud. Volvé a intentar.";
}

export function remainingMs(value: Received<Timed>) {
  return Date.parse(value.expiresAt) - Date.parse(value.serverNow) - (performance.now() - value.receivedAt);
}

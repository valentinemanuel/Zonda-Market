import { authenticatedRequest } from "@/features/auth/session-api";
import type { DeliveryAddress } from "@/features/auth/profile-api";
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
  id: string; cartVersion: number; address: DeliveryAddress; groups: Group[]; total: string;
  thumbnails?: Record<string, string>;
}>;

export type DirectPreview = { groups: Group[]; thumbnails: Record<string, string> };

export type Refund = {
  id: string; reason: "TARDIO" | "DUPLICADO"; status: "PENDIENTE" | "CONFIRMADA" | "EN_REVISION"; amount: string;
};

export type Order = Received<Timed & {
  id: string; status: string; subOrders: SubOrder[]; total: string;
  testPayment: { reference: string; result: PaymentResult | null } | null;
  refunds?: Refund[];
  refundedAmount?: string;
  directSkuId?: string | null;
  thumbnails?: Record<string, string>;
  payment?: { method: PaymentMethod | null; lastFour: string | null; simulated: boolean } | null;
}>;

export type Carrier = "ANDREANI" | "CORREO_ARGENTINO";
export type ShipmentResponse = {
  id: string; status: string; version: number;
  shipment: { carrier: Carrier; trackingCode: string; trackingUrl: string; shippedAt: string; deliveredAt: string | null } | null;
};
export type SubOrder = ShipmentResponse & { address: DeliveryAddress; group: Group; reviewed: boolean | null;
  dispute: import("@/features/disputes/dispute-api").Reference | null };
export type SellerOrderStatus = "PREPARANDO" | "DESPACHADA" | "ENTREGADA" | "REEMBOLSADA";
type SellerOrderPage = { content: SubOrder[]; page: number; size: number; hasNext: boolean };

export type PaymentOutcome = "APPROVED" | "REJECTED";
type PaymentResult = PaymentOutcome | "LATE_APPROVAL" | "EXPIRED";

export type PaymentMethod = "CARD" | "CHECKOUT_PRO";
export type VerifiedAttemptOutcome = "PENDIENTE" | "APROBADO" | "RECHAZADO";
export type PaymentAttempt = {
  id: string; method: PaymentMethod; expiresAt: string;
  provider: "LOCAL" | "MERCADO_PAGO";
  status: VerifiedAttemptOutcome | "CREADO" | "ENVIADO" | "RESULTADO_DESCONOCIDO";
  nextAction: "INGRESAR_TARJETA" | "ABRIR_CHECKOUT_PRO" | "CONSULTAR" | "ELEGIR_MEDIO" | "NINGUNA";
  checkoutUrl?: string | null;
};
export type CardConfig = { publicKey: string; amount: string; payerEmail: string };
export type PaymentOptions = {
  methods: PaymentMethod[]; latestAttempt: PaymentAttempt | null; card: CardConfig | null; checkoutPro?: boolean;
};
export type CardData = {
  token: string;
  paymentMethodId: string;
  paymentType: "credit_card" | "debit_card";
  installments: number;
  lastFour?: string;
  payer: { email: string; identification?: { type?: string; number?: string } };
};

export const activeOrderKey = (revision: number) => ["private", "active-order", revision] as const;
export const orderKey = (revision: number, id: string) => ["private", "order", revision, id] as const;
export const ordersKey = (revision: number) => ["private", "orders", revision] as const;
export const sellerOrdersKey = (revision: number) => ["private", "seller-orders", revision] as const;
export const paymentOptionsKey = (revision: number, id: string) => ["private", "payment-options", revision, id] as const;
export const reservationPath = (id: string) => "/checkout/reserva/" + encodeURIComponent(id);
export const checkoutPath = (skuId?: string | null) => skuId ? "/checkout?sku=" + encodeURIComponent(skuId) : "/checkout";

export type OrderHistoryFilter = "PAGADAS" | "PREPARANDO" | "ENTREGADAS";

export type OrderSummary = {
  id: string; createdAt: string; expiresAt: string; status: string; total: string; currency: "ARS";
  previews: { title: string; thumbnailUrl: string | null }[];
  lineCount: number;
  units: number;
  fulfillment: "PREPARANDO" | "DESPACHADA" | "ENTREGADA" | null;
};

export type OrderPage = {
  content: OrderSummary[];
  page: number;
  size: number;
  hasNext: boolean;
};

export function orderStatus(status: string) {
  switch (status) {
    case "RESERVADA": return "Reservada";
    case "PAGO_PENDIENTE": return "Pago pendiente";
    case "EXPIRADA": return "Vencida";
    case "CANCELADA": return "Reserva cancelada";
    case "PAGADA": return "Pagada";
    case "REEMBOLSO_PARCIAL": return "Con devolución parcial";
    case "REEMBOLSADA": return "Reembolsada";
    case "FALLIDA": return "Pago rechazado";
    default: return status.replaceAll("_", " ").toLowerCase();
  }
}

export async function fetchOrders(page: number, term: string, filter: OrderHistoryFilter | "", signal: AbortSignal): Promise<OrderPage> {
  const params = new URLSearchParams({ page: String(page) });
  if (term) params.set("q", term);
  if (filter) params.set("filter", filter);
  return (await authenticatedRequest("/api/v1/orders?" + params, { signal })).json();
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

export async function fetchDirectPreview(skuId: string, signal: AbortSignal): Promise<DirectPreview> {
  return (await authenticatedRequest("/api/v1/checkout/items/" + encodeURIComponent(skuId), { signal })).json();
}

export async function quoteCheckout(addressId: string, cartVersion: number, directSkuId?: string): Promise<Quote> {
  return timed(await authenticatedRequest("/api/v1/checkout/quote", post({ addressId, cartVersion, directSkuId })));
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

export async function cancelReservation(id: string): Promise<void> {
  await authenticatedRequest("/api/v1/orders/" + encodeURIComponent(id) + "/cancel-reservation", { method: "POST" });
}

export async function fetchSellerOrders(page: number, status: SellerOrderStatus | "", subOrderId: string | null,
  signal: AbortSignal): Promise<SellerOrderPage> {
  const params = new URLSearchParams({ page: String(page) });
  if (status) params.set("status", status);
  if (subOrderId) params.set("subOrderId", subOrderId);
  return (await authenticatedRequest("/api/v1/seller/orders?" + params, { signal })).json();
}

export async function shipSubOrder(id: string, carrier: Carrier, trackingCode: string,
  expectedVersion: number): Promise<ShipmentResponse> {
  return (await authenticatedRequest("/api/v1/seller/sub-orders/" + encodeURIComponent(id) + "/ship",
    post({ carrier, trackingCode, expectedVersion }))).json();
}

export async function confirmDelivery(id: string, expectedVersion: number): Promise<ShipmentResponse> {
  return (await authenticatedRequest("/api/v1/me/sub-orders/" + encodeURIComponent(id) + "/confirm-delivery",
    post({ expectedVersion }))).json();
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

export async function submitCheckoutPro(id: string, attemptId: string): Promise<PaymentAttempt> {
  return (await authenticatedRequest("/api/v1/orders/" + encodeURIComponent(id) + "/payments/pro",
    post({ attemptId }, attemptId), 25_000)).json();
}

export async function reconcileCheckoutPro(id: string, attemptId: string, signal?: AbortSignal): Promise<PaymentAttempt> {
  return (await authenticatedRequest("/api/v1/orders/" + encodeURIComponent(id) + "/payments/" + encodeURIComponent(attemptId)
    + "/reconcile-pro", { method: "POST", signal }, 25_000)).json();
}

export async function simulateAttemptOutcome(id: string, attemptId: string,
  outcome: VerifiedAttemptOutcome): Promise<PaymentAttempt> {
  return (await authenticatedRequest("/api/v1/test-fixtures/orders/" + encodeURIComponent(id) + "/payments/"
    + encodeURIComponent(attemptId) + "/outcome", post({ outcome }))).json();
}

export function checkoutError(error: unknown) {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "ADDRESS_NOT_FOUND": return "La dirección cambió. Elegí otra dirección para calcular el envío.";
      case "CART_VERSION_CONFLICT": return "El carrito cambió. Revisalo y volvé a calcular el envío.";
      case "QUOTE_CHANGED": return "Cambió el precio o la dirección. Volvé a calcular el envío para actualizar el resumen de compra.";
      case "QUOTE_NOT_FOUND": return "Volvé a calcular el envío para actualizar el resumen de compra.";
      case "QUOTE_EXPIRED": return "Venció el cálculo del envío. Volvé a calcularlo para continuar.";
      case "STOCK_CHANGED": return "Cambió la disponibilidad. Revisá el carrito antes de continuar.";
      case "CART_EMPTY": return "Agregá productos al carrito para continuar con la compra.";
      case "ORDER_ALREADY_ACTIVE": return "Ya tenés una reserva para esta cuenta.";
      case "CHECKOUT_EXPIRED": return "Tu reserva anterior venció.";
      case "QUOTE_ALREADY_CONFIRMED": return "Ya se creó una orden para esta compra. Podés verla en Mis órdenes.";
      case "EMAIL_NOT_VERIFIED": return "Verificá tu correo desde Mi cuenta.";
      case "CHECKOUT_TOTAL_LIMIT": return "El importe supera el máximo de una orden. Revisá el carrito.";
    }
  }
  return "No pudimos completar la solicitud. Volvé a intentar.";
}

export function remainingMs(value: Received<Timed>) {
  return Date.parse(value.expiresAt) - Date.parse(value.serverNow) - (performance.now() - value.receivedAt);
}

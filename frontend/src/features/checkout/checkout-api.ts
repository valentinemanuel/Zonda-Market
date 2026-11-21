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
}>;

export const activeOrderKey = (revision: number) => ["private", "active-order", revision] as const;
export const orderKey = (revision: number, id: string) => ["private", "order", revision, id] as const;
export const reservationPath = (id: string) => "/checkout/reserva/" + encodeURIComponent(id);

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
      case "EMAIL_NOT_VERIFIED": return "Verificá tu correo desde Mi cuenta.";
      case "CHECKOUT_TOTAL_LIMIT": return "El importe supera el máximo de una orden. Revisá el carrito.";
    }
  }
  return "No pudimos completar la solicitud. Volvé a intentar.";
}

export function remainingMs(value: Received<Timed>) {
  return Date.parse(value.expiresAt) - Date.parse(value.serverNow) - (performance.now() - value.receivedAt);
}

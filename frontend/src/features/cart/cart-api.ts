import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSyncExternalStore } from "react";
import { authenticatedRequest, getSessionRevision, subscribeToSession } from "@/features/auth/session-api";
import { ApiError } from "@/shared/api/http-client";

const path = "/api/v1/cart";
const cartKey = (revision: number) => ["private", "cart", revision] as const;

export type UnavailableReason = "LISTING_UNAVAILABLE" | "SKU_UNAVAILABLE" | "INSUFFICIENT_STOCK" | "SELF_PURCHASE";

export type CartItem = {
  skuId: string;
  listingSlug: string | null;
  thumbnailUrl?: string | null;
  title: string;
  attributes: Record<string, string>;
  quantity: number;
  availableStock: number;
  unitPrice: string;
  referencePrice: string;
  subtotal: string | null;
  reason: UnavailableReason | null;
  reserved: boolean;
};

export type Cart = {
  version: number;
  groups: { sellerId: string; sellerSlug: string; sellerName: string; items: CartItem[]; subtotal: string }[];
  unavailable: CartItem[];
  total: string;
  currency: "ARS";
  itemCount: number;
  activeOrderId: string | null;
};

type Edit = { skuId: string; expectedVersion: number } & (
  { kind: "put"; quantity: number } | { kind: "remove" }
);

export function findCartItem(cart: Cart, skuId: string) {
  for (const group of cart.groups) {
    const item = group.items.find((entry) => entry.skuId === skuId);
    if (item) return item;
  }
  return cart.unavailable.find((item) => item.skuId === skuId);
}

export function useCart(enabled = true) {
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);
  const query = useQuery({
    queryKey: cartKey(revision),
    queryFn: async ({ signal }): Promise<Cart> => (await authenticatedRequest(path, { signal })).json(),
    enabled,
    retry: false,
  });
  return { query, revision };
}

export function useCartEdit(revision: number) {
  const client = useQueryClient();
  const key = cartKey(revision);
  return useMutation({
    mutationFn: async (edit: Edit): Promise<Cart> => {
      if (revision !== getSessionRevision()) throw new DOMException("La sesión cambió.", "AbortError");
      const itemPath = path + "/items/" + edit.skuId;
      if (edit.kind === "remove") {
        return (await authenticatedRequest(itemPath + "?expectedVersion=" + edit.expectedVersion, { method: "DELETE" })).json();
      }
      return (await authenticatedRequest(itemPath, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ quantity: edit.quantity, expectedVersion: edit.expectedVersion }),
      })).json();
    },
    onMutate: () => client.cancelQueries({ queryKey: key }),
    onSuccess: async (cart) => {
      if (revision !== getSessionRevision()) return;
      await client.cancelQueries({ queryKey: key });
      if (revision === getSessionRevision()) {
        client.setQueryData<Cart>(key, (previous) => previous && previous.version > cart.version ? previous : cart);
      }
    },
    onError: async (error) => {
      if (revision === getSessionRevision() && error instanceof ApiError && [401, 404, 409].includes(error.status)) {
        await client.invalidateQueries({ queryKey: key });
      }
    },
  });
}

export function useCartReorder(revision: number) {
  const client = useQueryClient();
  const queryKey = cartKey(revision);
  return useMutation({
    mutationFn: async ({ orderId, key }: { orderId: string; key: string }): Promise<Cart> => {
      if (revision !== getSessionRevision()) throw new DOMException("La sesión cambió.", "AbortError");
      return (await authenticatedRequest(path + "/reorders/" + encodeURIComponent(orderId), {
        method: "POST",
        headers: { "Idempotency-Key": key },
      })).json();
    },
    onSettled: async () => {
      if (revision === getSessionRevision()) await client.invalidateQueries({ queryKey });
    },
  });
}

export function reorderError(error: unknown) {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "REORDER_NOT_FOUND": return "No encontramos esta compra.";
      case "CART_QUANTITY_LIMIT": return "Esta recompra superaría las 99 unidades de una variante en tu carrito.";
      case "CART_ITEM_RESERVED": return "Uno de los productos está incluido en una reserva activa.";
      case "SKU_NOT_FOUND": return "Uno de los productos ya no está disponible.";
      case "INSUFFICIENT_STOCK": return "Uno de los productos no tiene stock para la cantidad solicitada.";
    }
  }
  return "No pudimos confirmar la recompra. Volvé a intentar.";
}

export function signInRequired(error: unknown) {
  return error instanceof ApiError && error.status === 401;
}

export function cartEditError(error: unknown) {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "CART_VERSION_CONFLICT": return "El carrito cambió en otra sesión. Revisá las cantidades y volvé a intentar.";
      case "INSUFFICIENT_STOCK": return "La cantidad supera el stock disponible.";
      case "SELF_PURCHASE": return "No podés agregar una publicación propia.";
      case "SKU_NOT_FOUND": return "La variante ya no está disponible.";
      case "CART_ITEM_NOT_FOUND": return "La variante ya no está en tu carrito.";
      case "CART_ITEM_RESERVED": return "La variante está incluida en una reserva.";
    }
  }
  return "No pudimos actualizar el carrito. Volvé a intentar.";
}

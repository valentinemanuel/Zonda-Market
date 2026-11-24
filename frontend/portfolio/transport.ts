import type { Cart, CartItem } from "@/features/cart/cart-api";
import { catalog, categoryById, listings, questions, snapshot } from "./catalog-data";

const stock = 5;
const storageKey = "zonda-market-portfolio-cart-v2";
const variants = new Map(snapshot.listings.flatMap(({ summary, detail }) =>
  detail.skus.map((sku) => [sku.id, { sku, summary }] as const)));
type CartState = { version: number; quantities: Record<string, number> };
let cart: CartState = { version: 0, quantities: {} };

function readCart(): CartState {
  try {
    const saved = JSON.parse(localStorage.getItem(storageKey) ?? "null") as CartState | null;
    if (saved && Number.isSafeInteger(saved.version) && saved.version >= 0 && saved.quantities) {
      const quantities = Object.fromEntries(Object.entries(saved.quantities).filter(([id, quantity]) =>
        variants.get(id)?.sku.available && Number.isInteger(quantity) && quantity > 0 && quantity <= stock));
      return { version: saved.version, quantities };
    }
  } catch { /* The cart remains usable when browser storage is unavailable. */ }
  return cart;
}

function amount(cents: number) {
  return (cents / 100).toFixed(2);
}

function presentCart(): Cart {
  const { version, quantities } = readCart();
  const bySeller = new Map<string, { sellerName: string; items: CartItem[]; cents: number }>();
  let itemCount = 0;
  let total = 0;
  for (const [skuId, quantity] of Object.entries(quantities)) {
    const entry = variants.get(skuId);
    if (!entry) continue;
    const { sku, summary } = entry;
    const cents = Math.round(Number(sku.price) * 100) * quantity;
    const group = bySeller.get(summary.seller) ?? { sellerName: summary.sellerName, items: [], cents: 0 };
    group.items.push({ skuId, listingSlug: summary.slug, title: summary.title, thumbnailUrl: summary.thumbnailUrl,
      attributes: sku.attributes, quantity, availableStock: stock, unitPrice: sku.price, referencePrice: sku.price,
      subtotal: amount(cents), reason: null, reserved: false });
    group.cents += cents;
    bySeller.set(summary.seller, group);
    total += cents;
    itemCount += quantity;
  }
  const groups = [...bySeller].map(([slug, group]) => ({ sellerId: slug, sellerSlug: slug,
    sellerName: group.sellerName, items: group.items, subtotal: amount(group.cents) }));
  return { version, itemCount, groups, total: amount(total), currency: "ARS", unavailable: [], activeOrderId: null };
}

function problem(status: number, code: string) {
  return Response.json({ status, code }, { status, headers: { "Content-Type": "application/problem+json" } });
}

function updateCart(id: string, expectedVersion: number, quantity: number) {
  const current = readCart();
  if (current.version !== expectedVersion) return problem(409, "CART_VERSION_CONFLICT");
  if (!variants.get(id)?.sku.available) return problem(404, "SKU_NOT_FOUND");
  if (!Number.isInteger(quantity) || quantity < 0 || quantity > stock) return problem(409, "INSUFFICIENT_STOCK");
  const quantities = { ...current.quantities };
  if (quantity) quantities[id] = quantity;
  else delete quantities[id];
  cart = { version: current.version + 1, quantities };
  try { localStorage.setItem(storageKey, JSON.stringify(cart)); } catch { /* Use the in-memory cart. */ }
  return Response.json(presentCart());
}

export async function servePortfolio(path: string, init: RequestInit): Promise<Response> {
  init.signal?.throwIfAborted();
  const { pathname, searchParams } = new URL(path, location.origin);
  const route = pathname.split("/").slice(3).map(decodeURIComponent);
  const [resource, id, subresource] = route;
  const method = init.method ?? "GET";

  if (pathname === "/api/v1/auth/refresh" && method === "POST") {
    return Response.json({ accessToken: "local-portfolio-session", expiresIn: 3600, roles: ["USER"] });
  }
  if (pathname === "/api/v1/cart/items/" + subresource && method === "PUT" && typeof init.body === "string") {
    const fields = JSON.parse(init.body) as { quantity: number; expectedVersion: number };
    return updateCart(subresource, fields.expectedVersion, fields.quantity);
  }
  if (pathname === "/api/v1/cart/items/" + subresource && method === "DELETE") {
    return updateCart(subresource, Number(searchParams.get("expectedVersion")), 0);
  }
  if (method !== "GET") return problem(404, "DEMO_ACTION_UNAVAILABLE");
  if (pathname === "/api/v1/cart") return Response.json(presentCart());
  if (pathname === "/api/v1/me/notifications") {
    return Response.json({ content: [], page: 0, size: 10, hasNext: false, unreadCount: 0 });
  }
  if (resource === "categories") {
    const result = id ? categoryById(id) : snapshot.categories;
    return result ? Response.json(result) : problem(404, "CATEGORY_NOT_FOUND");
  }
  if (resource === "listings") {
    if (subresource === "questions") {
      const page = Number(searchParams.get("page") ?? 0);
      return Response.json((page === 0 && questions.get(id)) || { content: [], page, hasNext: false });
    }
    if (!id) return Response.json(catalog(searchParams));
    // Preserve links created by the first portfolio deployment.
    const entry = listings.get(id) ?? listings.get(`demo-${id}-nuevo`);
    return entry ? Response.json(entry.detail) : problem(404, "LISTING_NOT_FOUND");
  }
  return problem(404, "DEMO_ROUTE_UNAVAILABLE");
}

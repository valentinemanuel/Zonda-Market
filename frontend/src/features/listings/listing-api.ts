import { authenticatedRequest } from "@/features/auth/session-api";

const sellerPath = "/api/v1/seller";
const listingsPath = sellerPath + "/listings";

export type ListingCategory = { id: string; name: string; attributeSchema: Record<string, string[]> };
export type ListingSummary = { id: string; title: string; status: "BORRADOR" };
export type PublicationFields = {
  categoryId: string;
  title: string;
  description: string;
  specs: Record<string, unknown>;
};
export type Listing = ListingSummary & PublicationFields & { version: number; skus: Sku[] };
export type Sku = {
  id: string;
  code: string;
  attributes: Record<string, string>;
  price: string;
  availableStock: number;
  version: number;
};

type SkuFields = Pick<Sku, "attributes" | "price">;
type SkuCommand = SkuFields & ({ code: string; initialStock: number } | { expectedVersion: number });

async function read<T>(path: string, signal: AbortSignal): Promise<T> {
  return (await authenticatedRequest(path, { signal })).json();
}

async function write<T>(path: string, method: "POST" | "PATCH", body: object): Promise<T> {
  return (await authenticatedRequest(path, {
    method,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  })).json();
}

export function fetchListings(signal: AbortSignal) {
  return read<ListingSummary[]>(listingsPath, signal);
}

export function fetchListing(id: string, signal: AbortSignal) {
  return read<Listing>(listingsPath + "/" + encodeURIComponent(id), signal);
}

export function fetchListingCategories(signal: AbortSignal) {
  return read<ListingCategory[]>(sellerPath + "/listing-categories", signal);
}

export function createListing(fields: PublicationFields) {
  return write<Listing>(listingsPath, "POST", fields);
}

export function editListing(listing: Listing, fields: Partial<PublicationFields>, expectedVersion: number) {
  return write<Listing>(listingsPath + "/" + listing.id, "PATCH", { ...fields, expectedVersion });
}

export function saveSku(listingId: string, fields: SkuCommand, skuId?: string) {
  const path = listingsPath + "/" + listingId + "/skus";
  return skuId ? write<Sku>(path + "/" + skuId, "PATCH", fields) : write<Sku>(path, "POST", fields);
}

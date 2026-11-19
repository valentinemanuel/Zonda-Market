import { authenticatedRequest } from "@/features/auth/session-api";
import type { Seller } from "@/features/auth/profile-api";
import { request } from "@/shared/api/http-client";

const sellerPath = "/api/v1/seller";
const listingsPath = sellerPath + "/listings";

export type ListingCategory = { id: string; name: string; attributeSchema: Record<string, string[]> };
export type ListingStatus = "BORRADOR" | "ACTIVA" | "ARCHIVADA";
export const listingStatusLabel: Record<ListingStatus, string> = {
  BORRADOR: "Borrador",
  ACTIVA: "Publicada",
  ARCHIVADA: "Archivada",
};

export type ListingSummary = { id: string; title: string; status: ListingStatus };
export type PublicationFields = {
  categoryId: string;
  title: string;
  description: string;
  specs: Record<string, unknown>;
};
export type ListingImage = { id: string; url: string; thumbnailUrl: string; alt: string; position: number };
export type Listing = ListingSummary & PublicationFields & {
  slug: string | null; version: number; skus: Sku[]; images: ListingImage[];
};
export type PublicSku = Pick<Sku, "id" | "attributes" | "price"> & { available: boolean };
export type ListingDetail = Pick<PublicationFields, "title" | "description" | "specs"> & {
  id: string; slug: string; category: ListingCategory; seller: Seller; images: ListingImage[]; skus: PublicSku[];
};
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

export async function uploadImage(listingId: string, fields: FormData): Promise<ListingImage> {
  return (await authenticatedRequest(listingsPath + "/" + listingId + "/images", {
    method: "POST", body: fields,
  }, 65_000)).json();
}

export function editImage(listingId: string, imageId: string, fields: { alt?: string; position?: number }) {
  return write<ListingImage[]>(listingsPath + "/" + listingId + "/images/" + imageId, "PATCH", fields);
}

export async function deleteImage(listingId: string, imageId: string) {
  await authenticatedRequest(listingsPath + "/" + listingId + "/images/" + imageId, { method: "DELETE" });
}

export async function changePublicationState(listingId: string, action: "publish" | "archive"): Promise<Listing> {
  return (await authenticatedRequest(listingsPath + "/" + listingId + "/" + action, { method: "POST" })).json();
}

export async function fetchPublicListing(slug: string, signal: AbortSignal): Promise<ListingDetail> {
  return (await request("/api/v1/listings/" + encodeURIComponent(slug), { signal })).json();
}

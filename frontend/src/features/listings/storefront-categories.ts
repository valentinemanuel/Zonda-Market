import type { ListingCategory } from "./listing-api";

export type CatalogCategoryNode = { category: ListingCategory; children: CatalogCategoryNode[] };

export function categoryCatalogPath(id: string) {
  return "/catalogo?" + new URLSearchParams({ category: id });
}

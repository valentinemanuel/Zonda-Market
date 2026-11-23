import type { ListingCategory } from "./listing-api";

export type CatalogCategoryNode = { category: ListingCategory; children: CatalogCategoryNode[] };

export function isStorefrontQuery(params: URLSearchParams) {
  return [...params.keys()].every((key) => key === "condition" || key === "sort" || key === "page");
}

export function categoryCatalogPath(id: string) {
  return "/catalogo?" + new URLSearchParams({ category: id });
}

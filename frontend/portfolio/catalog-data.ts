import type { CatalogItem } from "@/features/listings/catalog-product-card";
import type { ListingCategory, ListingDetail } from "@/features/listings/listing-api";
import rawSnapshot from "./catalog.snapshot.json";

type CategoryNode = {
  category: ListingCategory & { slug: string; parentId: string | null };
  children: CategoryNode[];
};
type Entry = { summary: CatalogItem; detail: ListingDetail; questions: unknown };

type Snapshot = { categories: CategoryNode[]; newest: string[]; listings: Entry[] };
// The JSON is generated from the public endpoints by snapshot.mjs.
export const snapshot = rawSnapshot as unknown as Snapshot;
export const listings = new Map(snapshot.listings.map((entry) => [entry.summary.slug, entry]));
export const questions = new Map(snapshot.listings.map((entry) => [entry.summary.id, entry.questions]));

function flatten(nodes: CategoryNode[]): CategoryNode[] {
  return nodes.flatMap((node) => [node, ...flatten(node.children)]);
}

const categories = new Map(flatten(snapshot.categories).map((node) => [node.category.id, node]));
const newestOrder = new Map(snapshot.newest.map((slug, index) => [slug, index]));
const normalize = (value: string) => value.toLowerCase().normalize("NFD").replace(/\p{M}/gu, "");

export function categoryById(id: string) {
  return categories.get(id)?.category;
}

export function catalog(params: URLSearchParams) {
  const page = Math.max(0, Number.parseInt(params.get("page") ?? "", 10) || 0);
  const size = 12;
  const categoryId = params.get("category");
  const root = categoryId ? categories.get(categoryId) : undefined;
  const allowed = new Set(root ? flatten([root]).map(({ category }) => category.id) : []);
  const seller = params.get("seller");
  const condition = params.get("condition");
  const min = Number(params.get("min") ?? "0");
  const max = Number(params.get("max") ?? "Infinity");
  const stock = params.get("inStock");
  const sort = params.get("sort");
  const attributes = params.getAll("attr").map((entry) => {
    const separator = entry.indexOf(":");
    return [entry.slice(0, separator), entry.slice(separator + 1)] as const;
  });
  const terms = normalize(params.get("q") ?? "").match(/[\p{L}\p{N}]+/gu) ?? [];
  const items: CatalogItem[] = [];
  const facets = new Map<string, { key: string; value: string; count: number }>();
  const sellers = new Map<string, { slug: string; name: string; count: number }>();

  for (const { detail, summary } of snapshot.listings) {
    if (categoryId && !allowed.has(summary.categoryId)) continue;
    if (seller && seller !== summary.seller) continue;
    if (condition === "nuevo" && summary.condition !== "nuevo") continue;
    if (condition === "usado" && summary.condition === "nuevo") continue;
    const text = normalize(`${detail.title} ${detail.description} ${detail.category.name}`);
    if (!terms.every((term) => text.includes(term) || (term.length > 4 && term.endsWith("s") && text.includes(term.slice(0, -1))))) continue;

    const matching = detail.skus.filter((sku) => Number(sku.price) >= min && Number(sku.price) <= max
      && attributes.every(([key, value]) => sku.attributes[key] === value));
    const available = matching.filter((sku) => sku.available);
    if (!matching.length || (stock === "true" && !available.length) || (stock === "false" && available.length)) continue;
    const candidates = available.length ? available : matching;
    const price = candidates.reduce((lowest, sku) => Math.min(lowest, Number(sku.price)), Infinity).toFixed(2);
    items.push({ ...summary, available: available.length > 0, price, quickSkuId: available.length === 1 ? available[0].id : null });

    const counted = new Set<string>();
    for (const sku of stock === "true" ? available : matching) {
      for (const [key, value] of Object.entries(sku.attributes)) {
        const id = JSON.stringify([key, value]);
        if (counted.has(id)) continue;
        counted.add(id);
        const prior = facets.get(id);
        if (prior) prior.count++;
        else facets.set(id, { key, value, count: 1 });
      }
    }
    const prior = sellers.get(summary.seller);
    if (prior) prior.count++;
    else sellers.set(summary.seller, { slug: summary.seller, name: summary.sellerName, count: 1 });
  }

  if (sort === "price_asc" || sort === "price_desc") {
    const direction = sort === "price_asc" ? 1 : -1;
    items.sort((first, second) => direction * (Number(first.price) - Number(second.price)));
  }
  if (sort === "newest") items.sort((first, second) => (newestOrder.get(first.slug) ?? 0) - (newestOrder.get(second.slug) ?? 0));
  return {
    content: items.slice(page * size, (page + 1) * size), page,
    totalPages: Math.ceil(items.length / size), totalElements: items.length,
    facets: [...facets.values()], sellers: [...sellers.values()],
  };
}

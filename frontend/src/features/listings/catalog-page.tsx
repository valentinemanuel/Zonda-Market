import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { useEffect, useRef, type FormEvent, type ReactNode } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { profileControl, profileLink } from "@/features/auth/profile-fields";
import { getJson } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import type { ListingCategory } from "./listing-api";
import { formattedPrice } from "./public-listing-format";
import { isStorefrontQuery, type CatalogCategoryNode } from "./storefront-categories";
import { StorefrontEnd, StorefrontIntro } from "./storefront-intro";
import { CatalogProductCard, type CatalogItem } from "./catalog-product-card";

type CategoryOption = ListingCategory & { depth: number };
type Facet = { key: string; value: string; count: number };
type CatalogPage = {
  content: CatalogItem[];
  page: number; totalPages: number; totalElements: number;
  facets: Facet[];
  sellers: { slug: string; name: string; count: number }[];
};

const conditions = [
  { value: "", label: "Todos" },
  { value: "nuevo", label: "Nuevo" },
  { value: "usado", label: "Usado" },
];

function flatten(nodes: CatalogCategoryNode[], depth = 0): CategoryOption[] {
  return nodes.flatMap(({ category, children }) => [{ ...category, depth }, ...flatten(children, depth + 1)]);
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return <label className="grid min-w-0 gap-1.5 text-sm font-medium">{label}{children}</label>;
}

export function PublicCatalogPage() {
  const [params, setParams] = useSearchParams();
  const filters = params.toString();
  const storefront = isStorefrontQuery(params);
  const q = params.get("q") ?? "";
  const condition = params.get("condition") ?? "";
  const categoryId = params.get("category") ?? "";
  const showFilters = Boolean(q.trim() || categoryId);
  const seller = params.get("seller") ?? "";
  const min = params.get("min") ?? "";
  const max = params.get("max") ?? "";
  const priceForm = useRef<HTMLFormElement>(null);
  const chosen = new Map(params.getAll("attr").map((entry) => {
    const split = entry.indexOf(":");
    return [entry.slice(0, split), entry.slice(split + 1)];
  }));
  const categories = useQuery({
    queryKey: ["catalog-categories"],
    queryFn: ({ signal }) => getJson("/api/v1/categories", signal) as Promise<CatalogCategoryNode[]>,
    retry: false,
  });
  const results = useQuery({
    queryKey: ["catalog", filters],
    queryFn: ({ signal }) => getJson("/api/v1/listings?" + filters, signal) as Promise<CatalogPage>,
    retry: false,
    placeholderData: keepPreviousData,
  });

  useEffect(() => { priceForm.current?.reset(); }, [min, max]);

  function filter(key: string, value: string) {
    const next = new URLSearchParams(params);
    update(next, key, value);
    if (key === "category") next.delete("attr");
    next.delete("page");
    setParams(next, { preventScrollReset: key === "condition" || key === "sort" });
  }

  function selectAttribute(key: string, value: string) {
    const next = new URLSearchParams(params);
    next.delete("attr");
    next.delete("page");
    if (value) chosen.set(key, value);
    else chosen.delete(key);
    for (const [name, option] of chosen) next.append("attr", name + ":" + option);
    setParams(next);
  }

  function submitPrices(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const next = new URLSearchParams(params);
    update(next, "min", String(data.get("min") ?? ""));
    update(next, "max", String(data.get("max") ?? ""));
    next.delete("page");
    setParams(next);
  }

  const options = flatten(categories.data ?? []);
  const schema = options.find(({ id }) => id === categoryId)?.attributeSchema ?? {};
  const attributes = new Map<string, Map<string, number | undefined>>();
  function addOption(key: string, value: string, count?: number) {
    if (!attributes.has(key)) attributes.set(key, new Map());
    attributes.get(key)?.set(value, count);
  }
  Object.entries(schema).forEach(([key, values]) => values.forEach((value) => addOption(key, value)));
  results.data?.facets.forEach(({ key, value, count }) => addOption(key, value, count));
  chosen.forEach((value, key) => {
    if (!attributes.get(key)?.has(value)) addOption(key, value);
  });

  const cleaned = new URLSearchParams();
  if (q) cleaned.set("q", q);

  const filtersPanel = (
        <Card className="py-4">
          <CardContent className="space-y-4 px-4">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h2 className="font-semibold">Filtros</h2>
              {filters !== cleaned.toString() && <Link className={profileLink + " text-sm"} to={"/catalogo?" + cleaned}>Limpiar</Link>}
            </div>
            <Field label="Categoría">
              <select className={profileControl} value={categoryId} onChange={(event) => filter("category", event.currentTarget.value)}>
                <option value="">Todas</option>
                {categoryId && !options.some(({ id }) => id === categoryId) && <option value={categoryId}>Categoría seleccionada</option>}
                {options.map(({ id, name, depth }) => <option key={id} value={id}>{"— ".repeat(depth)}{name}</option>)}
              </select>
            </Field>
            {categories.isPending && <p role="status" className="text-sm">Cargando categorías…</p>}
            {categories.isError && <div className="space-y-2">
              <p role="alert" className="text-sm text-destructive">No pudimos cargar las categorías.</p>
              <Button type="button" variant="outline" onClick={() => void categories.refetch()}>Reintentar categorías</Button>
            </div>}
            <Field label="Vendedor">
              <select className={profileControl} value={seller} onChange={(event) => filter("seller", event.currentTarget.value)}>
                <option value="">Todos</option>
                {seller && !results.data?.sellers.some(({ slug }) => slug === seller) && <option value={seller}>{seller}</option>}
                {results.data?.sellers.map(({ slug, name, count }) => <option key={slug} value={slug}>{name} ({count})</option>)}
              </select>
            </Field>
            <form ref={priceForm} className="space-y-2" onSubmit={submitPrices}>
              <div className="grid grid-cols-2 gap-2">
                <Field label="Desde ($)">
                  <input name="min" type="number" min="0" step="0.01" className={profileControl} defaultValue={min} />
                </Field>
                <Field label="Hasta ($)">
                  <input name="max" type="number" min="0" step="0.01" className={profileControl} defaultValue={max} />
                </Field>
              </div>
              <Button type="submit" variant="outline" className="w-full">Aplicar precio</Button>
            </form>
            <Field label="Disponibilidad">
              <select className={profileControl} value={params.get("inStock") ?? ""}
                onChange={(event) => filter("inStock", event.currentTarget.value)}>
                <option value="">Todas</option>
                <option value="true">Disponible</option>
                <option value="false">Sin stock</option>
              </select>
            </Field>
            {[...attributes].map(([key, values]) => (
              <Field key={key} label={key.charAt(0).toUpperCase() + key.slice(1)}>
                <select className={profileControl} value={chosen.get(key) ?? ""}
                  onChange={(event) => selectAttribute(key, event.currentTarget.value)}>
                  <option value="">Todos</option>
                  {[...values].map(([value, count]) => (
                    <option key={value} value={value}>{value}{count === undefined ? "" : " (" + count + ")"}</option>
                  ))}
                </select>
              </Field>
            ))}
          </CardContent>
        </Card>
  );
  const resultsPanel = (
        <section key="results" className="min-w-0 space-y-3" aria-label="Resultados">
          {(results.isPending || results.isPlaceholderData) && <p role="status">Cargando publicaciones…</p>}
          {results.isError && <div className="space-y-3 rounded-lg border bg-card p-5">
            <p role="alert" className="text-destructive">No pudimos cargar el catálogo. Revisá los filtros o volvé a intentar.</p>
            <Button type="button" variant="outline" onClick={() => void results.refetch()}>Reintentar catálogo</Button>
          </div>}
          {results.data && !results.isPlaceholderData && <>
            <p role="status" className="text-sm text-muted-foreground">
              {results.data.totalElements === 1 ? "1 publicación" : results.data.totalElements + " publicaciones"}
            </p>
            {results.data.totalElements === 0 && <p className="rounded-lg border bg-card p-5">
              {filters ? "No encontramos publicaciones con esos filtros." : "Todavía no hay publicaciones visibles."}
            </p>}
            {results.data.totalElements > 0 && results.data.content.length === 0 && <p>Esta página no tiene resultados.</p>}
            <ul className={`grid gap-3 min-[360px]:grid-cols-2 ${storefront ? "md:grid-cols-3 lg:grid-cols-4" : "xl:grid-cols-3"}`}>
              {results.data.content.map((item) => <CatalogProductCard key={item.id} item={item} catalogSearch={filters} />)}
            </ul>
            {(results.data.totalPages > 1 || results.data.page > 0) &&
              <CatalogPagination params={params} page={results.data.page} total={results.data.totalPages} />}
          </>}
        </section>
  );

  return (
    <>
      {storefront && <StorefrontIntro categories={categories.data ?? []} />}
      <div className={storefront ? "store-frame mt-6 space-y-6" : "space-y-6"}>
        <header id="productos" className="flex scroll-mt-40 flex-wrap items-center gap-x-4 gap-y-3">
          <h1 className={storefront ? "sr-only" : "store-section-title mr-auto"}>Catálogo</h1>
          {!showFilters && max && /^\d+(?:\.\d{1,2})?$/.test(max) &&
            <p className="text-sm font-medium text-muted-foreground">Productos hasta {formattedPrice(max)}</p>}
          {storefront && <h2 className="store-section-title mr-auto">Productos destacados</h2>}
          <div role="group" aria-label="Condición del producto" className="flex flex-wrap gap-1.5">
            {conditions.map(({ value, label }) => <Button key={value} type="button" variant="outline"
              aria-pressed={condition === value} onClick={() => filter("condition", value)}
              className="h-10 rounded-full px-3.5 text-sm shadow-none aria-pressed:border-primary/40 aria-pressed:bg-accent aria-pressed:text-primary">
              {label}
            </Button>)}
          </div>
          <Field label="Ordenar por">
            <select className={profileControl} value={params.get("sort") ?? "relevance"}
              onChange={(event) => filter("sort", event.currentTarget.value)}>
              <option value="relevance">Relevancia</option>
              <option value="newest">Más recientes</option>
              <option value="price_asc">Menor precio</option>
              <option value="price_desc">Mayor precio</option>
            </select>
          </Field>
        </header>
        <div className={showFilters ? "grid items-start gap-6 lg:grid-cols-[15rem_minmax(0,1fr)]" : "grid gap-6"}>
          {showFilters && filtersPanel}
          {resultsPanel}
        </div>
        {storefront && <StorefrontEnd />}
      </div>
    </>
  );
}

function update(params: URLSearchParams, key: string, value: string) {
  if (value) params.set(key, value);
  else params.delete(key);
}

function pageLink(params: URLSearchParams, page: number) {
  const next = new URLSearchParams(params);
  update(next, "page", page ? String(page) : "");
  return { search: next.toString(), hash: "#productos" };
}

function CatalogPagination({ params, page, total }: { params: URLSearchParams; page: number; total: number }) {
  const navigate = useNavigate();
  const width = 8;
  const start = Math.max(1, Math.min(page - 3, total - width - 1));
  const pages = total <= 10 ? Array.from({ length: total }, (_, index) => index)
    : [0, ...Array.from({ length: width }, (_, index) => start + index), total - 1];
  return <nav aria-label="Páginas del catálogo" className="flex flex-wrap items-center justify-center gap-1 rounded-panel border bg-card p-2 shadow-panel">
    {page > 0 && <Button asChild variant="ghost" className="min-h-11">
      <Link to={pageLink(params, page >= total ? 0 : page - 1)}><ChevronLeft aria-hidden="true" />Anterior</Link>
    </Button>}
    {pages.map((number, index) => <span key={number} className="inline-flex items-center gap-1">
      {index > 0 && number - pages[index - 1] > 1 && <span aria-hidden="true" className="px-1 text-muted-foreground">…</span>}
      <Button asChild variant={number === page ? "outline" : "ghost"} className={
        "size-10 px-0 tabular-nums " + (number === page ? "border-primary bg-accent text-primary" : "")}>
        <Link aria-label={"Página " + (number + 1)} aria-current={number === page ? "page" : undefined}
          to={pageLink(params, number)}>{number + 1}</Link>
      </Button>
    </span>)}
    {page + 1 < total && <Button asChild variant="ghost" className="min-h-11">
      <Link to={pageLink(params, page + 1)}>Siguiente<ChevronRight aria-hidden="true" /></Link>
    </Button>}
    {total > 10 && <form className="flex items-center gap-2 pl-2" onSubmit={(event) => {
      event.preventDefault();
      const target = Number(new FormData(event.currentTarget).get("destination"));
      if (Number.isInteger(target) && target >= 1 && target <= total) navigate(pageLink(params, target - 1));
    }}>
      <label className="flex items-center gap-2 text-sm">
        Ir a página
        <input key={page} name="destination" type="number" inputMode="numeric" min={1} max={total} step={1} required
          className="h-10 w-20 rounded-lg border border-input bg-card px-2 tabular-nums" />
      </label>
      <Button type="submit" variant="outline">Ir</Button>
    </form>}
  </nav>;
}

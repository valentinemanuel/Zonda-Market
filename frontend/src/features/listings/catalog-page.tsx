import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Package, Search } from "lucide-react";
import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { profileControl, profileLink } from "@/features/auth/profile-fields";
import { getJson } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import type { ListingCategory } from "./listing-api";
import { formattedPrice, listingPublicPath } from "./public-listing-format";
import type { CatalogCategoryNode } from "./storefront-categories";
import { StorefrontEnd, StorefrontIntro } from "./storefront-intro";

type CategoryOption = ListingCategory & { depth: number };
type Facet = { key: string; value: string; count: number };
type CatalogPage = {
  content: {
    id: string; slug: string; title: string; categoryId: string; seller: string; sellerName: string;
    thumbnailUrl: string | null; price: string; available: boolean;
  }[];
  page: number; totalPages: number; totalElements: number;
  facets: Facet[];
  sellers: { slug: string; name: string; count: number }[];
};

function flatten(nodes: CatalogCategoryNode[], depth = 0): CategoryOption[] {
  return nodes.flatMap(({ category, children }) => [{ ...category, depth }, ...flatten(children, depth + 1)]);
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return <label className="grid min-w-0 gap-1.5 text-sm font-medium">{label}{children}</label>;
}

export function PublicCatalogPage() {
  const [params, setParams] = useSearchParams();
  const filters = params.toString();
  const storefront = !filters;
  const q = params.get("q") ?? "";
  const [draft, setDraft] = useState<{ base: string; value: string }>();
  const term = draft?.base === q ? draft.value : q;
  const categoryId = params.get("category") ?? "";
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

  useEffect(() => { setDraft(undefined); }, [q]);
  useEffect(() => { priceForm.current?.reset(); }, [min, max]);

  useEffect(() => {
    if (term.trim() === q) return;
    const timeout = window.setTimeout(() => {
      const next = new URLSearchParams(filters);
      update(next, "q", term.trim());
      next.delete("page");
      setParams(next, { replace: true });
    }, 350);
    return () => window.clearTimeout(timeout);
  }, [term, q, filters, setParams]);

  function filter(key: string, value: string) {
    const next = new URLSearchParams(params);
    update(next, key, value);
    if (key === "category") next.delete("attr");
    next.delete("page");
    setParams(next);
  }

  function selectAttribute(key: string, value: string) {
    const next = new URLSearchParams(params);
    update(next, "q", term.trim());
    next.delete("attr");
    next.delete("page");
    if (value) chosen.set(key, value);
    else chosen.delete(key);
    for (const [name, option] of chosen) next.append("attr", name + ":" + option);
    setParams(next);
  }

  function submitSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (term.trim() !== q) filter("q", term.trim());
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
        <Card key="filters" id="filtros" className="scroll-mt-40 py-4">
          <CardContent className={storefront ? "grid gap-4 px-4 sm:grid-cols-2 lg:grid-cols-4" : "space-y-4 px-4"}>
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
        <section key="results" className="min-w-0 space-y-4" aria-label="Resultados">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <h2 className="store-section-title">{storefront ? "Productos destacados" : "Publicaciones"}</h2>
            <Field label="Ordenar por">
              <select className={profileControl} value={params.get("sort") ?? "relevance"}
                onChange={(event) => filter("sort", event.currentTarget.value)}>
                <option value="relevance">Relevancia</option>
                <option value="newest">Más recientes</option>
                <option value="price_asc">Menor precio</option>
                <option value="price_desc">Mayor precio</option>
              </select>
            </Field>
          </div>
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
              {results.data.content.map((item) => <li key={item.id}>
                <Link to={listingPublicPath(item.slug)} state={{ catalogSearch: filters }}
                  className="store-product group flex h-full flex-col rounded-panel border bg-card p-3 shadow-panel hover:border-primary/50">
                  <div className="store-photo grid aspect-square place-items-center overflow-hidden rounded-lg bg-muted">
                    {item.thumbnailUrl ? <img src={item.thumbnailUrl} alt="" loading="lazy" decoding="async" className="size-full object-cover" />
                      : <Package aria-hidden="true" className="text-muted-foreground" />}
                  </div>
                  <div className="flex flex-1 flex-col gap-1 pt-2.5 [overflow-wrap:anywhere]">
                    <p className="text-xs text-muted-foreground">{item.sellerName}</p>
                    <h3 className="mb-auto line-clamp-2 text-sm font-semibold group-hover:underline">{item.title}</h3>
                    <p className="mt-2 border-t pt-2 text-lg font-bold tabular-nums">{formattedPrice(item.price)}</p>
                    <p className={"text-xs font-semibold " + (item.available ? "text-success" : "text-muted-foreground")}>
                      {item.available ? "Disponible" : "Sin stock"}
                    </p>
                  </div>
                </Link>
              </li>)}
            </ul>
            {(results.data.totalPages > 1 || results.data.page > 0) && <nav aria-label="Páginas del catálogo" className="flex flex-wrap items-center gap-3">
              {results.data.page > 0 && <Button asChild variant="outline">
                <Link to={pageLink(params, results.data.page >= results.data.totalPages ? 0 : results.data.page - 1)}>Anterior</Link>
              </Button>}
              {results.data.page < results.data.totalPages && <span>Página {results.data.page + 1} de {results.data.totalPages}</span>}
              {results.data.page + 1 < results.data.totalPages && <Button asChild variant="outline">
                <Link to={pageLink(params, results.data.page + 1)}>Siguiente</Link>
              </Button>}
            </nav>}
          </>}
        </section>
  );

  return (
    <div className="space-y-6">
      {storefront && <StorefrontIntro categories={categories.data ?? []} />}
      <header id="productos" className="flex scroll-mt-40 flex-wrap items-end justify-between gap-3 border-b pb-4">
        <h1 className="store-section-title">Catálogo</h1>
        <form role="search" aria-label="Buscar publicaciones" onSubmit={submitSearch} className="flex w-full max-w-sm flex-wrap items-end gap-2">
          <label className="grid min-w-36 flex-1 gap-1.5 text-sm font-medium">
            <span className="sr-only">Buscar publicaciones</span>
            <input type="search" name="q" maxLength={160} value={term}
              onChange={(event) => setDraft({ base: q, value: event.currentTarget.value })}
              className={profileControl}
              placeholder="¿Qué estás buscando?" />
          </label>
          <Button type="submit" className="min-h-11"><Search />Buscar</Button>
        </form>
      </header>
      <div className={storefront ? "grid gap-6" : "grid items-start gap-6 lg:grid-cols-[15rem_minmax(0,1fr)]"}>
        {storefront ? [resultsPanel, filtersPanel] : [filtersPanel, resultsPanel]}
      </div>
      {storefront && <StorefrontEnd />}
    </div>
  );
}

function update(params: URLSearchParams, key: string, value: string) {
  if (value) params.set(key, value);
  else params.delete(key);
}

function pageLink(params: URLSearchParams, page: number) {
  const next = new URLSearchParams(params);
  update(next, "page", page ? String(page) : "");
  return { search: next.toString() };
}

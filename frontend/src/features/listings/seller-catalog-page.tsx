import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, Boxes, CircleAlert, LayoutList, Package, Pencil, Plus, Search, SlidersHorizontal, X } from "lucide-react";
import { Dialog } from "radix-ui";
import { useRef, useState, useSyncExternalStore, type ReactNode } from "react";
import { Link, Navigate, useSearchParams } from "react-router-dom";
import { getSessionRevision, subscribeToSession } from "@/features/auth/session-api";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { fetchListing, fetchListings, listingStatusLabel, type Listing, type ListingSummary, type Sku } from "./listing-api";
import { attributeEntries, attributeLabel } from "./listing-forms";
import {
  inventoryPath, listingCollectionKey, listingDetailKey, ListingStatusBadge, ProductThumbnail,
  sellerListingsPath, SellerHeading, SellerNavigation, StockTotal, unitFormat,
} from "./seller-workspace";
import { StockEditor } from "./stock-editor";
import type { StockMovement } from "./stock-api";

function totalStock(listing: ListingSummary) {
  return listing.skus.reduce((total, sku) => total + sku.availableStock, 0);
}

function combination(sku: Sku) {
  return attributeEntries(sku.attributes).map(([name, value]) => attributeLabel(name) + " " + value).join(" · ") || "Opción única";
}

function searchable(text: string) {
  return text.normalize("NFD").replace(/\p{M}/gu, "").toLocaleLowerCase("es-AR").trim();
}

function Metric({ title, value, icon }: { title: string; value: number; icon: ReactNode }) {
  return (
    <div className="flex min-w-0 items-start gap-3 rounded-xl border bg-card p-2.5 sm:p-4">
      <span className="hidden size-10 shrink-0 place-items-center rounded-lg bg-secondary text-accent-foreground sm:grid">{icon}</span>
      <div>
        <p className="text-xs font-medium leading-snug text-muted-foreground sm:text-sm">{title}</p>
        <p className="text-xl font-semibold tracking-tight tabular-nums sm:text-2xl">{unitFormat.format(value)}</p>
      </div>
    </div>
  );
}

function Empty({ title, description, children }: { title: string; description?: string; children?: ReactNode }) {
  return (
    <section className="grid justify-items-center gap-3 rounded-xl border border-dashed bg-card px-5 py-10 text-center">
      <span className="grid size-12 place-items-center rounded-xl bg-muted text-muted-foreground"><Package aria-hidden="true" size={24} /></span>
      <div className="space-y-1">
        <h2 className="font-semibold">{title}</h2>
        {description && <p className="text-sm text-muted-foreground">{description}</p>}
      </div>
      {children}
    </section>
  );
}

export function ListingsPage() {
  return <CatalogWorkspace inventory={false} />;
}

export function InventoryPage() {
  return <CatalogWorkspace inventory />;
}

function CatalogWorkspace({ inventory }: { inventory: boolean }) {
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);
  const catalog = useQuery({ queryKey: listingCollectionKey(revision),
    queryFn: ({ signal }) => fetchListings(signal), retry: false });
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState("");
  const [route, setRoute] = useSearchParams();
  const all = catalog.data ?? [];
  const selectedListingId = inventory ? route.get("publicacion") : null;
  const selectedSkuId = inventory ? route.get("sku") : null;
  const needle = searchable(query);
  const selectedListing = all.find(({ id }) => id === selectedListingId);
  const scope = selectedListingId ? all.filter(({ id }) => id === selectedListingId) : all;
  const inventoryCount = scope.reduce((total, listing) => total + totalStock(listing), 0);
  const outOfStock = scope.reduce((total, listing) => total + listing.skus.filter((sku) => sku.availableStock === 0).length, 0);
  const adjusting = selectedSkuId
    ? all.find((listing) => (!selectedListingId || listing.id === selectedListingId) && listing.skus.some(({ id }) => id === selectedSkuId))
    : undefined;
  const selectedSku = adjusting?.skus.find(({ id }) => id === selectedSkuId);
  const visible = all.flatMap((listing) => {
    if (selectedListingId && listing.id !== selectedListingId) return [];
    const titleMatches = searchable(listing.title).includes(needle);
    const matches = (sku: Sku) => titleMatches || searchable(sku.code + " " + combination(sku)).includes(needle);
    if (!inventory) return (titleMatches || listing.skus.some(matches)) && (!filter || listing.status === filter) ? [listing] : [];
    const skus = listing.skus.filter((sku) => matches(sku) &&
      (!filter || (filter === "empty" ? sku.availableStock === 0 : sku.availableStock > 0)));
    return skus.length ? [{ ...listing, skus }] : [];
  });

  const cache = useQueryClient();
  const trigger = useRef<HTMLButtonElement | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const search = useRef<HTMLInputElement>(null);

  function closeAdjustment() {
    setRoute((current) => {
      const next = new URLSearchParams(current);
      next.delete("sku");
      return next;
    }, { replace: true });
  }

  function updateSku(listing: ListingSummary, updated: Sku) {
    const merge = (sku: Sku) => sku.id === updated.id && sku.version <= updated.version ? updated : sku;
    cache.setQueryData<ListingSummary[]>(listingCollectionKey(revision), (current) => current?.map((item) =>
      item.id === listing.id ? { ...item, skus: item.skus.map(merge) } : item));
    cache.setQueryData<Listing>(listingDetailKey(revision, listing.id), (current) => current && { ...current, skus: current.skus.map(merge) });
    if (listing.slug) void cache.invalidateQueries({ queryKey: ["listing-public", listing.slug] });
  }

  async function reloadStock(listing: ListingSummary, sku: Sku) {
    const detail = await fetchListing(listing.id, AbortSignal.timeout(8_000));
    const current = detail.skus.find(({ id }) => id === sku.id);
    if (!current) throw new Error("La opción ya no está disponible.");
    cache.setQueryData(listingDetailKey(revision, listing.id), detail);
    updateSku(listing, current);
    return current;
  }

  function stockSaved(listing: ListingSummary, sku: Sku, movement: StockMovement) {
    updateSku(listing, { ...sku, version: movement.version, availableStock: movement.afterStock });
  }

  if (catalog.error instanceof ApiError && catalog.error.status === 401) return <Navigate to="/ingresar" replace />;

  return (
    <div className="mx-auto max-w-6xl space-y-5">
      <SellerNavigation />
      <SellerHeading title={inventory ? "Inventario" : "Mis publicaciones"}
        description={inventory ? "Consultá las unidades disponibles y registrá cada ajuste con su motivo."
          : "Revisá qué está publicado, cuánto stock tenés y qué productos requieren atención."}
        actions={inventory
          ? <Button asChild className="min-h-11 bg-white text-slate-900 hover:bg-slate-100"><Link to={sellerListingsPath}><LayoutList /> Ver publicaciones</Link></Button>
          : <Button asChild className="min-h-11 shadow-sm"><Link to={sellerListingsPath + "/nueva"}><Plus /> Crear publicación</Link></Button>}
      />
      {catalog.isPending && <p role="status" className="rounded-xl border bg-card p-5">Cargando publicaciones…</p>}
      {catalog.isError && (
        <section className="space-y-3 rounded-xl border bg-card p-5">
          {catalog.error instanceof ApiError && catalog.error.status === 403 ? (
            <p role="alert">Activá tu perfil de vendedor desde <Link to="/cuenta" className="text-primary underline">Mi cuenta</Link>.</p>
          ) : (
            <>
              <p role="alert" className="text-destructive">No pudimos cargar las publicaciones o sus categorías.</p>
              <Button type="button" variant="outline" onClick={() => void catalog.refetch()}>Reintentar</Button>
            </>
          )}
        </section>
      )}
      {catalog.isSuccess && !all.length && (
        <Empty title="Todavía no creaste publicaciones." description="Cargá el primer producto para empezar a gestionar su stock.">
          <Button asChild className="min-h-11"><Link to={sellerListingsPath + "/nueva"}><Plus /> Crear primera publicación</Link></Button>
        </Empty>
      )}
      {catalog.isSuccess && all.length > 0 && (
        <>
          <div className="grid grid-cols-3 gap-2">
            {inventory ? (
              <>
                <Metric title="Unidades disponibles" value={inventoryCount} icon={<Boxes size={19} aria-hidden="true" />} />
                <Metric title="Opciones sin stock" value={outOfStock} icon={<CircleAlert size={19} aria-hidden="true" />} />
                <Metric title="Publicaciones con stock" value={scope.filter((item) => totalStock(item) > 0).length} icon={<Package size={19} aria-hidden="true" />} />
              </>
            ) : (
              <>
                <Metric title="Publicadas" value={all.filter(({ status }) => status === "ACTIVA").length} icon={<LayoutList size={19} aria-hidden="true" />} />
                <Metric title="Borradores" value={all.filter(({ status }) => status === "BORRADOR").length} icon={<Pencil size={19} aria-hidden="true" />} />
                <Metric title="Unidades disponibles" value={inventoryCount} icon={<Boxes size={19} aria-hidden="true" />} />
              </>
            )}
          </div>
          <section className="space-y-4" aria-label={inventory ? "Productos en inventario" : "Tus publicaciones"}>
            <div className="flex flex-wrap gap-3 rounded-xl border bg-card p-3">
              <div className="flex min-w-0 flex-1 basis-56 items-center gap-2 rounded-lg border bg-background px-3 focus-within:ring-2 focus-within:ring-ring">
                <Search size={18} className="shrink-0 text-muted-foreground" aria-hidden="true" />
                <input ref={search} aria-label="Buscar por producto o código" placeholder="Buscar por producto o código"
                  type="search" value={query} onChange={(event) => setQuery(event.target.value)}
                  className="min-h-11 min-w-0 flex-1 bg-transparent text-sm outline-none focus-visible:outline-none" />
              </div>
              <select aria-label={inventory ? "Filtrar por disponibilidad" : "Filtrar por estado"} value={filter}
                onChange={(event) => setFilter(event.target.value)} className="min-h-11 max-w-full rounded-lg border bg-background px-3 text-sm">
                {inventory ? <>
                  <option value="">Todo el stock</option><option value="available">Con stock</option><option value="empty">Sin stock</option>
                </> : <>
                  <option value="">Todos los estados</option>
                  {Object.entries(listingStatusLabel).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
                </>}
              </select>
            </div>
            {selectedListingId && (
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-sm">Mostrando <strong>{selectedListing?.title ?? "la publicación seleccionada"}</strong></span>
                <Button asChild variant="secondary" size="sm"><Link to={inventoryPath()}><X /> Quitar filtro</Link></Button>
              </div>
            )}
            {!visible.length ? (
              <Empty title={needle || filter ? "No hay coincidencias" : "Todavía no hay opciones con inventario"}
                description={needle || filter ? "Probá otra búsqueda o cambiá el filtro." : "Agregá una opción de venta a una publicación para cargarle stock."}>
                {selectedListing && <Button asChild variant="outline"><Link to={sellerListingsPath + "/" + selectedListing.id}>Editar publicación</Link></Button>}
              </Empty>
            ) : inventory ? (
              <div className="space-y-3">
                {visible.map((listing) => (
                  <article key={listing.id} className="overflow-hidden rounded-xl border bg-card">
                    <header className="flex items-center gap-3 border-b bg-muted/40 px-4 py-3 sm:px-5">
                      <ProductThumbnail url={listing.thumbnailUrl} />
                      <div className="min-w-0 space-y-1">
                        <h2 className="font-semibold [overflow-wrap:anywhere]">{listing.title}</h2>
                        <ListingStatusBadge status={listing.status} />
                      </div>
                    </header>
                    <ul className="divide-y">
                      {listing.skus.map((sku) => (
                        <li key={sku.id} className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-3 px-4 py-4 sm:grid-cols-[minmax(0,1.4fr)_minmax(7.5rem,.75fr)_auto] sm:px-5">
                          <div className="col-span-2 min-w-0 sm:col-span-1">
                            <p className="font-medium [overflow-wrap:anywhere]">{combination(sku)}</p>
                            <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]">Código {sku.code}</p>
                          </div>
                          <StockTotal units={sku.availableStock} />
                          <Button type="button" className="min-h-11 w-fit"
                            aria-label={"Ajustar stock de " + sku.code} onClick={(event) => {
                              trigger.current = event.currentTarget;
                              setRoute((current) => {
                                const next = new URLSearchParams(current);
                                next.set("sku", sku.id);
                                return next;
                              }, { replace: true });
                            }}>
                            <SlidersHorizontal /> Ajustar stock
                          </Button>
                        </li>
                      ))}
                    </ul>
                  </article>
                ))}
              </div>
            ) : (
              <ul className="divide-y overflow-hidden rounded-xl border bg-card">
                {visible.map((listing) => (
                  <li key={listing.id} className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-3 px-4 py-4 sm:grid-cols-[minmax(0,1.35fr)_minmax(7.5rem,.72fr)_auto] sm:px-5">
                    <div className="col-span-2 flex min-w-0 items-center gap-3 sm:col-span-1">
                      <ProductThumbnail url={listing.thumbnailUrl} />
                      <div className="min-w-0 space-y-1">
                        <Link to={sellerListingsPath + "/" + listing.id}
                          className="font-semibold text-foreground underline-offset-4 [overflow-wrap:anywhere] hover:text-primary hover:underline">{listing.title}</Link>
                        <div><ListingStatusBadge status={listing.status} /></div>
                      </div>
                    </div>
                    <StockTotal units={totalStock(listing)} configured={listing.skus.length > 0} />
                    <div className="flex flex-wrap justify-end gap-2 max-sm:flex-col max-sm:items-stretch">
                      {listing.skus.length > 0 && <Button asChild className="min-h-10">
                        <Link to={inventoryPath(listing.id, listing.skus.length === 1 ? listing.skus[0].id : undefined)}>
                          <Boxes /> Ajustar stock
                        </Link>
                      </Button>}
                      <Button asChild variant="outline" className="min-h-10">
                        <Link to={sellerListingsPath + "/" + listing.id}><Pencil /> Editar</Link>
                      </Button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </>
      )}
      <Dialog.Root open={Boolean(adjusting && selectedSku)} onOpenChange={(open) => { if (!open) closeAdjustment(); }}>
        {adjusting && selectedSku && (
          <Dialog.Portal>
            <Dialog.Overlay className="fixed inset-0 z-40 bg-slate-950/50" />
            <Dialog.Content onOpenAutoFocus={(event) => {
              event.preventDefault();
              heading.current?.focus();
            }} onCloseAutoFocus={(event) => {
              event.preventDefault();
              if (trigger.current?.isConnected) trigger.current.focus();
              else search.current?.focus();
              trigger.current = null;
            }} className="fixed inset-y-0 right-0 z-50 w-full max-w-lg overflow-y-auto border-l bg-background shadow-2xl focus:outline-none">
              <header className="sticky top-0 z-10 flex items-start justify-between gap-3 border-b bg-card px-5 py-4 sm:px-6">
                <div className="min-w-0 space-y-1">
                  <Dialog.Title ref={heading} tabIndex={-1} className="text-xl font-semibold focus:outline-none">Ajustar stock</Dialog.Title>
                  <Dialog.Description className="text-sm text-muted-foreground [overflow-wrap:anywhere]">
                    {adjusting.title} · {combination(selectedSku)} · {selectedSku.code}
                  </Dialog.Description>
                </div>
                <Dialog.Close asChild>
                  <Button type="button" variant="ghost" size="icon" aria-label="Cerrar ajuste"><X /></Button>
                </Dialog.Close>
              </header>
              <div className="p-5 sm:p-6">
                <StockEditor key={selectedSku.id} sku={selectedSku} revision={revision}
                  saved={(movement) => stockSaved(adjusting, selectedSku, movement)} reload={() => reloadStock(adjusting, selectedSku)} />
              </div>
            </Dialog.Content>
          </Dialog.Portal>
        )}
      </Dialog.Root>
      {catalog.isSuccess && selectedSkuId && !selectedSku && (
        <p role="alert" className="rounded-xl border bg-card p-4">La opción de stock ya no está disponible.{" "}
          <Link to={inventoryPath(selectedListingId ?? undefined)} className="inline-flex items-center gap-1 font-medium text-primary underline">
            Volver al inventario <ArrowRight size={15} aria-hidden="true" />
          </Link>
        </p>
      )}
    </div>
  );
}

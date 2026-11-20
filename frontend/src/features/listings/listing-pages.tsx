import { Boxes, Camera, FileText, Package, ScanEye } from "lucide-react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Tabs } from "radix-ui";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { Link, Navigate, useNavigate, useParams } from "react-router-dom";
import { profileLink } from "@/features/auth/profile-fields";
import { getSessionRevision, subscribeToSession } from "@/features/auth/session-api";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import { fetchListing, fetchListingCategories,
  type Listing, type ListingCategory, type ListingImage, type Sku } from "./listing-api";
import { ListingImageEditor } from "./listing-image-editor";
import { ListingPreview } from "./listing-preview";
import {
  inventoryPath, listingCollectionKey, listingDetailKey, ListingStatusBadge, SellerHeading,
  SellerNavigation, sellerListingsPath,
} from "./seller-workspace";
import { attributeEntries, attributeLabel, PublicationForm, SkuForm } from "./listing-forms";

const tabClass = "flex min-h-11 min-w-0 items-center justify-center gap-1.5 rounded-lg px-2 text-sm font-semibold text-muted-foreground hover:bg-muted data-[state=active]:bg-secondary data-[state=active]:text-accent-foreground sm:px-4";

function LoadError({ error, retry, detail = false }: { error: unknown; retry: () => void; detail?: boolean }) {
  if (error instanceof ApiError) {
    if (error.status === 401) return <Navigate to="/ingresar" replace />;
    if (error.status === 403) {
      return <p role="alert">Activá tu perfil de vendedor desde <Link className={profileLink} to="/cuenta">Mi cuenta</Link>.</p>;
    }
    if (error.status === 404 && detail) return <p role="alert">No encontramos esa publicación.</p>;
  }
  return (
    <div className="space-y-3">
      <p role="alert" className="text-destructive">No pudimos cargar las publicaciones o sus categorías.</p>
      <Button type="button" variant="outline" onClick={retry}>Reintentar</Button>
    </div>
  );
}

export function ListingEditorPage() {
  const { listingId } = useParams();
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);
  const [editorRevision, setEditorRevision] = useState(0);
  const [reloading, setReloading] = useState(false);
  const tabs = useRef<HTMLDivElement>(null);
  const imagesTab = useRef<HTMLButtonElement>(null);
  const [activeTab, setActiveTab] = useState("ficha");
  const cache = useQueryClient();
  const navigate = useNavigate();
  const categories = useQuery({
    queryKey: ["private", "listing-categories", revision],
    queryFn: ({ signal }) => fetchListingCategories(signal), retry: false,
  });
  const detail = useQuery({
    queryKey: listingDetailKey(revision, listingId ?? ""),
    queryFn: ({ signal }) => fetchListing(listingId!, signal),
    enabled: Boolean(listingId), retry: false, refetchOnWindowFocus: false,
  });

  function listingSaved(listing: Listing) {
    cache.setQueryData(listingDetailKey(revision, listing.id), listing);
    void cache.invalidateQueries({ queryKey: listingCollectionKey(revision), exact: true });
    if (listing.slug) void cache.invalidateQueries({ queryKey: ["listing-public", listing.slug] });
    if (!listingId) void navigate(sellerListingsPath + "/" + listing.id, { replace: true });
  }

  function skuSaved(sku: Sku) {
    cache.setQueryData<Listing>(listingDetailKey(revision, listingId!), (current) => {
      if (!current) return current;
      const exists = current.skus.some(({ id }) => id === sku.id);
      return { ...current, skus: exists ? current.skus.map((item) => item.id === sku.id ? sku : item) : [...current.skus, sku] };
    });
    void cache.invalidateQueries({ queryKey: listingCollectionKey(revision), exact: true });
    if (detail.data?.slug) void cache.invalidateQueries({ queryKey: ["listing-public", detail.data.slug] });
  }

  function imagesChanged(images: ListingImage[]) {
    cache.setQueryData<Listing>(listingDetailKey(revision, listingId!), (current) => current && { ...current, images });
    if (detail.data?.slug) void cache.invalidateQueries({ queryKey: ["listing-public", detail.data.slug] });
    void cache.invalidateQueries({ queryKey: listingCollectionKey(revision), exact: true });
  }

  async function reload() {
    setReloading(true);
    try {
      const result = await detail.refetch();
      if (result.isSuccess) {
        setEditorRevision((previous) => previous + 1);
        tabs.current?.querySelector<HTMLElement>('[aria-selected="true"]')?.focus();
      }
    } finally {
      setReloading(false);
    }
  }

  const error = detail.error ?? categories.error;
  const listing = listingId ? detail.data : undefined;
  const loadingFreshDetail = Boolean(listingId && detail.isFetching && !detail.isFetchedAfterMount);
  if (error instanceof ApiError && error.status === 401) return <Navigate to="/ingresar" replace />;
  const category = categories.data?.find(({ id }) => id === listing?.categoryId);

  return (
    <div className="mx-auto max-w-5xl space-y-4">
      <SellerNavigation />
      <SellerHeading title={listing?.title ?? (listingId ? "Editar publicación" : "Nueva publicación")}
        description={listing ? undefined : "Guardá los datos del producto para agregar fotos y opciones de venta."}
        actions={<>
          {listing?.skus.length ? <Button asChild className="min-h-11 bg-white text-slate-900 hover:bg-slate-100">
            <Link to={inventoryPath(listing.id)}><Boxes /> Ver stock del producto</Link>
          </Button> : null}
          <Link className="inline-flex min-h-11 items-center px-2 text-sm font-medium text-slate-100 underline underline-offset-4"
            to={sellerListingsPath}>Volver a Publicaciones</Link>
        </>}>
        {listing && <ListingStatusBadge status={listing.status} />}
      </SellerHeading>
      {(categories.isPending || (listingId && detail.isPending) || loadingFreshDetail || reloading) && <p role="status">Cargando publicación…</p>}
      {error && <LoadError error={error} detail={Boolean(listingId)} retry={() => {
        if (categories.isError) void categories.refetch();
        if (detail.isError) void reload();
      }} />}
      {!error && !loadingFreshDetail && categories.data && (
        listingId ? listing && (
          <Tabs.Root value={activeTab} onValueChange={setActiveTab} key={listing.id + ":" + revision} className="space-y-4">
            <Tabs.List ref={tabs} aria-label="Editar publicación" className="grid w-full grid-cols-2 gap-1 rounded-xl border bg-card p-1.5 sm:grid-cols-4">
              <Tabs.Trigger className={tabClass} value="ficha"><FileText size={17} aria-hidden="true" />Producto</Tabs.Trigger>
              <Tabs.Trigger className={tabClass} value="variantes"><Package size={17} aria-hidden="true" />Opciones de venta</Tabs.Trigger>
              <Tabs.Trigger ref={imagesTab} className={tabClass} value="imagenes"><Camera size={17} aria-hidden="true" />Fotos</Tabs.Trigger>
              <Tabs.Trigger className={tabClass} value="previa"><ScanEye size={17} aria-hidden="true" />Vista previa</Tabs.Trigger>
            </Tabs.List>
            <Tabs.Content value="ficha" forceMount className="data-[state=inactive]:hidden">
              <PublicationForm key={editorRevision + ":" + listing.status} listing={listing} categories={categories.data} saved={listingSaved} reload={() => void reload()} />
            </Tabs.Content>
            <Tabs.Content value="variantes" forceMount className="data-[state=inactive]:hidden">
              <OptionsPanel key={editorRevision} listing={listing} category={category} saved={skuSaved} reload={() => void reload()} />
            </Tabs.Content>
            <Tabs.Content value="imagenes" forceMount className="data-[state=inactive]:hidden">
              <ListingImageEditor key={editorRevision} listingId={listing.id} images={listing.images} changed={imagesChanged} />
            </Tabs.Content>
            <Tabs.Content value="previa">
              <ListingPreview listing={listing} category={category} sessionRevision={revision} updated={listingSaved} editImages={() => {
                setActiveTab("imagenes");
                imagesTab.current?.focus();
              }} />
            </Tabs.Content>
          </Tabs.Root>
        ) : (
          categories.data.length
            ? <PublicationForm key={revision} categories={categories.data} saved={listingSaved} />
            : <p>No hay categorías disponibles para publicar.</p>
        )
      )}
    </div>
  );
}

function OptionsPanel({ listing, category, saved, reload }: {
  listing: Listing; category?: ListingCategory; saved: (sku: Sku) => void; reload: () => void;
}) {
  const [editing, setEditing] = useState<string>();
  const formHeading = useRef<HTMLHeadingElement>(null);
  const sku = listing.skus.find(({ id }) => id === editing);
  useEffect(() => {
    if (editing) formHeading.current?.focus();
  }, [editing]);

  return (
    <div className="space-y-4">
      <Card className="rounded-lg"><CardContent className="min-w-0 space-y-4 px-4 sm:px-6">
        <h2 className="text-lg font-semibold" id="variants-title">Opciones de venta guardadas</h2>
        <p className="text-sm text-muted-foreground">Usá una opción por combinación de características, como azul y talle M. Cada opción puede tener su propio precio.</p>
        {listing.skus.length === 0 ? <p>Todavía no agregaste opciones de venta.</p> : (
          <div className="overflow-x-auto rounded-md border" tabIndex={0} role="region" aria-labelledby="variants-title">
            <table className="w-full table-fixed text-left text-sm sm:table-auto">
              <thead className="border-b bg-muted">
                <tr>
                  <th scope="col" className="w-[44%] px-2 py-2 sm:w-auto sm:px-3">
                    <span className="sm:hidden">Opción</span>
                    <span className="hidden sm:inline">Código SKU</span>
                  </th>
                  <th scope="col" className="hidden px-3 py-2 sm:table-cell">Combinación</th>
                  <th scope="col" className="w-[34%] px-2 py-2 sm:w-auto sm:px-3">Precio (ARS)</th>
                  <th scope="col" className="hidden px-3 py-2 sm:table-cell">Stock disponible</th>
                  <th scope="col" className="px-1 py-2 sm:px-3">Acciones</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {listing.skus.map((item) => {
                  const combination = attributeEntries(item.attributes)
                    .map(([key, value]) => attributeLabel(key) + " " + value).join(" · ") || "Opción única";
                  return (
                    <tr key={item.id}>
                      <th scope="row" className="px-2 py-2 font-medium [overflow-wrap:anywhere] sm:px-3">
                        {item.code}
                        <span className="mt-1 block text-xs font-normal text-muted-foreground sm:hidden">{combination}</span>
                      </th>
                      <td className="hidden px-3 py-2 [overflow-wrap:anywhere] sm:table-cell">{combination}</td>
                      <td className="px-2 py-2 tabular-nums [overflow-wrap:anywhere] sm:px-3">
                        {item.price.replace(".", ",")}
                        <span className="mt-1 block text-xs text-muted-foreground sm:hidden">{item.availableStock} en stock</span>
                      </td>
                      <td className="hidden px-3 py-2 tabular-nums sm:table-cell">{item.availableStock}</td>
                      <td className="px-1 py-2 sm:px-3">
                        <div className="flex flex-col items-start gap-1 sm:flex-row sm:gap-2">
                          {category && <Button type="button" variant="outline" className="min-h-11 px-1.5 sm:px-4"
                            aria-label={"Editar " + item.code} onClick={() => editing === item.id ? formHeading.current?.focus() : setEditing(item.id)}>Editar</Button>}
                          <Button asChild variant="secondary" className="min-h-11 px-1.5 sm:px-4">
                            <Link aria-label={"Ajustar stock de " + item.code} to={inventoryPath(listing.id, item.id)}>Stock</Link>
                          </Button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </CardContent></Card>
      {category ? (
        <Card className="rounded-lg"><CardContent className="space-y-4 px-4 sm:px-6">
          <h2 ref={formHeading} tabIndex={-1} className="[overflow-wrap:anywhere] text-lg font-semibold">
            {sku ? "Editar " + sku.code : "Agregar opción de venta"}
          </h2>
          {sku && <Button type="button" variant="outline" onClick={() => {
            setEditing(undefined);
            formHeading.current?.focus();
          }}>Agregar otra opción</Button>}
          <SkuForm key={category.id + ":" + (sku?.id ?? "new")}
            listingId={listing.id} category={category} sku={sku} saved={saved} reload={reload} />
        </CardContent></Card>
      ) : <p role="alert">Elegí una categoría disponible en la ficha para editar las opciones de venta.</p>}
    </div>
  );
}

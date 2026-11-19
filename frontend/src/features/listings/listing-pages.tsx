import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Tabs } from "radix-ui";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { Link, Navigate, useNavigate, useParams } from "react-router-dom";
import { profileLink } from "@/features/auth/profile-fields";
import { getSessionRevision, subscribeToSession } from "@/features/auth/session-api";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import { fetchListing, fetchListingCategories, fetchListings, listingStatusLabel,
  type Listing, type ListingCategory, type ListingImage, type Sku } from "./listing-api";
import { ListingImageEditor } from "./listing-image-editor";
import { ListingPreview } from "./listing-preview";
import { attributeEntries, attributeLabel, PublicationForm, SkuForm } from "./listing-forms";

const base = "/vendedor/publicaciones";
const listKey = (revision: number) => ["private", "listings", revision] as const;
const detailKey = (revision: number, id: string) => [...listKey(revision), id] as const;
const tabClass = "min-h-11 rounded-md px-1.5 text-sm font-semibold sm:px-3 text-muted-foreground data-[state=active]:bg-primary data-[state=active]:text-primary-foreground";

function ListingHeader({ title, backTo = base, backLabel = "Volver a Publicaciones" }: {
  title: string; backTo?: string; backLabel?: string;
}) {
  return (
    <header className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1">
      <h1 className="break-words text-2xl font-semibold">{title}</h1>
      <Link className={profileLink} to={backTo}>{backLabel}</Link>
    </header>
  );
}

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

export function ListingsPage() {
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);
  const listings = useQuery({ queryKey: listKey(revision), queryFn: ({ signal }) => fetchListings(signal), retry: false });
  return (
    <div className="mx-auto max-w-3xl space-y-4">
      <ListingHeader title="Publicaciones" backTo="/vendedor" backLabel="Volver al panel vendedor" />
      {listings.isPending && <p role="status">Cargando publicaciones…</p>}
      {listings.isError && <LoadError error={listings.error} retry={() => void listings.refetch()} />}
      {listings.isSuccess && (
        <>
          <Button asChild className="min-h-11"><Link to={base + "/nueva"}>Crear publicación</Link></Button>
          {listings.data.length ? (
            <Card className="rounded-lg py-0">
              <ul className="divide-y divide-border">
                {listings.data.map((listing) => (
                  <li key={listing.id} className="flex min-w-0 flex-wrap items-center justify-between gap-x-4 px-4 py-2 sm:px-6">
                    <Link className={profileLink + " min-w-0 [overflow-wrap:anywhere] text-base font-medium"} to={base + "/" + listing.id}>
                      {listing.title}
                    </Link>
                    <span className="text-sm text-muted-foreground">{listingStatusLabel[listing.status]}</span>
                  </li>
                ))}
              </ul>
            </Card>
          ) : <p>Todavía no creaste publicaciones.</p>}
        </>
      )}
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
    queryKey: detailKey(revision, listingId ?? ""),
    queryFn: ({ signal }) => fetchListing(listingId!, signal),
    enabled: Boolean(listingId), retry: false, refetchOnWindowFocus: false,
  });

  function listingSaved(listing: Listing) {
    cache.setQueryData(detailKey(revision, listing.id), listing);
    void cache.invalidateQueries({ queryKey: listKey(revision), exact: true });
    if (listing.slug) void cache.invalidateQueries({ queryKey: ["listing-public", listing.slug] });
    if (!listingId) void navigate(base + "/" + listing.id, { replace: true });
  }

  function skuSaved(sku: Sku) {
    cache.setQueryData<Listing>(detailKey(revision, listingId!), (current) => {
      if (!current) return current;
      const exists = current.skus.some(({ id }) => id === sku.id);
      return { ...current, skus: exists ? current.skus.map((item) => item.id === sku.id ? sku : item) : [...current.skus, sku] };
    });
  }

  function imagesChanged(images: ListingImage[]) {
    cache.setQueryData<Listing>(detailKey(revision, listingId!), (current) => current && { ...current, images });
    if (detail.data?.slug) void cache.invalidateQueries({ queryKey: ["listing-public", detail.data.slug] });
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
  if (error instanceof ApiError && error.status === 401) return <Navigate to="/ingresar" replace />;
  const category = categories.data?.find(({ id }) => id === listing?.categoryId);

  return (
    <div className="mx-auto max-w-4xl space-y-4">
      <ListingHeader title={listing?.title ?? (listingId ? "Editar publicación" : "Nueva publicación")} />
      {listing && <p className="text-sm text-muted-foreground">{listingStatusLabel[listing.status]}</p>}
      {(categories.isPending || (listingId && detail.isPending) || reloading) && <p role="status">Cargando publicación…</p>}
      {error && <LoadError error={error} detail={Boolean(listingId)} retry={() => {
        if (categories.isError) void categories.refetch();
        if (detail.isError) void reload();
      }} />}
      {!error && categories.data && (
        listingId ? listing && (
          <Tabs.Root value={activeTab} onValueChange={setActiveTab} key={listing.id + ":" + revision} className="space-y-4">
            <Tabs.List ref={tabs} aria-label="Editar publicación" className="grid grid-cols-2 gap-1 rounded-lg border bg-card p-1 min-[365px]:grid-cols-4 sm:inline-flex">
              <Tabs.Trigger className={tabClass} value="ficha">Ficha</Tabs.Trigger>
              <Tabs.Trigger className={tabClass} value="variantes">Variantes</Tabs.Trigger>
              <Tabs.Trigger ref={imagesTab} className={tabClass} value="imagenes">Imágenes</Tabs.Trigger>
              <Tabs.Trigger className={tabClass} value="previa">Vista previa</Tabs.Trigger>
            </Tabs.List>
            <Tabs.Content value="ficha" forceMount className="data-[state=inactive]:hidden">
              <Card className="rounded-lg"><CardContent className="px-4 sm:px-6">
                <PublicationForm key={editorRevision + ":" + listing.status} listing={listing} categories={categories.data} saved={listingSaved} reload={() => void reload()} />
              </CardContent></Card>
            </Tabs.Content>
            <Tabs.Content value="variantes" forceMount className="data-[state=inactive]:hidden">
              <VariantsPanel key={editorRevision} listing={listing} category={category} saved={skuSaved} reload={() => void reload()} />
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
            ? <Card className="rounded-lg"><CardContent className="px-4 sm:px-6">
                <PublicationForm key={revision} categories={categories.data} saved={listingSaved} />
              </CardContent></Card>
            : <p>No hay categorías disponibles para publicar.</p>
        )
      )}
    </div>
  );
}

function VariantsPanel({ listing, category, saved, reload }: {
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
        <h2 className="text-lg font-semibold" id="variants-title">Variantes guardadas</h2>
        {listing.skus.length === 0 ? <p>Todavía no agregaste variantes.</p> : (
          <div className="overflow-x-auto rounded-md border" tabIndex={0} role="region" aria-labelledby="variants-title">
            <table className="w-full table-fixed text-left text-sm sm:table-auto">
              <thead className="border-b bg-muted">
                <tr>
                  <th scope="col" className="w-[44%] px-2 py-2 sm:w-auto sm:px-3">
                    <span className="sm:hidden">Variante</span>
                    <span className="hidden sm:inline">Código SKU</span>
                  </th>
                  <th scope="col" className="hidden px-3 py-2 sm:table-cell">Combinación</th>
                  <th scope="col" className="w-[34%] px-2 py-2 sm:w-auto sm:px-3">Precio (ARS)</th>
                  <th scope="col" className="hidden px-3 py-2 sm:table-cell">Stock disponible</th>
                  {category && <th scope="col" className="px-1 py-2 sm:px-3">Acción</th>}
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {listing.skus.map((item) => {
                  const combination = attributeEntries(item.attributes)
                    .map(([key, value]) => attributeLabel(key) + " " + value).join(" · ") || "Sin atributos";
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
                      {category && <td className="px-1 py-2 sm:px-3">
                        <Button type="button" variant="outline" className="min-h-11 px-1.5 sm:px-4"
                          aria-label={"Editar " + item.code}
                          onClick={() => editing === item.id ? formHeading.current?.focus() : setEditing(item.id)}>Editar</Button>
                      </td>}
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
            {sku ? "Editar " + sku.code : "Agregar variante"}
          </h2>
          {sku && <Button type="button" variant="outline" onClick={() => {
            setEditing(undefined);
            formHeading.current?.focus();
          }}>Volver a agregar variantes</Button>}
          <SkuForm key={category.id + ":" + (sku?.id ?? "new")}
            listingId={listing.id} category={category} sku={sku} saved={saved} reload={reload} />
        </CardContent></Card>
      ) : <p role="alert">Elegí una categoría disponible en la ficha para editar las variantes.</p>}
    </div>
  );
}

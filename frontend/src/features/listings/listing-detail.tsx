import { useQuery } from "@tanstack/react-query";
import { useId, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import { profileControl, profileLink } from "@/features/auth/profile-fields";
import { attributeEntries, attributeLabel } from "./listing-forms";
import { fetchPublicListing, type ListingImage, type PublicSku, type ListingDetail } from "./listing-api";

export function listingPublicPath(slug: string) {
  return "/publicaciones/" + encodeURIComponent(slug);
}

export function PublicListingPage() {
  const { slug = "" } = useParams();
  const detail = useQuery({
    queryKey: ["listing-public", slug],
    queryFn: ({ signal }) => fetchPublicListing(slug, signal),
    retry: false,
  });

  if (detail.isPending) return <p role="status">Cargando publicación…</p>;
  if (detail.isError) {
    return detail.error instanceof ApiError && detail.error.status === 404 ? (
      <div className="space-y-2">
        <h1 className="text-2xl font-semibold">Publicación no disponible</h1>
        <Link className={profileLink} to="/">Volver al inicio</Link>
      </div>
    ) : (
      <div className="space-y-3">
        <p role="alert" className="text-destructive">No pudimos cargar la publicación.</p>
        <Button type="button" variant="outline" onClick={() => void detail.refetch()}>Reintentar</Button>
      </div>
    );
  }
  return <ListingDetailView key={slug} detail={detail.data} />;
}

function ImageView({ image, title }: { image: ListingImage; title: string }) {
  const [failed, setFailed] = useState(false);
  return failed
    ? <p role="alert" className="self-center px-4 text-sm text-muted-foreground">No pudimos cargar esta imagen.</p>
    : <img className="h-full w-full object-contain" src={image.url} alt={image.alt || title}
        decoding="async" onError={() => setFailed(true)} />;
}

function Gallery({ images, title }: { images: ListingImage[]; title: string }) {
  const [selectedId, select] = useState<string>();
  const current = images.find(({ id }) => id === selectedId) ?? images[0];
  return (
    <section aria-label="Galería de imágenes" className="min-w-0 space-y-3">
      <div className="grid aspect-square place-items-center overflow-hidden rounded-lg border bg-muted">
        {current ? <ImageView key={current.id} image={current} title={title} />
          : <p className="text-sm text-muted-foreground">Todavía no hay imágenes.</p>}
      </div>
      {images.length > 1 && (
        <ul aria-label="Miniaturas" className="grid grid-cols-6 gap-2">
          {images.map((image, index) => (
            <li key={image.id} className="min-w-0">
              <button type="button" aria-label={"Ver imagen " + (index + 1) + (image.alt ? " · " + image.alt : "")}
                aria-pressed={current.id === image.id} onClick={() => select(image.id)}
                className="aspect-square min-h-11 w-full cursor-pointer overflow-hidden rounded-md border-2 bg-muted aria-pressed:border-primary">
                <img src={image.thumbnailUrl} alt="" loading="lazy" decoding="async" className="h-full w-full object-cover" />
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function formattedPrice(price: string) {
  const [whole, cents = "00"] = price.split(".");
  return "$ " + whole.replace(/\B(?=(\d{3})+(?!\d))/g, ".") + "," + cents;
}

function VariantPicker({ skus }: { skus: PublicSku[] }) {
  const id = useId();
  const [selectedId, select] = useState<string>();
  const selected = skus.find(({ id }) => id === selectedId) ?? skus.find((sku) => sku.available) ?? skus[0];
  if (!selected) return <p className="text-sm text-muted-foreground">Todavía no hay variantes.</p>;
  const hasAttributes = Object.keys(selected.attributes).length > 0;
  return (
    <div className="space-y-3">
      {hasAttributes && (
        <div>
          <label htmlFor={id} className="mb-1.5 block text-sm font-medium">Variante</label>
          <select id={id} className={profileControl} value={selected.id} onChange={(event) => select(event.currentTarget.value)}>
            {skus.map((sku) => (
              <option key={sku.id} value={sku.id}>
                {attributeEntries(sku.attributes).map(([key, value]) => attributeLabel(key) + " " + value).join(" · ")}
              </option>
            ))}
          </select>
        </div>
      )}
      <div aria-live="polite">
        <p className="break-words text-3xl font-semibold tabular-nums">{formattedPrice(selected.price)}</p>
        <p className={selected.available ? "text-success" : "text-muted-foreground"}>
          {selected.available ? "Disponible" : "Sin stock"}
        </p>
      </div>
    </div>
  );
}

type ViewData = Pick<ListingDetail, "title" | "description" | "images" | "skus" | "specs"> & {
  category?: Pick<ListingDetail["category"], "id" | "name">;
  seller?: ListingDetail["seller"];
};

export function ListingDetailView({ detail, preview = false }: { detail: ViewData; preview?: boolean }) {
  const Title = preview ? "h2" : "h1";
  const specs = Object.entries(detail.specs);
  return (
    <article className="mx-auto max-w-5xl space-y-5">
      {detail.category &&
        <Link className={profileLink + " text-sm"} to={"/categorias/" + detail.category.id}>{detail.category.name}</Link>}
      <div className="grid gap-6 md:grid-cols-[minmax(0,1.25fr)_minmax(0,1fr)] md:gap-10">
        <Gallery images={detail.images} title={detail.title} />
        <div className="min-w-0 space-y-5">
          <Title className="break-words text-2xl font-semibold sm:text-3xl">{detail.title}</Title>
          <VariantPicker skus={detail.skus} />
          {detail.seller && (
            <Card className="gap-2 rounded-lg py-4">
              <CardContent className="space-y-1 px-4">
                <h2 className="text-sm font-medium">Vendedor</h2>
                <Link className={profileLink + " break-words"} to={"/vendedores/" + detail.seller.slug}>
                  {detail.seller.displayName}
                </Link>
              </CardContent>
            </Card>
          )}
        </div>
      </div>
      <section className="space-y-2 border-t pt-5">
        <h2 className="text-lg font-semibold">Descripción</h2>
        <p className="whitespace-pre-wrap break-words text-muted-foreground">{detail.description}</p>
      </section>
      {specs.length > 0 && <section className="space-y-2">
        <h2 className="text-lg font-semibold">Especificaciones</h2>
        <dl className="divide-y rounded-lg border bg-card">
          {specs.map(([key, value]) => (
            <div key={key} className="grid gap-1 px-4 py-2 [overflow-wrap:anywhere] sm:grid-cols-2">
              <dt className="font-medium">{key}</dt>
              <dd className="text-muted-foreground">{typeof value === "string" ? value : JSON.stringify(value)}</dd>
            </div>
          ))}
        </dl>
      </section>}
    </article>
  );
}

import { useQuery } from "@tanstack/react-query";
import { ChevronRight, PackageCheck, Store } from "lucide-react";
import { useId, useState } from "react";
import { Link, useLocation, useParams } from "react-router-dom";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import { profileControl, profileLink } from "@/features/auth/profile-fields";
import { attributeEntries, attributeLabel } from "./listing-forms";
import { SpecificationValue } from "./specifications-editor";
import { fetchPublicListing, type ListingImage, type PublicSku, type ListingDetail } from "./listing-api";
import { formattedPrice } from "./public-listing-format";
import { ListingQuestions } from "./listing-questions";
import { AddToCart } from "@/features/cart/add-to-cart";
import { ReputationSummary } from "@/features/reviews/reputation";

export function PublicListingPage() {
  const { slug = "" } = useParams();
  const { state } = useLocation();
  const catalogSearch = typeof state?.catalogSearch === "string" ? state.catalogSearch : "";
  const catalog = { pathname: "/catalogo", search: catalogSearch };
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
        <Link className={profileLink} to={catalog}>Volver al catálogo</Link>
      </div>
    ) : (
      <div className="space-y-3">
        <p role="alert" className="text-destructive">No pudimos cargar la publicación.</p>
        <Button type="button" variant="outline" onClick={() => void detail.refetch()}>Reintentar</Button>
      </div>
    );
  }
  return <div className="mx-auto max-w-7xl space-y-4">
    <nav aria-label="Ubicación de la publicación" className="flex flex-wrap items-center gap-2 text-sm">
      <Link className={profileLink} to={catalog}>Volver al catálogo</Link>
      <ChevronRight aria-hidden="true" className="size-3.5 shrink-0 text-muted-foreground" />
      <Link className={profileLink} to={"/categorias/" + detail.data.category.id}>{detail.data.category.name}</Link>
    </nav>
    <ListingDetailView key={slug} detail={detail.data} purchase={{ slug, catalogSearch }} />
    <ListingQuestions key={detail.data.id} listingId={detail.data.id} />
  </div>;
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
      <div className="store-photo grid aspect-square place-items-center overflow-hidden rounded-panel bg-muted">
        {current ? <ImageView key={current.id} image={current} title={title} />
          : <p className="text-sm text-muted-foreground">Todavía no hay imágenes.</p>}
      </div>
      {images.length > 1 && (
        <ul aria-label="Miniaturas" className="grid grid-cols-4 gap-2 sm:grid-cols-6">
          {images.map((image, index) => (
            <li key={image.id} className="min-w-0">
              <button type="button" aria-label={"Ver imagen " + (index + 1) + (image.alt ? " · " + image.alt : "")}
                aria-pressed={current.id === image.id} onClick={() => select(image.id)}
                className="store-photo aspect-square min-h-11 w-full cursor-pointer overflow-hidden rounded-lg border-2 bg-muted aria-pressed:border-primary">
                <img src={image.thumbnailUrl} alt="" loading="lazy" decoding="async" className="h-full w-full object-cover" />
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

type Purchase = { slug: string; catalogSearch: string };

function VariantPicker({ skus, purchase }: { skus: PublicSku[]; purchase?: Purchase }) {
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
      <div aria-live="polite" className="space-y-2 border-y py-4">
        <p className="break-words font-wordmark text-3xl font-extrabold tabular-nums">{formattedPrice(selected.price)}</p>
        <p className={"flex items-center gap-2 text-sm font-semibold " + (selected.available ? "text-success" : "text-muted-foreground")}>
          <PackageCheck aria-hidden="true" className="size-4" />{selected.available ? "Disponible" : "Sin stock"}
        </p>
      </div>
      {purchase && <AddToCart skuId={selected.id} available={selected.available} {...purchase} />}
    </div>
  );
}

type ViewData = Pick<ListingDetail, "title" | "description" | "images" | "skus" | "specs"> & {
  category?: Pick<ListingDetail["category"], "id" | "name">;
  seller?: ListingDetail["seller"];
};

export function ListingDetailView({ detail, preview = false, purchase }: { detail: ViewData; preview?: boolean; purchase?: Purchase }) {
  const Title = preview ? "h2" : "h1";
  const specs = Object.entries(detail.specs);
  const seller = detail.seller;
  const supplemental = detail.images[1];
  return (
    <article className="mx-auto max-w-7xl space-y-5">
      {preview && detail.category &&
        <Link className={profileLink + " text-sm"} to={"/categorias/" + detail.category.id}>{detail.category.name}</Link>}
      <div className="grid gap-5 rounded-panel border bg-card p-4 shadow-panel sm:p-6 md:grid-cols-[minmax(0,1.45fr)_minmax(0,1fr)]">
        <Gallery images={detail.images} title={detail.title} />
        <div className="min-w-0 space-y-5">
          {seller && <p className="text-sm text-muted-foreground">Por <Link className={profileLink + " font-semibold"} to={"/vendedores/" + seller.slug}>{seller.displayName}</Link></p>}
          <Title className="break-words font-wordmark text-2xl font-bold tracking-tight sm:text-3xl">{detail.title}</Title>
          <VariantPicker skus={detail.skus} purchase={purchase} />
        </div>
      </div>
      {seller && <Card className="py-4">
        <CardContent className="flex flex-wrap items-center gap-4 px-4 sm:px-6">
          <Store aria-hidden="true" className="size-11 shrink-0 rounded-xl bg-accent p-3 text-primary" />
          <div className="min-w-0 flex-1">
            <h2 className="text-sm font-semibold">Vendedor</h2>
            <ReputationSummary {...seller} />
          </div>
          <Button asChild variant="outline"><Link to={"/vendedores/" + seller.slug}>Ver productos del vendedor</Link></Button>
        </CardContent>
      </Card>}
      <div className="space-y-5 rounded-panel border bg-card p-4 shadow-panel sm:p-6">
        {!preview && <nav aria-label="Información del producto" className="flex flex-wrap gap-4 border-b pb-3 text-sm font-semibold">
          <a href="#descripcion-publicacion" className={profileLink}>Descripción</a>
          {specs.length > 0 && <a href="#caracteristicas-publicacion" className={profileLink}>Características</a>}
          <a href="#preguntas" className={profileLink}>Preguntas y respuestas</a>
        </nav>}
        <section id="descripcion-publicacion" className="grid scroll-mt-32 items-start gap-5 md:grid-cols-2">
          <div className={supplemental ? "space-y-2" : "space-y-2 md:col-span-2"}>
            <h2 className="store-section-title">Descripción</h2>
            <p className="whitespace-pre-wrap break-words text-muted-foreground">{detail.description}</p>
          </div>
          {supplemental && <div className="store-photo grid aspect-[3/2] place-items-center overflow-hidden rounded-panel bg-muted">
            <img src={supplemental.url} alt="" loading="lazy" decoding="async" className="size-full object-cover" />
          </div>}
        </section>
        {specs.length > 0 && <section id="caracteristicas-publicacion" className="scroll-mt-32 space-y-2">
          <h2 className="store-section-title">Características</h2>
          <dl className="divide-y rounded-lg border bg-card">
            {specs.map(([key, value]) => (
              <div key={key} className="grid gap-1 px-4 py-2 [overflow-wrap:anywhere] sm:grid-cols-2">
                <dt className="font-medium">{key}</dt>
                <dd className="text-muted-foreground"><SpecificationValue value={value} /></dd>
              </div>
            ))}
          </dl>
        </section>}
      </div>
    </article>
  );
}

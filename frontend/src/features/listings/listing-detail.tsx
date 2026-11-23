import { useQuery } from "@tanstack/react-query";
import { ChevronRight, Minus, PackageCheck, Plus, ShieldCheck, Store, Truck } from "lucide-react";
import { useId, useState } from "react";
import { Link, useLocation, useParams } from "react-router-dom";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { profileControl, profileLink } from "@/features/auth/profile-fields";
import { attributeEntries, attributeLabel } from "./listing-forms";
import { SpecificationValue } from "./specifications-editor";
import { fetchPublicListing, type PublicSku, type ListingDetail } from "./listing-api";
import { formattedPrice, listingCondition, publicListingTitle } from "./public-listing-format";
import { ProductGallery } from "./product-gallery";
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
    <ListingDetailView key={slug} detail={detail.data} purchase={{ slug, catalogSearch, cartSku: state?.cartSku, cartQuantity: state?.cartQuantity }} />
    <ListingQuestions key={detail.data.id} listingId={detail.data.id} />
  </div>;
}

type Purchase = { slug: string; catalogSearch: string; cartSku?: unknown; cartQuantity?: unknown };

function VariantPicker({ skus, selected, select }: { skus: PublicSku[]; selected: PublicSku; select: (id: string) => void }) {
  const id = useId();
  if (skus.length < 2 && Object.keys(selected.attributes).length === 0) return null;
  return <div>
    <label htmlFor={id} className="mb-1.5 block text-sm font-semibold">Variante</label>
    <select id={id} className={profileControl + " text-sm"} value={selected.id} onChange={(event) => select(event.currentTarget.value)}>
      {skus.map((sku, index) => <option key={sku.id} value={sku.id}>
        {attributeEntries(sku.attributes).map(([key, value]) => attributeLabel(key) + " " + value).join(" · ")
          || `Opción ${index + 1}`}
      </option>)}
    </select>
  </div>;
}

function StockStatus({ available }: { available: boolean }) {
  return <p className={"flex items-center gap-2 text-sm font-semibold " + (available ? "text-success" : "text-muted-foreground")}>
    <PackageCheck aria-hidden="true" className="size-4" />{available ? "Disponible" : "Sin stock"}
  </p>;
}

function PurchaseBox({ selected, purchase }: { selected: PublicSku; purchase: Purchase }) {
  const initial = purchase.cartSku === selected.id && typeof purchase.cartQuantity === "number"
    && Number.isInteger(purchase.cartQuantity) && purchase.cartQuantity >= 1 && purchase.cartQuantity <= 99
    ? purchase.cartQuantity : 1;
  const [quantity, setQuantity] = useState(initial);

  return <section aria-label="Opciones de compra" className="space-y-4 rounded-[12px] border border-[#e5e7eb] bg-card p-5 shadow-panel">
    <div aria-live="polite"><StockStatus available={selected.available} /></div>
    <div role="group" aria-label="Cantidad" className="flex flex-wrap items-center justify-between gap-2">
      <span className="text-sm font-semibold">Cantidad</span>
      <div className="inline-flex items-center rounded-lg border border-input">
        <Button type="button" variant="ghost" size="icon-lg" aria-label="Quitar una unidad"
          className="size-11" disabled={quantity <= 1 || !selected.available} onClick={() => setQuantity(quantity - 1)}>
          <Minus aria-hidden="true" />
        </Button>
        <output aria-label="Cantidad seleccionada" className="min-w-7 text-center text-sm font-semibold tabular-nums">{quantity}</output>
        <Button type="button" variant="ghost" size="icon-lg" aria-label="Agregar una unidad"
          className="size-11" disabled={quantity >= 99 || !selected.available} onClick={() => setQuantity(quantity + 1)}>
          <Plus aria-hidden="true" />
        </Button>
      </div>
    </div>
    <AddToCart skuId={selected.id} slug={purchase.slug} catalogSearch={purchase.catalogSearch}
      available={selected.available} quantity={quantity} />
    <ul role="list" className="space-y-2 border-t pt-3 text-xs font-medium text-muted-foreground">
      <li className="flex items-center gap-2"><ShieldCheck aria-hidden="true" className="size-4 shrink-0 text-primary" />Compra protegida</li>
      <li className="flex items-center gap-2"><Truck aria-hidden="true" className="size-4 shrink-0 text-primary" />Envío con seguimiento</li>
    </ul>
  </section>;
}

type ViewData = Pick<ListingDetail, "title" | "description" | "images" | "skus" | "specs"> & {
  category?: Pick<ListingDetail["category"], "id" | "name">;
  seller?: ListingDetail["seller"];
};

export function ListingDetailView({ detail, preview = false, purchase }: { detail: ViewData; preview?: boolean; purchase?: Purchase }) {
  const Title = preview ? "h2" : "h1";
  const DetailsHeading = preview ? "h3" : "h2";
  const title = publicListingTitle(detail.title);
  const condition = listingCondition(detail.title, detail.specs);
  const specs = Object.entries(detail.specs).filter(([key]) => !condition || key.toLowerCase() !== "condición");
  const seller = detail.seller;
  const [selectedId, select] = useState(purchase?.cartSku);
  const selected = detail.skus.find(({ id }) => id === selectedId) ?? detail.skus.find((sku) => sku.available) ?? detail.skus[0];
  const [whole, cents] = selected ? formattedPrice(selected.price).split(",") : [];

  return <article className="space-y-3">
    {preview && detail.category &&
      <Link className={profileLink + " text-sm"} to={`/categorias/${detail.category.id}`}>{detail.category.name}</Link>}
    <div data-preview={preview || undefined} className="product-layout grid items-start gap-5 rounded-panel border bg-card p-4 shadow-panel sm:p-6">
      <ProductGallery images={detail.images} title={title} />
      <div className="product-intro min-w-0 space-y-4">
        {condition && <p className="w-fit rounded-full bg-accent px-2.5 py-1 text-xs font-semibold text-accent-foreground">{condition}</p>}
        <Title className="wrap-anywhere font-wordmark text-2xl font-bold leading-tight tracking-tight sm:text-3xl">{title}</Title>
        {selected ? <>
          <div aria-live="polite">
            <p className="wrap-anywhere font-wordmark text-4xl font-extrabold tracking-tight tabular-nums">
              {whole}<sup className="top-[-0.55em] ml-px text-[0.52em] font-semibold text-muted-foreground">,{cents}</sup>
            </p>
            {!purchase && <div className="mt-3"><StockStatus available={selected.available} /></div>}
          </div>
          <VariantPicker skus={detail.skus} selected={selected} select={select} />
        </> : <p className="text-sm text-muted-foreground">Todavía no hay variantes.</p>}
      </div>
      {((purchase && selected) || seller) && <aside aria-label="Compra y vendedor" className="product-purchase min-w-0 space-y-3">
        {purchase && selected && <PurchaseBox key={selected.id} selected={selected} purchase={purchase} />}
        {seller && <section aria-label="Vendedor" className="space-y-3 rounded-[12px] border border-[#e5e7eb] bg-card p-5 shadow-panel">
          <div className="flex items-start gap-3">
            <Store aria-hidden="true" className="size-9 shrink-0 rounded-lg bg-accent p-2.5 text-primary" />
            <div className="min-w-0">
              <p className="text-xs font-medium text-muted-foreground">Vendedor</p>
              <Link className="wrap-anywhere text-sm font-bold text-foreground hover:underline" to={"/vendedores/" + seller.slug}>{seller.displayName}</Link>
            </div>
          </div>
          <ReputationSummary {...seller} />
          <Button asChild variant="outline" className="min-h-11 w-full">
            <Link className="whitespace-normal text-center" to={`/catalogo?seller=${encodeURIComponent(seller.slug)}`}>Ver productos del vendedor</Link>
          </Button>
        </section>}
      </aside>}
      <section id="descripcion-publicacion" className="product-details min-w-0 scroll-mt-32 space-y-4 border-t pt-5">
        <DetailsHeading className="font-wordmark text-xl font-bold leading-tight tracking-tight">Descripción y detalles del producto</DetailsHeading>
        <p className="whitespace-pre-wrap wrap-anywhere text-sm leading-relaxed text-muted-foreground sm:text-base">{detail.description}</p>
        {specs.length > 0 && <dl className="divide-y overflow-hidden rounded-lg border">
          {specs.map(([key, value]) => <div key={key} className="grid min-w-0 gap-1 px-3 py-2 text-sm wrap-anywhere odd:bg-muted/40 sm:grid-cols-2">
            <dt className="font-semibold">{key}</dt>
            <dd className="min-w-0 text-muted-foreground"><SpecificationValue value={value} /></dd>
          </div>)}
        </dl>}
        {!preview && <a href="#preguntas" className={profileLink + " text-sm font-semibold"}>Preguntas y respuestas</a>}
      </section>
    </div>
  </article>;
}

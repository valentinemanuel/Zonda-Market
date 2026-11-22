import { ArrowRight, ArrowUpRight, Boxes, ChartNoAxesColumnIncreasing, LayoutList, MessageCircle, Package, Plus } from "lucide-react";
import { useQuery } from "@tanstack/react-query";
import { useSyncExternalStore } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import { inventoryPath, sellerListingsPath, sellerOrdersPath, sellerQuestionsPath, sellerSalesPath, SellerHeading } from "@/features/listings/seller-workspace";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { SellerReviews } from "@/features/reviews/seller-reviews";
import { ownSeller, publicSeller, type Seller } from "./profile-api";
import { profileLink } from "./profile-fields";
import { getSessionRevision, subscribeToSession } from "./session-api";

export function SellerPanelPage() {
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);
  const seller = useQuery({
    queryKey: ["private", "seller", revision],
    queryFn: ({ signal }) => ownSeller(signal),
    retry: false,
  });
  if (seller.error instanceof ApiError && seller.error.status === 401) {
    return <Navigate to="/ingresar" replace />;
  }

  return (
    <div className="mx-auto max-w-6xl space-y-5">
      <Link className={profileLink} to="/cuenta">Volver a Mi cuenta</Link>
      <SellerHeading title="Panel vendedor" description={seller.data?.displayName}
        actions={seller.data && <>
          <Button asChild className="min-h-11"><Link to={sellerListingsPath + "/nueva"}><Plus /> Crear publicación</Link></Button>
          <Button asChild className="min-h-11 bg-white text-slate-900 hover:bg-slate-100">
            <Link to={`/vendedores/${seller.data.slug}`}>Ver ficha pública <ArrowUpRight /></Link>
          </Button>
        </>} />
      {seller.isPending && <p role="status" className="rounded-xl border bg-card p-5 text-muted-foreground">Cargando perfil vendedor…</p>}
      {seller.isError && (seller.error instanceof ApiError && seller.error.status === 404 ? (
        <p role="alert" className="rounded-xl border bg-card p-5">Activá tu perfil de vendedor desde Mi cuenta.</p>
      ) : <SellerLoadError retry={seller.refetch} />)}
      {seller.data && (
        <section aria-label="Herramientas de venta" className="grid gap-4 md:grid-cols-2">
          <Link aria-label="Mis publicaciones" to={sellerListingsPath} className="group flex flex-wrap items-start gap-4 rounded-xl border bg-card p-5 transition-colors hover:border-primary/50 hover:bg-secondary/30 sm:p-6">
            <span className="grid size-12 place-items-center rounded-xl bg-secondary text-accent-foreground"><LayoutList size={23} aria-hidden="true" /></span>
            <span className="min-w-40 flex-1 space-y-1">
              <span className="block text-lg font-semibold">Mis publicaciones</span>
              <span className="block text-sm text-muted-foreground">Creá productos y revisá sus fotos, precios y estados.</span>
            </span>
            <ArrowRight className="text-primary transition-transform group-hover:translate-x-1" size={20} aria-hidden="true" />
          </Link>
          <Link aria-label="Inventario" to={inventoryPath()} className="group flex flex-wrap items-start gap-4 rounded-xl border bg-card p-5 transition-colors hover:border-primary/50 hover:bg-secondary/30 sm:p-6">
            <span className="grid size-12 place-items-center rounded-xl bg-orange-100 text-orange-800"><Boxes size={23} aria-hidden="true" /></span>
            <span className="min-w-40 flex-1 space-y-1">
              <span className="block text-lg font-semibold">Inventario</span>
              <span className="block text-sm text-muted-foreground">Consultá cuántas unidades tenés y registrá reposiciones o bajas.</span>
            </span>
            <ArrowRight className="text-primary transition-transform group-hover:translate-x-1" size={20} aria-hidden="true" />
          </Link>
          <Link aria-label="Preguntas" to={sellerQuestionsPath} className="group flex flex-wrap items-start gap-4 rounded-xl border bg-card p-5 transition-colors hover:border-primary/50 hover:bg-secondary/30 sm:p-6">
            <span className="grid size-12 place-items-center rounded-xl bg-secondary text-accent-foreground"><MessageCircle size={23} aria-hidden="true" /></span>
            <span className="min-w-40 flex-1 space-y-1">
              <span className="block text-lg font-semibold">Preguntas</span>
              <span className="block text-sm text-muted-foreground">Respondé las preguntas pendientes sobre tus publicaciones.</span>
            </span>
            <ArrowRight className="text-primary transition-transform group-hover:translate-x-1" size={20} aria-hidden="true" />
          </Link>
          <Link aria-label="Pedidos recibidos" to={sellerOrdersPath} className="group flex flex-wrap items-start gap-4 rounded-xl border bg-card p-5 transition-colors hover:border-primary/50 hover:bg-secondary/30 sm:p-6">
            <span className="grid size-12 place-items-center rounded-xl bg-secondary text-accent-foreground"><Package size={23} aria-hidden="true" /></span>
            <span className="min-w-40 flex-1 space-y-1">
              <span className="block text-lg font-semibold">Pedidos recibidos</span>
              <span className="block text-sm text-muted-foreground">Registrá despachos y consultá las entregas.</span>
            </span>
            <ArrowRight className="text-primary transition-transform group-hover:translate-x-1" size={20} aria-hidden="true" />
          </Link>
          <Link aria-label="Ventas y neto estimado" to={sellerSalesPath} className="group flex flex-wrap items-start gap-4 rounded-xl border bg-card p-5 transition-colors hover:border-primary/50 hover:bg-secondary/30 sm:p-6">
            <span className="grid size-12 place-items-center rounded-xl bg-secondary text-accent-foreground"><ChartNoAxesColumnIncreasing size={23} aria-hidden="true" /></span>
            <span className="min-w-40 flex-1 space-y-1">
              <span className="block text-lg font-semibold">Ventas y neto estimado</span>
              <span className="block text-sm text-muted-foreground">Consultá importes y actividad de tus subórdenes.</span>
            </span>
            <ArrowRight className="text-primary transition-transform group-hover:translate-x-1" size={20} aria-hidden="true" />
          </Link>
        </section>
      )}
    </div>
  );
}

export function PublicSellerPage() {
  const { slug = "" } = useParams();
  const seller = useQuery({
    queryKey: ["seller", slug],
    queryFn: ({ signal }) => publicSeller(slug, signal),
    retry: false,
  });
  if (seller.isPending) return <p role="status" className="text-muted-foreground">Cargando vendedor…</p>;
  if (seller.isError) {
    return seller.error instanceof ApiError && seller.error.status === 404
      ? <h1 className="text-2xl font-semibold">Vendedor no encontrado</h1>
      : <SellerLoadError retry={seller.refetch} />;
  }
  return <SellerCard seller={seller.data} />;
}

function SellerCard({ seller }: { seller: Seller }) {
  return (
    <article className="space-y-4">
      <div>
        <h1 className="break-words text-2xl font-semibold">{seller.displayName}</h1>
        <p className="mt-1 text-sm text-muted-foreground">{seller.type === "COMERCIO" ? "Comercio" : "Vendedor particular"}</p>
      </div>
      {seller.bio && <p className="whitespace-pre-wrap break-words">{seller.bio}</p>}
      <SellerReviews slug={seller.slug} reputation={seller} />
    </article>
  );
}

function SellerLoadError({ retry }: { retry: () => unknown }) {
  return (
    <div className="space-y-3">
      <p role="alert" className="text-destructive">No pudimos cargar el perfil de vendedor.</p>
      <Button type="button" onClick={() => void retry()}>Reintentar</Button>
    </div>
  );
}

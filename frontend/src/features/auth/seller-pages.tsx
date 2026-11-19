import { useQuery } from "@tanstack/react-query";
import { useSyncExternalStore } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
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
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">Panel vendedor</h1>
      {seller.isPending && <p role="status" className="text-muted-foreground">Cargando perfil vendedor…</p>}
      {seller.isError && (seller.error instanceof ApiError && seller.error.status === 404 ? (
        <p role="alert">Activá tu perfil de vendedor desde Mi cuenta.</p>
      ) : <SellerLoadError retry={seller.refetch} />)}
      {seller.data && (
        <>
          <p>El perfil de {seller.data.displayName} está activo.</p>
          <Link className={profileLink} to={`/vendedores/${seller.data.slug}`}>Ver ficha pública</Link>
        </>
      )}
      <div><Link className={profileLink} to="/cuenta">Volver a Mi cuenta</Link></div>
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
      <section className="border-t border-border pt-4" aria-labelledby="reputation-title">
        <h2 id="reputation-title" className="font-semibold">Reputación</h2>
        <p className="text-sm text-muted-foreground">
          {seller.reviewCount === 0 ? "Todavía no tiene reseñas." : `${seller.averageRating} de 5 · ${seller.reviewCount} reseñas`}
        </p>
      </section>
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

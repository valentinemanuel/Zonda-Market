import { Link } from "react-router-dom";
import { Button } from "@/shared/ui/button";
import { profileLink } from "@/features/auth/profile-fields";
import { cartEditError, signInRequired, useCart, useCartEdit, type Cart } from "./cart-api";

export function AddToCart({ skuId, slug, available, catalogSearch }: {
  skuId: string; slug: string; available: boolean; catalogSearch: string;
}) {
  const { query, revision } = useCart();
  return <AddAction key={revision + ":" + skuId} skuId={skuId} slug={slug}
    available={available} catalogSearch={catalogSearch} revision={revision} cart={query.data}
    loading={query.isPending} error={query.error} retry={() => void query.refetch()} />;
}

function AddAction({ skuId, slug, available, catalogSearch, revision, cart, loading, error, retry }: {
  skuId: string; slug: string; available: boolean; catalogSearch: string; revision: number;
  cart?: Cart; loading: boolean; error: unknown; retry: () => void;
}) {
  const edit = useCartEdit(revision);
  if (!available) return <Button type="button" disabled>Agregar al carrito</Button>;
  if (signInRequired(error) || signInRequired(edit.error)) {
    return <Button asChild>
      <Link to="/ingresar" state={{ cartListingSlug: slug, catalogSearch }}>Ingresá para agregar al carrito</Link>
    </Button>;
  }
  if (loading) return <p role="status" className="text-sm text-muted-foreground">Consultando carrito…</p>;
  if (error || !cart) return <div className="space-y-2">
    <p role="alert" className="text-sm text-destructive">No pudimos consultar tu carrito.</p>
    <Button type="button" variant="outline" onClick={retry}>Reintentar carrito</Button>
  </div>;

  const item = cart.groups.flatMap((group) => group.items).find((item) => item.skuId === skuId)
    ?? cart.unavailable.find((item) => item.skuId === skuId);
  const quantity = item?.quantity ?? 0;
  return <div className="space-y-2">
    <Button type="button" disabled={edit.isPending || item?.reserved || quantity >= 99}
      onClick={() => edit.mutate({ kind: "put", skuId, quantity: quantity + 1, expectedVersion: cart.version })}>
      {edit.isPending ? "Agregando…" : "Agregar al carrito"}
    </Button>
    {item?.reserved && <p className="text-sm text-muted-foreground">Esta variante está incluida en tu reserva.</p>}
    {quantity >= 99 && <p className="text-sm text-muted-foreground">Alcanzaste el máximo de 99 unidades de esta variante.</p>}
    {edit.isSuccess && <p role="status" className="text-sm text-success">Agregaste una unidad al carrito.</p>}
    {edit.isError && <p role="alert" className="text-sm text-destructive">{cartEditError(edit.error)}</p>}
    {quantity > 0 && <Link className={profileLink + " block w-fit text-sm"} to="/carrito">Ver carrito</Link>}
  </div>;
}

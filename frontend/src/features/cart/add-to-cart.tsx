import { ShoppingCart, Zap } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router-dom";
import { Button } from "@/shared/ui/button";
import { checkoutPath } from "@/features/checkout/checkout-api";
import { profileLink } from "@/features/auth/profile-fields";
import { cartEditError, findCartItem, signInRequired, useCart, useCartEdit, type Cart } from "./cart-api";

export function AddToCart({ skuId, slug, available, catalogSearch, quantity }: {
  skuId: string; slug: string; available: boolean; catalogSearch: string; quantity: number;
}) {
  const { query, revision } = useCart();
  return <div className="space-y-3">
    {available ? <Button asChild className="min-h-11 w-full">
      <Link to={checkoutPath(skuId, quantity)}><Zap aria-hidden="true" />Comprar ahora</Link>
    </Button> : <Button type="button" className="min-h-11 w-full" disabled>Comprar ahora</Button>}
    <AddAction key={revision + ":" + skuId} skuId={skuId} slug={slug}
      available={available} catalogSearch={catalogSearch} revision={revision} cart={query.data}
      quantity={quantity}
      loading={query.isPending} error={query.error} retry={() => void query.refetch()} />
  </div>;
}

function AddAction({ skuId, slug, available, catalogSearch, revision, cart, loading, error, retry, quantity }: {
  skuId: string; slug: string; available: boolean; catalogSearch: string; revision: number;
  cart?: Cart; loading: boolean; error: unknown; retry: () => void; quantity: number;
}) {
  const edit = useCartEdit(revision);
  const [added, setAdded] = useState(0);
  if (!available) return <Button type="button" variant="outline" className="min-h-11 w-full" disabled>Agregar al carrito</Button>;
  if (signInRequired(error) || signInRequired(edit.error)) {
    return <Button asChild variant="outline" className="min-h-11 w-full">
      <Link to="/ingresar" className="whitespace-normal text-center"
        state={{ cartListingSlug: slug, catalogSearch, cartSku: skuId, cartQuantity: quantity }}>Ingresá para agregar al carrito</Link>
    </Button>;
  }
  if (loading) return <p role="status" className="text-sm text-muted-foreground">Consultando carrito…</p>;
  if (error || !cart) return <div className="space-y-2">
    <p role="alert" className="text-sm text-destructive">No pudimos consultar tu carrito.</p>
    <Button type="button" variant="outline" onClick={retry}>Reintentar carrito</Button>
  </div>;

  const item = findCartItem(cart, skuId);
  const current = item?.quantity ?? 0;
  const total = current + quantity;
  return <div className="space-y-2">
    <Button type="button" variant="outline" className="min-h-11 w-full" disabled={edit.isPending || item?.reserved || total > 99}
      onClick={() => edit.mutate({ kind: "put", skuId, quantity: total, expectedVersion: cart.version }, {
        onSuccess: () => setAdded(quantity),
      })}>
      {edit.isPending ? "Agregando…" : "Agregar al carrito"}
      <ShoppingCart aria-hidden="true" />
    </Button>
    {item?.reserved && <p className="text-sm text-muted-foreground">Esta variante está incluida en tu reserva.</p>}
    {total > 99 && <p className="text-sm text-muted-foreground">El carrito admite hasta 99 unidades de esta variante.</p>}
    {edit.isSuccess && <p role="status" className="text-sm text-success">
      {added === 1 ? "Agregaste una unidad al carrito." : `Agregaste ${added} unidades al carrito.`}
    </p>}
    {edit.isError && <p role="alert" className="text-sm text-destructive">{cartEditError(edit.error)}</p>}
    {current > 0 && <Link className={profileLink + " w-fit text-sm"} to="/carrito">Ver carrito</Link>}
  </div>;
}

import { ArrowLeft, ArrowRight, Minus, Package, Plus, ShoppingBag, ShoppingCart, Store, Trash2 } from "lucide-react";
import { useId, useRef, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { profileControl, profileLink } from "@/features/auth/profile-fields";
import { attributeEntries, attributeLabel } from "@/features/listings/listing-forms";
import { formattedPrice, listingPublicPath } from "@/features/listings/public-listing-format";
import { cn } from "@/shared/lib/utils";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import { cartEditError, signInRequired, useCart, useCartEdit, type Cart, type CartItem, type UnavailableReason } from "./cart-api";

const reasonText: Record<UnavailableReason, string> = {
  LISTING_UNAVAILABLE: "La publicación ya no está disponible.",
  SKU_UNAVAILABLE: "Esta variante ya no está disponible.",
  INSUFFICIENT_STOCK: "No hay stock suficiente para esta cantidad.",
  SELF_PURCHASE: "Esta publicación te pertenece.",
};

export function CartPage() {
  const { query, revision } = useCart();
  const heading = useRef<HTMLHeadingElement>(null);
  return <div className="mx-auto max-w-7xl space-y-5">
    <header className="workspace-heading flex flex-wrap items-center justify-between gap-3 p-5 sm:p-7">
      <div className="flex min-w-0 items-center gap-3">
        <ShoppingCart aria-hidden="true" className="size-11 shrink-0 rounded-xl border border-white/15 bg-white/10 p-2.5 text-brand-gold" />
        <div>
          <h1 ref={heading} tabIndex={-1} className="font-wordmark text-3xl font-extrabold focus-visible:outline-brand-gold">Mi carrito</h1>
          {query.data && <p className="text-sm text-hero-muted">{query.data.itemCount === 1 ? "1 unidad" : query.data.itemCount + " unidades"}</p>}
        </div>
      </div>
      <Button asChild variant="outline" className="min-h-11 border-white/20 bg-white/10 text-white hover:bg-white/20 hover:text-white focus-visible:outline-brand-gold">
        <Link to="/catalogo"><ArrowLeft aria-hidden="true" />Seguir comprando</Link>
      </Button>
    </header>
    {query.isPending ? <p role="status">Cargando carrito…</p>
      : signInRequired(query.error) ? <div className="space-y-3">
        <p>Ingresá para ver tu carrito.</p>
        <Button asChild><Link to="/ingresar" state={{ returnToCart: true }}>Iniciar sesión</Link></Button>
      </div>
      : query.isError ? <div className="space-y-3">
        <p role="alert" className="text-destructive">No pudimos cargar el carrito.</p>
        <Button type="button" variant="outline" onClick={() => void query.refetch()}>Reintentar</Button>
      </div>
      : <CartContent key={revision} cart={query.data} revision={revision} focusHeading={() => heading.current?.focus()} />}
  </div>;
}

function CartContent({ cart, revision, focusHeading }: { cart: Cart; revision: number; focusHeading: () => void }) {
  const edit = useCartEdit(revision);
  const unavailable = cart.unavailable.filter((item) => !item.reserved);
  const reserved = cart.unavailable.filter((item) => item.reserved);
  function remove(skuId: string) {
    edit.mutate({ kind: "remove", skuId, expectedVersion: cart.version }, { onSuccess: focusHeading });
  }
  const renderItem = (item: CartItem) => <CartLine key={item.skuId} item={item}
    busy={edit.isPending} save={(quantity) => edit.mutate({ kind: "put", skuId: item.skuId, quantity, expectedVersion: cart.version })}
    remove={() => remove(item.skuId)} />;
  return <>
    {edit.isPending && <p role="status">Actualizando carrito…</p>}
    {edit.isError && <p role="alert" className="text-destructive">{cartEditError(edit.error)}</p>}
    {edit.isSuccess && <p role="status" className="text-success">
      {edit.variables?.kind === "remove" ? "Producto quitado del carrito." : "Cantidad actualizada."}
    </p>}
    {cart.itemCount === 0 ? <Card>
      <CardContent className="grid justify-items-center gap-3 py-6">
        <ShoppingBag aria-hidden="true" className="size-12 rounded-xl bg-accent p-3 text-primary" />
        <p className="font-semibold">Tu carrito está vacío.</p>
        <Button asChild className="min-h-11"><Link to="/catalogo">Explorar productos <ArrowRight aria-hidden="true" /></Link></Button>
      </CardContent>
    </Card> : (
      <div className="grid items-start gap-5 md:grid-cols-[minmax(0,2fr)_minmax(14rem,1fr)]">
        <div className="space-y-4">
          {cart.groups.map((group) => (
            <Card key={group.sellerId} className="gap-0 py-4">
              <CardContent className="space-y-3 px-4 sm:px-6">
                <h2 className="flex items-center gap-3 border-b pb-2 text-lg font-semibold">
                  <Store aria-hidden="true" className="size-9 shrink-0 rounded-lg bg-accent p-2 text-primary" />
                  <Link className={profileLink} to={"/vendedores/" + group.sellerSlug}>{group.sellerName}</Link>
                </h2>
                <ul className="divide-y">{group.items.map(renderItem)}</ul>
                <p className="flex flex-wrap justify-between gap-x-3 border-t pt-3 font-medium tabular-nums">
                  Subtotal del vendedor <span>{formattedPrice(group.subtotal)}</span>
                </p>
              </CardContent>
            </Card>
          ))}
          {reserved.length > 0 && <Card className="gap-0 py-4">
            <CardContent className="space-y-2 px-4 sm:px-6">
              <h2 className="text-lg font-semibold">Productos reservados</h2>
              <ul className="divide-y">{reserved.map(renderItem)}</ul>
            </CardContent>
          </Card>}
          {unavailable.length > 0 && <Card className="gap-0 py-4">
            <CardContent className="space-y-2 px-4 sm:px-6">
              <h2 className="text-lg font-semibold">Productos no disponibles</h2>
              <ul className="divide-y">{unavailable.map(renderItem)}</ul>
            </CardContent>
          </Card>}
        </div>
        <Card className="gap-0 border-t-4 border-t-brand py-4 md:sticky md:top-28">
          <CardContent className="space-y-4 px-4 sm:px-6">
            <h2 className="border-b pb-3 font-wordmark text-lg font-bold">Resumen</h2>
            <p className="flex flex-wrap justify-between gap-x-3 text-lg font-semibold tabular-nums" aria-live="polite">
              Total <span>{formattedPrice(cart.total)}</span>
            </p>
            <p className="text-sm text-muted-foreground">El envío se calcula en el paso Envío.</p>
            {cart.unavailable.length > 0 && <p className="text-sm text-muted-foreground">El total incluye los productos disponibles.</p>}
            {cart.activeOrderId ? <Button className="min-h-11 w-full" asChild>
              <Link to={"/checkout/reserva/" + cart.activeOrderId}>Ver mi reserva</Link>
            </Button> : !unavailable.length && <Button className="min-h-11 w-full" asChild>
              <Link to="/checkout">Continuar al checkout <ArrowRight aria-hidden="true" /></Link>
            </Button>}
          </CardContent>
        </Card>
      </div>
    )}
  </>;
}

function CartLine({ item, busy, save, remove }: {
  item: CartItem; busy: boolean; save: (quantity: number) => void; remove: () => void;
}) {
  const inputId = useId();
  const [draft, setDraft] = useState({ original: item.quantity, value: String(item.quantity) });
  if (draft.original !== item.quantity) {
    setDraft({ original: item.quantity, value: String(item.quantity) });
  }
  const editable = !item.reserved && (item.reason === null || (item.reason === "INSUFFICIENT_STOCK" && item.availableStock > 0));
  const max = Math.min(99, item.availableStock);
  const attributes = attributeEntries(item.attributes).map(([name, value]) => attributeLabel(name) + " " + value).join(" · ");
  const priceChanged = item.unitPrice !== item.referencePrice;
  const [failedImage, setFailedImage] = useState<string | null>();
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!busy) save(Number(draft.value));
  }
  return <li className="grid grid-cols-[auto_minmax(0,1fr)] gap-3 py-3 sm:gap-4">
    <span className="store-photo grid size-12 place-items-center overflow-hidden rounded-lg bg-muted sm:size-20">
      {item.thumbnailUrl && item.thumbnailUrl !== failedImage
        ? <img src={item.thumbnailUrl} alt="" className="size-full object-cover" loading="lazy" onError={() => setFailedImage(item.thumbnailUrl)} />
        : <Package aria-hidden="true" className="size-5 text-muted-foreground" />}
    </span>
    <div className="min-w-0 space-y-2">
    <div className="flex flex-wrap justify-between gap-x-4 gap-y-1">
      {item.listingSlug ? <Link to={listingPublicPath(item.listingSlug)} className={profileLink + " break-words font-semibold"}>{item.title}</Link>
        : <p className="break-words font-semibold">{item.title}</p>}
      {item.subtotal !== null && <span className="font-semibold tabular-nums">{formattedPrice(item.subtotal)}</span>}
    </div>
    {attributes && <p className="break-words text-sm text-muted-foreground">{attributes}</p>}
    {(editable || item.reason === "INSUFFICIENT_STOCK") &&
      <p className="text-sm tabular-nums">{formattedPrice(item.unitPrice)} por unidad</p>}
    {priceChanged && (editable || item.reason === "INSUFFICIENT_STOCK") && <p className="text-sm text-foreground">
      Cambió el precio por unidad. Antes {formattedPrice(item.referencePrice)}, ahora {formattedPrice(item.unitPrice)}.
    </p>}
    {item.reserved && <p className="text-sm text-success">Incluido en tu reserva.</p>}
    {item.reason && !item.reserved && <p className="text-sm text-destructive">
      {reasonText[item.reason]} {item.reason === "INSUFFICIENT_STOCK" && `Disponibles: ${item.availableStock}.`}
    </p>}
    <div className="flex flex-wrap items-end gap-2">
      {editable ? <form className="flex flex-wrap items-end gap-2" onSubmit={submit}>
        <div className="flex gap-1">
          <Button type="button" variant="outline" size="icon" className="size-10" disabled={busy || item.quantity <= 1}
            aria-label={"Reducir cantidad de " + item.title} onClick={() => save(item.quantity - 1)}><Minus aria-hidden="true" /></Button>
          <Button type="button" variant="outline" size="icon" className="size-10" disabled={busy || item.quantity >= max}
            aria-label={"Aumentar cantidad de " + item.title} onClick={() => save(item.quantity + 1)}><Plus aria-hidden="true" /></Button>
        </div>
        <div>
          <label htmlFor={inputId} className="block text-sm font-medium">Cantidad <span className="sr-only">de {item.title} {attributes}</span></label>
          <input className={cn(profileControl, "w-20 tabular-nums")} id={inputId} type="number" inputMode="numeric"
            min={1} max={max} required readOnly={busy} value={draft.value}
            onChange={(event) => setDraft({ original: item.quantity, value: event.currentTarget.value })} />
        </div>
        <Button type="submit" variant="outline" aria-disabled={busy} className="min-h-11">Guardar</Button>
      </form> : <p className="self-center text-sm">Cantidad: {item.quantity}</p>}
      {!item.reserved && <Button type="button" variant="ghost" className="min-h-11" aria-disabled={busy}
        aria-label={`Quitar ${item.title}${attributes ? " " + attributes : ""}`} onClick={() => { if (!busy) remove(); }}>
        <Trash2 aria-hidden="true" />Quitar
      </Button>}
    </div>
    </div>
  </li>;
}

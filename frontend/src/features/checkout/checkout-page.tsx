import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, LockKeyhole, MapPin } from "lucide-react";
import { useId, useState, type FormEvent } from "react";
import { Link, Navigate, useNavigate, useSearchParams } from "react-router-dom";
import { getAddresses, type Address } from "@/features/auth/profile-api";
import { profileControl, profileLink } from "@/features/auth/profile-fields";
import { currentAccount, getSessionRevision } from "@/features/auth/session-api";
import { signInRequired, useCart, type Cart } from "@/features/cart/cart-api";
import { formattedPrice } from "@/features/listings/public-listing-format";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import { activeOrder, activeOrderKey, checkoutError, confirmCheckout, fetchDirectPreview, orderKey, ordersKey, quoteCheckout,
  reservationPath, type DirectPreview, type Quote } from "./checkout-api";
import { addressText, CheckoutHeading, CheckoutSummary, checkoutSurface } from "./checkout-summary";

export function CheckoutPage() {
  const [params] = useSearchParams();
  const sku = params.get("sku");
  const directSkuId = sku && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(sku) ? sku : undefined;
  const { query: cart, revision } = useCart();
  const direct = useQuery({
    queryKey: ["private", "checkout-item", revision, directSkuId],
    queryFn: ({ signal }) => fetchDirectPreview(directSkuId!, signal), enabled: !!directSkuId, retry: false,
  });
  const account = useQuery({
    queryKey: ["private", "me", revision], queryFn: ({ signal }) => currentAccount(signal), retry: false,
  });
  const addresses = useQuery({
    queryKey: ["private", "addresses", revision], queryFn: ({ signal }) => getAddresses(signal), retry: false,
  });
  const active = useQuery({
    queryKey: activeOrderKey(revision), queryFn: ({ signal }) => activeOrder(signal), retry: false,
  });
  const queries = [cart, account, addresses, active, ...(directSkuId ? [direct] : [])];
  const reload = () => { void Promise.all(queries.map((query) => query.refetch())); };
  const ready = (!directSkuId || direct.isSuccess) && cart.data && !(cart.data.activeOrderId && cart.isFetching)
    && account.data && addresses.data && active.isSuccess && !active.data;
  const differentReservation = directSkuId && active.data && active.data.directSkuId !== directSkuId ? active.data : null;

  return <div className="mx-auto max-w-7xl space-y-5">
    <Link className={profileLink + " gap-2 text-sm"} to="/carrito"><ArrowLeft aria-hidden="true" className="size-4" />Volver al carrito</Link>
    {(!ready || (sku !== null && !directSkuId)) && <CheckoutHeading title="Confirmar compra" step="Envío" />}
    {sku !== null && !directSkuId ? <p role="alert">No encontramos la variante de esta compra.</p>
      : differentReservation && !active.isFetching ? <Card className={checkoutSurface + " max-w-xl items-start gap-3 px-5"}>
          <p>Ya tenés otra reserva activa. Resolvela antes de comprar esta variante.</p>
          <Button asChild className="min-h-11"><Link to={reservationPath(differentReservation.id)}>Ver mi reserva activa</Link></Button>
        </Card>
      : active.data && !active.isFetching ? <Navigate to={reservationPath(active.data.id)} replace />
      : queries.some((query) => signInRequired(query.error)) ? <Button asChild>
          <Link to="/ingresar" state={{ returnToCheckout: true, checkoutSku: directSkuId }}>Iniciar sesión para comprar</Link>
        </Button>
      : queries.some((query) => query.isError) ? <div className="space-y-2">
          <p role="alert" className="text-destructive">{directSkuId && direct.isError
            ? checkoutError(direct.error) : "No pudimos preparar tu compra."}</p>
          <Button variant="outline" onClick={reload}>Reintentar</Button>
        </div>
      : ready && cart.data && account.data && addresses.data
        ? <CheckoutContent key={revision + ":" + (directSkuId ?? "")} revision={revision} cart={cart.data} verified={account.data.emailVerified}
            addresses={addresses.data} reload={reload} directSkuId={directSkuId} direct={direct.data} />
        : <p role="status">Preparando tu compra…</p>}
  </div>;
}

type ShippingProps = {
  revision: number; cart: Cart; verified: boolean; addresses: Address[]; reload: () => void;
  directSkuId?: string; direct?: DirectPreview;
};

function CheckoutContent({ verified, ...props }: ShippingProps) {
  const { direct, cart, addresses } = props;
  let notice;
  if (!verified) notice = <div className="space-y-2">
    <p>Verificá tu correo para confirmar la compra.</p>
    <Link className={profileLink} to="/cuenta">Ir a Mi cuenta</Link>
  </div>;
  else if (!direct && (!cart.itemCount || cart.unavailable.length)) notice = <p role="status">
    {cart.itemCount ? "Revisá los productos no disponibles en el carrito antes de continuar." : "Agregá productos al carrito para comprar."}
  </p>;
  else if (addresses.length === 0) notice = <div className="space-y-2">
    <p>Agregá una dirección de entrega para continuar.</p>
    <Link className={profileLink} to="/cuenta/direcciones">Administrar direcciones</Link>
  </div>;
  if (notice) return <CheckoutSummary groups={shippingGroups(cart, direct)} thumbnails={shippingImages(cart, direct)} showGroups={false}
    beforeGroups={<><CheckoutHeading title="Confirmar compra" step="Envío" />
      <Card className={checkoutSurface + " px-5"}>{notice}</Card></>} />;
  return <CheckoutForm {...props} />;
}

function shippingGroups(cart: Cart, direct?: DirectPreview) {
  if (direct) return direct.groups.map((group) => ({ ...group, shipping: null }));
  return cart.groups.map((group) => ({
    ...group,
    merchandise: group.subtotal,
    shipping: null,
    items: group.items.flatMap((item) => item.subtotal === null ? [] : [{ ...item, subtotal: item.subtotal }]),
  }));
}

function shippingImages(cart: Cart, direct?: DirectPreview) {
  return direct?.thumbnails ?? Object.fromEntries(cart.groups.flatMap((group) => group.items.flatMap((item) =>
    item.thumbnailUrl ? [[item.skuId, item.thumbnailUrl]] : [])));
}

function CheckoutForm({ revision, cart, addresses, reload, directSkuId, direct }: Omit<ShippingProps, "verified">) {
  const navigate = useNavigate();
  const client = useQueryClient();
  const addressInput = useId();
  const [chosen, setChosen] = useState<string>();
  const [quote, setQuote] = useState<Quote>();
  const address = addresses.find((item) => item.id === chosen) ?? addresses.find((item) => item.defaultAddress) ?? addresses[0];

  const requestQuote = useMutation({
    mutationFn: () => quoteCheckout(address.id, cart.version, directSkuId),
    onSuccess: (result) => {
      if (revision === getSessionRevision()) setQuote(result);
    },
    onError: (error) => {
      if (revision === getSessionRevision() && error instanceof ApiError && [404, 409].includes(error.status)) reload();
    },
  });
  const confirm = useMutation({
    mutationFn: (id: string) => confirmCheckout(id),
    onSuccess: (result) => {
      if (revision !== getSessionRevision()) return;
      client.setQueryData(orderKey(revision, result.id), result);
      void client.invalidateQueries({ queryKey: ["private", "cart", revision] });
      void client.invalidateQueries({ queryKey: activeOrderKey(revision) });
      void client.invalidateQueries({ queryKey: ordersKey(revision) });
      navigate(reservationPath(result.id));
    },
    onError: (error) => {
      if (revision !== getSessionRevision()) return;
      if (error instanceof ApiError && [404, 409].includes(error.status)) {
        setQuote(undefined);
        reload();
      }
    },
  });

  const currentQuote = quote?.address.id === address.id && quote.cartVersion === cart.version ? quote : undefined;
  const busy = requestQuote.isPending || confirm.isPending;
  const error = confirm.error ?? requestQuote.error;
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    confirm.reset();
    setQuote(undefined);
    requestQuote.mutate();
  }

  const groups = currentQuote?.groups ?? shippingGroups(cart, direct);
  return <CheckoutSummary groups={groups} total={currentQuote?.total} thumbnails={currentQuote?.thumbnails ?? shippingImages(cart, direct)}
    beforeGroups={<>
    <CheckoutHeading title="Confirmar compra" step="Envío" />
    <Card className={checkoutSurface + " gap-0 py-5"}>
      <CardContent className="space-y-3 px-4 sm:px-6">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="flex items-center gap-3 text-lg font-bold"><MapPin aria-hidden="true" className="size-10 rounded-xl bg-accent p-2.5 text-primary" />Dirección de entrega</h2>
          <Link className={profileLink + " text-sm"} to="/cuenta/direcciones">Cambiar o agregar</Link>
        </div>
        <form className="space-y-3" onSubmit={submit}>
          <label htmlFor={addressInput} className="block text-sm font-medium">Elegí una dirección</label>
          <select className={profileControl} id={addressInput} value={address.id} disabled={busy}
            onChange={(event) => {
              setChosen(event.currentTarget.value);
              setQuote(undefined);
              requestQuote.reset();
              confirm.reset();
            }}>
            {addresses.map((item) => <option value={item.id} key={item.id}>{addressText(item)}</option>)}
          </select>
          <Button type="submit" variant={currentQuote ? "outline" : "default"} className="min-h-11" disabled={busy}>
            {requestQuote.isPending ? "Cotizando…" : currentQuote ? "Volver a cotizar" : "Cotizar compra"}
          </Button>
        </form>
        {busy && <p role="status">{confirm.isPending ? "Confirmando la reserva…" : "Consultando importe y stock vigentes…"}</p>}
        {error && <p role="alert" className="text-destructive">{checkoutError(error)}</p>}
        {quote && !currentQuote && <p role="status">El carrito cambió. Volvé a cotizar.</p>}
      </CardContent>
    </Card></>
  }>
    {currentQuote && <>
        <p role="status" className="text-sm font-medium text-success">Cotización lista. El total es {formattedPrice(currentQuote.total)}.</p>
        <Button type="button" className="min-h-11 w-full" disabled={busy} onClick={() => confirm.mutate(currentQuote.id)}>
          <LockKeyhole aria-hidden="true" />
          {confirm.isPending ? "Reservando…" : "Confirmar reserva"}
        </Button>
    </>}
  </CheckoutSummary>;
}

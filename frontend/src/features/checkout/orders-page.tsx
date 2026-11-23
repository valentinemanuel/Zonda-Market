import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, ArrowRight, CalendarDays, Check, ChevronRight, Package, RotateCcw, Search, ShoppingBag, Truck } from "lucide-react";
import { useRef, useState, useSyncExternalStore } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { profileControl, profileLink } from "@/features/auth/profile-fields";
import { getSessionRevision, subscribeToSession } from "@/features/auth/session-api";
import { reorderError, signInRequired, useCartReorder } from "@/features/cart/cart-api";
import { formattedPrice } from "@/features/listings/public-listing-format";
import { Button } from "@/shared/ui/button";
import { Card } from "@/shared/ui/card";
import { fetchOrders, ordersKey, orderStatus, reservationPath, type OrderHistoryFilter, type OrderSummary } from "./checkout-api";

const dateFormat = new Intl.DateTimeFormat("es-AR", { dateStyle: "long", timeZone: "America/Argentina/Buenos_Aires" });
const filters = [
  { value: "", label: "Todas" },
  { value: "PAGADAS", label: "Pagadas" },
  { value: "PREPARANDO", label: "En preparación" },
  { value: "ENTREGADAS", label: "Entregadas" },
] as const;

const fulfillmentLabels = {
  PREPARANDO: "En preparación",
  DESPACHADA: "En camino",
  ENTREGADA: "Entregada",
};

export function OrdersPage() {
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);
  return <OrderHistory key={revision} revision={revision} />;
}

function OrderHistory({ revision }: { revision: number }) {
  const [params, setParams] = useSearchParams();
  const raw = Number(params.get("pagina"));
  const page = Number.isInteger(raw) && raw > 0 && raw <= 2147483647 ? raw : 0;
  const term = (params.get("q") ?? "").trim().slice(0, 160);
  const filter = filters.find(({ value }) => value === params.get("filtro"))?.value ?? "";
  const orders = useQuery({
    queryKey: [...ordersKey(revision), page, term, filter],
    queryFn: ({ signal }) => fetchOrders(page, term, filter, signal),
    retry: false,
  });
  const reorder = useCartReorder(revision);
  const retryKeys = useRef(new Map<string, string>());
  const signIn = signInRequired(orders.error);

  function update(nextTerm: string, nextFilter: OrderHistoryFilter | "", nextPage = 0) {
    const next = new URLSearchParams();
    if (nextTerm) next.set("q", nextTerm);
    if (nextFilter) next.set("filtro", nextFilter);
    if (nextPage) next.set("pagina", String(nextPage));
    setParams(next, { preventScrollReset: true });
  }

  function buyAgain(orderId: string) {
    if (reorder.isPending) return;
    const key = retryKeys.current.get(orderId) ?? crypto.randomUUID();
    retryKeys.current.set(orderId, key);
    reorder.mutate({ orderId, key }, { onSuccess: () => retryKeys.current.delete(orderId) });
  }

  return <div className="mx-auto max-w-5xl space-y-5">
    <Link className={profileLink + " gap-2 text-sm"} to="/cuenta"><ArrowLeft aria-hidden="true" className="size-4" />Volver a Mi cuenta</Link>
    <header className="workspace-heading flex items-center gap-4 p-4 sm:p-7">
      <span className="grid size-12 shrink-0 place-items-center rounded-xl border border-white/15 bg-white/10 text-brand-gold sm:size-14">
        <ShoppingBag aria-hidden="true" className="size-6" />
      </span>
      <div>
        <h1 className="font-wordmark text-3xl font-extrabold tracking-tight sm:text-4xl">Mis órdenes</h1>
        <p className="mt-1 text-sm text-hero-muted">Buscá compras y seguí tus envíos.</p>
      </div>
    </header>
    {!signIn && <OrderFilters term={term} filter={filter} update={update} />}
    {(term || filter) && <p role="status" className="sr-only">
      {orders.data && (orders.data.content.length === 1 ? "1 orden en esta página."
        : `${orders.data.content.length} órdenes en esta página.`)}
    </p>}
    {orders.isPending ? <Card className="px-5"><p role="status" className="text-sm text-muted-foreground">Cargando órdenes…</p></Card>
      : signIn ? <Card className="items-start px-5">
          <Button asChild className="min-h-11 whitespace-normal">
            <Link to="/ingresar" state={{ returnToOrders: true }}>Iniciar sesión para ver tus órdenes</Link>
          </Button>
        </Card>
      : orders.isError ? <Card className="items-start gap-3 px-5">
          <p role="alert" className="text-destructive">No pudimos cargar tus órdenes.</p>
          <Button type="button" variant="outline" onClick={() => void orders.refetch()}>Reintentar</Button>
        </Card>
      : <>
          {orders.data.content.length > 0 ? <ul role="list" className="space-y-4">
            {orders.data.content.map((order) => {
              const selected = reorder.variables?.orderId === order.id;
              return <li key={order.id}>
                <OrderCard order={order} buyAgain={() => buyAgain(order.id)} busy={reorder.isPending}
                  saving={selected && reorder.isPending} added={selected && reorder.isSuccess}
                  error={selected && reorder.isError ? reorderError(reorder.error) : null} />
              </li>;
            })}
          </ul> : page === 0 && !term && !filter ? <OrdersEmpty />
            : <OrdersEmpty filtered onReset={() => update("", "")} />}
          {(page > 0 || orders.data.hasNext) && <nav aria-label="Paginación de órdenes" className="flex flex-wrap items-center justify-center gap-3">
            <Button variant="outline" className="min-h-11" disabled={page === 0} onClick={() => update(term, filter, page - 1)}>Anterior</Button>
            <span className="text-sm tabular-nums text-muted-foreground">Página {page + 1}</span>
            <Button variant="outline" className="min-h-11" disabled={!orders.data.hasNext} onClick={() => update(term, filter, page + 1)}>Siguiente</Button>
          </nav>}
        </>}
  </div>;
}

function OrderFilters({ term, filter, update }: {
  term: string; filter: OrderHistoryFilter | "";
  update: (term: string, filter: OrderHistoryFilter | "") => void;
}) {
  const [edit, setEdit] = useState({ term, draft: term });
  if (edit.term !== term) setEdit({ term, draft: term });
  const draft = edit.term === term ? edit.draft : term;
  return <Card className="gap-3 p-4 sm:p-5">
    <form role="search" aria-label="Historial de compras" className="space-y-2"
      onSubmit={(event) => { event.preventDefault(); update(draft.trim(), filter); }}>
      <label htmlFor="orders-search" className="block text-sm font-semibold">Buscar en tus órdenes</label>
      <div className="flex flex-wrap gap-2">
        <div className="relative min-w-0 flex-1 basis-40">
          <Search aria-hidden="true" className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <input id="orders-search" type="search" name="q" maxLength={160} value={draft}
            onChange={(event) => setEdit({ term, draft: event.target.value })} placeholder="Producto o número de orden"
            className={profileControl + " min-h-11 ps-9"} />
        </div>
        <Button type="submit" variant="outline" className="min-h-11">Buscar</Button>
      </div>
    </form>
    <div role="group" aria-label="Filtrar por estado" className="flex flex-wrap gap-2">
      {filters.map(({ value, label }) => <Button key={value} type="button" variant="outline"
        aria-pressed={filter === value} onClick={() => update(term, value)}
        className="min-h-11 rounded-full border-input px-3.5 aria-pressed:border-primary aria-pressed:bg-accent aria-pressed:text-accent-foreground">
        {label}
      </Button>)}
    </div>
  </Card>;
}

type OrderCardProps = {
  order: OrderSummary; buyAgain: () => void; busy: boolean; saving: boolean; added: boolean; error: string | null;
};

function OrderCard({ order, buyAgain, busy, saving, added, error }: OrderCardProps) {
  const shortId = "#" + order.id.slice(0, 8);
  const orderLabel = "Orden " + shortId;
  const descriptionId = "orden-" + order.id;
  const title = order.previews[0]?.title ?? "Detalle de la orden";
  const extra = order.lineCount - order.previews.length;
  const paid = order.status === "PAGADA";
  const refundable = order.status === "REEMBOLSO_PARCIAL" || order.status === "REEMBOLSADA";
  const pending = order.status === "PAGO_PENDIENTE" || order.status === "RESERVADA";
  const color = paid ? "bg-emerald-100 text-emerald-900" : pending ? "bg-amber-100 text-amber-950"
    : order.status === "FALLIDA" ? "bg-red-100 text-red-900" : "bg-muted text-foreground";
  return <article aria-labelledby={descriptionId} className="rounded-panel border bg-card shadow-panel">
    <header className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 rounded-t-[inherit] border-b bg-muted/50 px-4 py-3 sm:px-5">
      <div className="grid gap-x-3 gap-y-1 text-sm sm:flex sm:flex-wrap sm:items-center">
        <time dateTime={order.createdAt} className="inline-flex items-center gap-2 font-semibold">
          <CalendarDays aria-hidden="true" className="size-4 text-muted-foreground" />{dateFormat.format(new Date(order.createdAt))}
        </time>
        <span id={descriptionId} className="text-muted-foreground">{orderLabel}</span>
      </div>
      <span className={"inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-bold " + color}>
        <span aria-hidden="true" className="size-1.5 rounded-full bg-current" />
        {orderStatus(order.status)}
      </span>
    </header>
    <div className="grid items-center gap-4 p-4 sm:p-5 lg:grid-cols-[minmax(0,1fr)_auto] lg:gap-6">
      <div className="grid min-w-0 items-center gap-3 sm:grid-cols-[auto_minmax(0,1fr)] sm:gap-4">
        <div className="flex flex-wrap gap-2" aria-label="Productos de la compra" role="group">
          {order.previews.map((product, index) => <OrderThumbnail
            key={index} url={product.thumbnailUrl} title={product.title} decorative={index === 0} />)}
          {extra > 0 && <span className="grid size-12 place-items-center rounded-lg bg-muted text-sm font-bold sm:size-14">
            <span aria-hidden="true">+{extra}</span><span className="sr-only">{extra} más</span>
          </span>}
        </div>
        <div className="min-w-0">
          <h2 className="wrap-anywhere font-wordmark text-lg leading-snug font-bold">{title}</h2>
          <p className="mt-1 text-xs font-medium text-muted-foreground">{order.units} {order.units === 1 ? "unidad" : "unidades"}</p>
          {order.fulfillment && <p className="mt-2 flex items-center gap-1.5 text-sm font-semibold">
            <Truck aria-hidden="true" className="size-4 text-primary" />{fulfillmentLabels[order.fulfillment]}
          </p>}
        </div>
      </div>
      <div className="grid items-center gap-3 border-t pt-4 lg:grid-cols-[auto_auto] lg:gap-5 lg:border-t-0 lg:border-s lg:ps-5 lg:pt-0">
        <div className="flex flex-wrap items-center justify-between gap-x-2 lg:block lg:text-right">
          <p className="text-xs font-medium text-muted-foreground">{paid ? "Total pagado" : refundable ? "Total original" : "Total de la orden"}</p>
          <p className="font-wordmark text-xl font-extrabold whitespace-nowrap tabular-nums">{formattedPrice(order.total)}</p>
        </div>
        <div className="grid gap-2 min-[360px]:grid-cols-2 lg:grid-cols-1">
          <Button asChild className="min-h-11">
            <Link aria-describedby={descriptionId} to={reservationPath(order.id)}>Ver detalle <ChevronRight aria-hidden="true" /></Link>
          </Button>
          {(paid || refundable) && <Button type="button" variant="outline" aria-describedby={descriptionId}
            aria-disabled={busy} className="min-h-11 aria-disabled:opacity-60" onClick={buyAgain}>
            <RotateCcw aria-hidden="true" />{saving ? "Agregando…" : "Volver a comprar"}
          </Button>}
        </div>
      </div>
    </div>
    {added && <div className="flex flex-wrap items-center gap-x-3 gap-y-1 border-t bg-emerald-50 px-4 py-3 text-sm text-emerald-900 sm:px-5">
      <p role="status" className="inline-flex items-center gap-2 font-semibold">
        <Check aria-hidden="true" className="size-4" />Productos agregados al carrito.
      </p>
      <Link to="/carrito" className="font-semibold underline underline-offset-4">Ver carrito</Link>
    </div>}
    {error && <p role="alert" className="border-t px-4 py-3 text-sm font-semibold text-destructive sm:px-5">{error}</p>}
  </article>;
}

function OrderThumbnail({ url, title, decorative }: { url: string | null; title: string; decorative: boolean }) {
  const [failed, setFailed] = useState(false);
  return <span className="grid size-12 shrink-0 place-items-center rounded-lg bg-muted text-muted-foreground sm:size-14">
    {url && !failed ? <img src={url} alt={decorative ? "" : title} loading="lazy" width={56} height={56}
      onError={() => setFailed(true)} className="size-full rounded-[inherit] object-cover outline-1 -outline-offset-1 outline-black/10" />
      : <><Package aria-hidden="true" className="size-5" />{!decorative && <span className="sr-only">{title}</span>}</>}
  </span>;
}

function OrdersEmpty({ filtered = false, onReset }: { filtered?: boolean; onReset?: () => void }) {
  const Icon = filtered ? Search : ShoppingBag;
  return <Card className="items-center gap-3 px-5 py-12 text-center">
    <span className="grid size-16 place-items-center rounded-2xl bg-accent text-primary">
      <Icon aria-hidden="true" className="size-8" strokeWidth={1.7} />
    </span>
    <h2 className="font-wordmark text-xl font-bold">{filtered ? "No encontramos órdenes con esos filtros" : "Aún no realizaste ninguna compra"}</h2>
    {filtered ? <Button type="button" variant="outline" className="min-h-11" onClick={onReset}>Ver todas las órdenes</Button>
      : <Button asChild className="min-h-11"><Link to="/catalogo">Ir al catálogo <ArrowRight aria-hidden="true" /></Link></Button>}
  </Card>;
}

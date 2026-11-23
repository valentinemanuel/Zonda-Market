import { useQuery } from "@tanstack/react-query";
import { ChevronDown, MapPin, Search, ShoppingCart, Store, UserRound } from "lucide-react";
import { useState } from "react";
import { Popover } from "radix-ui";
import { Form, Link, NavLink, Outlet, ScrollRestoration, useLocation } from "react-router-dom";
import { Card, CardContent } from "@/shared/ui/card";
import { getJson } from "@/shared/api/http-client";
import { useCart } from "@/features/cart/cart-api";
import { NotificationBell } from "@/features/notifications/notification-bell";
import { categoryCatalogPath, type CatalogCategoryNode } from "@/features/listings/storefront-categories";

function CartLink({ enabled }: { enabled: boolean }) {
  const { query } = useCart(enabled);
  const count = query.isSuccess ? query.data.itemCount : 0;
  return <Link to="/carrito" aria-label={count > 0 ? `Carrito, ${count} ${count === 1 ? "unidad" : "unidades"}` : "Carrito"}
    className="store-header-link relative min-w-10">
    <ShoppingCart aria-hidden="true" className="size-5" />
    {count > 0 && <span aria-hidden="true" className="absolute top-0.5 right-0 rounded-full bg-brand-ink px-1 text-[0.625rem] leading-4 text-white tabular-nums">
      {count > 99 ? "99+" : count}
    </span>}
  </Link>;
}

function CategoryNavigation() {
  const [open, setOpen] = useState(false);
  const { pathname, search } = useLocation();
  const selected = pathname === "/catalogo" ? new URLSearchParams(search).get("category") : null;
  const categories = useQuery({
    queryKey: ["catalog-categories"],
    queryFn: ({ signal }) => getJson("/api/v1/categories", signal) as Promise<CatalogCategoryNode[]>,
    staleTime: 30_000, retry: false,
  });
  const roots = categories.data?.map(({ category }) => category) ?? [];
  return <nav aria-label="Secciones de la tienda" className="store-frame flex min-h-9 flex-wrap items-center gap-x-4 border-t border-brand-ink/15 text-xs font-semibold lg:gap-x-6">
    <NavLink end to="/cuenta/direcciones" className="inline-flex min-h-9 items-center gap-1.5 rounded hover:underline aria-[current=page]:underline">
      <MapPin aria-hidden="true" className="size-3.5" /> Direcciones
    </NavLink>
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger className="flex min-h-9 cursor-pointer items-center gap-1 rounded hover:underline">
        Categorías <ChevronDown aria-hidden="true" className="size-3.5" />
      </Popover.Trigger>
      <Popover.Content align="start" aria-label="Categorías del catálogo" onOpenAutoFocus={(event) => event.preventDefault()}
        className="z-30 max-h-[var(--radix-popover-content-available-height)] w-52 overflow-y-auto overscroll-contain rounded-panel border bg-card p-1.5 text-sm text-foreground shadow-float">
        <Link to="/catalogo" className="block rounded-lg px-3 py-2 hover:bg-accent"
          onClick={() => setOpen(false)}>Todas las categorías</Link>
        {roots.map(({ id, name }) => <Link key={id} to={categoryCatalogPath(id)} aria-current={selected === id ? "page" : undefined}
          className="block rounded-lg px-3 py-2 hover:bg-accent aria-[current=page]:font-bold aria-[current=page]:underline"
          onClick={() => setOpen(false)}>{name}</Link>)}
      </Popover.Content>
    </Popover.Root>
    {roots.slice(0, 6).map(({ id, name }) => <Link key={id} to={categoryCatalogPath(id)} aria-current={selected === id ? "page" : undefined}
      className="hidden min-h-9 items-center rounded hover:underline aria-[current=page]:underline md:inline-flex">{name}</Link>)}
    <NavLink end to="/mis-ordenes" aria-label="Historial de compras" className="ml-auto inline-flex min-h-7 items-center rounded-full bg-brand-ink px-2.5 text-brand-gold hover:bg-hero-panel aria-[current=page]:underline">
      Mis órdenes
    </NavLink>
  </nav>;
}

export function SiteLayout() {
  const { pathname, search } = useLocation();
  // Identity flows manage their own refresh cookie, including the previous session in Google exchange.
  const identityFlow = /^\/(ingresar|registro|oauth\/retorno|recuperar-acceso|restablecer-clave|verificar-correo)\/?$/i.test(pathname);
  const catalog = /^\/catalogo\/?$/i.test(pathname);
  const catalogTerm = catalog ? new URLSearchParams(search).get("q") ?? "" : "";
  const storefront = catalog && !search;
  const listing = pathname.startsWith("/publicaciones/");
  const checkout = /^\/checkout(?:\/|$)/i.test(pathname);

  return <>
    <a href="#contenido"
      className="sr-only focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-50 focus:bg-white focus:p-2">Ir al contenido</a>
    <header className={`store-header relative z-20 text-brand-ink ${checkout ? "" : "md:sticky md:top-0"}`}>
      <div className="store-frame grid grid-cols-[auto_minmax(0,1fr)] items-center gap-x-3 gap-y-1 py-2 md:min-h-14 md:grid-cols-[auto_minmax(0,1fr)_auto] lg:gap-x-6">
        <Link to="/catalogo" aria-label="Zonda Market" className="inline-flex min-h-10 min-w-0 items-center gap-1.5 rounded-lg font-extrabold">
          <img src="/brand/zonda-isotipo.svg#color" alt="" width="30" height="30" className="size-7 shrink-0 rounded-lg bg-brand-ink p-0.5 sm:size-8" />
          <span className="whitespace-nowrap font-wordmark text-base leading-none tracking-[-0.035em] sm:text-xl">
            <span className="text-white [text-shadow:0_1px_2px_#791c13]">ZONDA</span><span>–MARKET</span>
          </span>
        </Link>
        <Form role="search" aria-label="Búsqueda de la tienda" action="/catalogo" method="get"
          className="col-span-2 row-start-2 flex min-w-0 items-center gap-1 rounded-lg bg-white p-1 shadow-panel md:col-span-1 md:row-auto">
          <input key={catalogTerm} name="q" type="search" aria-label="Buscar en la tienda" maxLength={160}
            defaultValue={catalogTerm} placeholder="Buscar productos, marcas y más…"
            className="min-h-8 min-w-0 flex-1 rounded-md bg-transparent px-2 text-sm text-foreground focus-visible:outline-offset-0" />
          <button type="submit" aria-label="Ejecutar búsqueda del catálogo"
            className="grid size-8 shrink-0 place-items-center rounded-md bg-brand-ink text-white hover:bg-hero-panel">
            <Search aria-hidden="true" className="size-4" />
          </button>
        </Form>
        <nav aria-label="Cuenta y compras" className="flex min-w-0 items-center justify-end gap-0.5 text-xs md:gap-1.5">
          <Link to="/vendedor" className="store-header-link hidden rounded-full border border-brand-ink/20 bg-white/20 sm:inline-flex">
            <Store aria-hidden="true" className="size-3.5" /> Vender
          </Link>
          <NotificationBell enabled={!identityFlow} />
          <CartLink enabled={!identityFlow} />
          <Link to="/cuenta" aria-label="Mi cuenta" className="store-header-link min-w-10">
            <span className="grid size-7 place-items-center rounded-full bg-white/30 ring-1 ring-brand-ink/10">
              <UserRound aria-hidden="true" className="size-4" />
            </span>
            <span className="hidden lg:inline">Mi cuenta</span>
          </Link>
        </nav>
      </div>
      <CategoryNavigation />
    </header>
    <main id="contenido" tabIndex={-1} className={`min-h-[65vh] scroll-mt-28 ${storefront ? "pb-10" : "store-frame " + (listing ? "pt-2 pb-7" : "py-5 sm:py-8")}`}>
      <Outlet />
    </main>
    <footer className="border-t bg-card">
      <div className="store-frame flex flex-wrap items-center justify-between gap-x-5 py-4 text-sm text-muted-foreground">
        <Link to="/catalogo" aria-label="Volver a la tienda" className="font-wordmark text-lg font-bold text-foreground">Zonda Market</Link>
        <div className="flex flex-wrap items-center gap-x-4">
          <Link to="/vendedor" className="inline-flex min-h-11 items-center hover:underline">Herramientas de venta</Link>
          <Link to="/estado" className="inline-flex min-h-11 items-center hover:underline">Estado del sistema</Link>
        </div>
      </div>
    </footer>
    <ScrollRestoration getKey={({ pathname }) => pathname} />
  </>;
}

export function PrivateLayout() {
  const { pathname } = useLocation();
  if (/^\/ingresar\/?$/i.test(pathname)) return <Outlet />;
  return <Card className="mx-auto max-w-lg border-t-4 border-t-brand py-6 shadow-float sm:py-8">
    <CardContent className="sm:px-8"><Outlet /></CardContent>
  </Card>;
}

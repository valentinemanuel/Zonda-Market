import { Link, Outlet, useLocation } from "react-router-dom";
import { Card, CardContent } from "@/shared/ui/card";
import { useCart } from "@/features/cart/cart-api";
import { NotificationBell } from "@/features/notifications/notification-bell";

function CartLink({ enabled }: { enabled: boolean }) {
  const { query } = useCart(enabled);
  const count = query.isSuccess ? query.data.itemCount : 0;
  return <Link to="/carrito" aria-label={count > 0 ? `Carrito, ${count} ${count === 1 ? "unidad" : "unidades"}` : "Carrito"}
    className="inline-flex min-h-11 items-center gap-1 whitespace-nowrap rounded-lg px-1 text-sm font-semibold underline underline-offset-4 focus-visible:outline-brand-ink">
    Carrito
    {count > 0 && <span aria-hidden="true" className="rounded-full bg-brand-ink px-1.5 text-xs text-white tabular-nums">
      {count > 99 ? "99+" : count}
    </span>}
  </Link>;
}

export function SiteLayout() {
  const { pathname } = useLocation();
  // Identity flows manage their own refresh cookie, including the previous session in Google exchange.
  const identityFlow = /^\/(ingresar|registro|oauth\/retorno|recuperar-acceso|restablecer-clave|verificar-correo)\/?$/i.test(pathname);
  return (
    <>
      <a
        href="#contenido"
        className="sr-only focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-10 focus:bg-white focus:p-2"
      >
        Ir al contenido
      </a>
      <header className="border-b border-brand-ink/15 bg-brand text-brand-ink">
        <div className="mx-auto flex min-h-16 max-w-7xl items-center justify-between gap-3 px-4 sm:px-6 lg:px-8">
          <Link to="/catalogo" aria-label="Zonda Market" className="inline-flex min-h-11 min-w-0 items-center gap-1.5 rounded-lg font-extrabold focus-visible:outline-brand-ink sm:gap-2.5">
            <img src="/brand/zonda-isotipo.svg#color" alt="" width="36" height="36" className="size-7 shrink-0 sm:size-9" />
            <span className="font-wordmark text-lg leading-none tracking-[-0.045em] sm:text-[1.375rem]">zonda market</span>
          </Link>
          <nav aria-label="Cuenta y compras" className="flex shrink-0 items-center gap-1 sm:gap-4">
            <CartLink enabled={!identityFlow} />
            <NotificationBell enabled={!identityFlow} />
            <Link to="/cuenta" className="inline-flex min-h-11 items-center whitespace-nowrap rounded-lg px-1 text-sm font-semibold underline underline-offset-4 focus-visible:outline-brand-ink">
              Mi cuenta
            </Link>
          </nav>
        </div>
      </header>
      <main id="contenido" tabIndex={-1} className="mx-auto max-w-7xl px-4 py-8 sm:px-6 sm:py-12 lg:px-8">
        <Outlet />
      </main>
      <footer className="mx-auto max-w-7xl border-t px-4 py-4 text-sm text-muted-foreground sm:px-6 lg:px-8">
        <Link to="/estado" className="inline-flex min-h-11 items-center underline underline-offset-4">Estado del sistema</Link>
      </footer>
    </>
  );
}

export function PrivateLayout() {
  return (
    <Card className="mx-auto max-w-lg rounded-lg py-7 sm:py-8">
      <CardContent className="sm:px-8"><Outlet /></CardContent>
    </Card>
  );
}

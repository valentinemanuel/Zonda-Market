import { Link, Outlet } from "react-router-dom";

export function SiteLayout() {
  return (
    <>
      <a
        href="#contenido"
        className="sr-only focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-10 focus:bg-white focus:p-2"
      >
        Ir al contenido
      </a>
      <header className="border-b border-brand-ink/15 bg-brand text-brand-ink">
        <div className="mx-auto flex min-h-16 max-w-7xl items-center px-4 sm:px-6 lg:px-8">
          <Link to="/estado" aria-label="Zonda Market" className="inline-flex min-h-11 items-center gap-2.5 rounded-lg font-extrabold tracking-tight focus-visible:outline-brand-ink">
            <img src="/brand/zonda-isotipo.svg" alt="" width="36" height="36" />
            ZONDA-MARKET
          </Link>
        </div>
      </header>
      <main id="contenido" tabIndex={-1} className="mx-auto max-w-7xl px-4 py-8 sm:px-6 sm:py-12 lg:px-8">
        <Outlet />
      </main>
    </>
  );
}

export function PrivateLayout() {
  return (
    <section className="mx-auto max-w-lg rounded-2xl border border-border bg-card p-6 sm:p-8">
      <Outlet />
    </section>
  );
}

import { Wind } from "lucide-react";
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
      <header className="border-b border-border">
        <div className="mx-auto flex h-18 max-w-6xl items-center px-5 sm:px-8">
          <Link to="/estado" className="inline-flex items-center gap-3 font-semibold">
            <span className="grid size-9 place-items-center rounded-xl bg-hero text-[#ffc564]">
              <Wind size={20} aria-hidden="true" />
            </span>
            Zonda Market
          </Link>
        </div>
      </header>
      <main id="contenido" tabIndex={-1} className="mx-auto max-w-6xl px-5 py-8 sm:px-8 sm:py-12">
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

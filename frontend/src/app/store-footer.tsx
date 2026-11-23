import { useQuery } from "@tanstack/react-query";
import { CreditCard, MessageCircle, Share2, ShieldCheck, Truck } from "lucide-react";
import { Link } from "react-router-dom";
import { fetchStatus } from "@/features/status/status-api";
import { StoreBrand } from "./store-brand";

const benefits = [
  { icon: CreditCard, title: "Pagá como quieras", text: "Tarjetas de crédito, débito y medios digitales." },
  { icon: Truck, title: "Envíos con seguimiento", text: "Seguí tu paquete paso a paso desde tu cuenta." },
  { icon: ShieldCheck, title: "Compra protegida", text: "Si tenés un problema, abrí un reclamo desde Mis órdenes." },
];

const sections = [
  { title: "Comprá", links: [
    ["Explorar catálogo", "/catalogo"], ["Ofertas", "/catalogo?max=20000&sort=price_asc"],
    ["Categorías populares", "/catalogo#categorias-populares"], ["Mis órdenes", "/mis-ordenes"],
  ] },
  { title: "Vendé", links: [
    ["Empezar a vender", "/cuenta"], ["Publicar producto", "/vendedor/publicaciones/nueva"],
    ["Herramientas de venta", "/vendedor"], ["Guía para vendedores", "/informacion/guia-vendedores"],
  ] },
  { title: "Ayuda y Cuenta", links: [
    ["Mi cuenta", "/cuenta"], ["Mis direcciones", "/cuenta/direcciones"], ["Centro de ayuda", "/informacion/ayuda"],
    ["Estado del sistema", "/estado"],
  ] },
];

const footerLink = "inline-flex min-h-9 items-center rounded-sm text-[#d1d6df] underline-offset-4 hover:text-white hover:underline focus-visible:outline-brand-gold";
const socialLink = "grid size-10 place-items-center rounded-lg border border-white/18 text-white hover:bg-white/10 focus-visible:outline-brand-gold";

function FooterAvailability() {
  const { data } = useQuery({
    queryKey: ["system-status"],
    queryFn: ({ signal }) => fetchStatus(signal),
    staleTime: 60_000,
    refetchInterval: 60_000,
  });
  const ready = !!data?.api && data.readiness === "ready";
  const down = data?.readiness === "down";
  return <span className={`inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-semibold ${ready
    ? "bg-emerald-400/15 text-emerald-200" : down ? "bg-amber-400/15 text-amber-200" : "bg-white/8 text-[#d1d6df]"}`}>
    <span aria-hidden="true" className="size-1.5 rounded-full bg-current" />
    {ready ? "Operativo" : down ? "Interrumpido" : data ? "Sin confirmar" : "Comprobando"}
  </span>;
}

export function StoreFooter() {
  const shareUrl = encodeURIComponent(new URL("/catalogo", window.location.origin).href);
  return <footer className="bg-[#111318] text-white">
    <div className="border-y border-[#e5e7eb] bg-[#f9fafb] text-foreground">
      <ul aria-label="Beneficios de comprar en Zonda Market" role="list"
        className="store-frame grid max-w-7xl divide-y md:grid-cols-3 md:divide-x md:divide-y-0">
        {benefits.map(({ icon: Icon, title, text }) => <li key={title} className="flex items-center gap-3 py-3.5 md:px-4 md:py-5 lg:px-6">
          <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-accent text-primary">
            <Icon aria-hidden="true" className="size-5" />
          </span>
          <div><p className="text-sm font-bold">{title}</p><p className="text-sm text-muted-foreground">{text}</p></div>
        </li>)}
      </ul>
    </div>
    <div className="store-frame grid max-w-7xl grid-cols-1 gap-x-6 gap-y-6 py-8 sm:grid-cols-2 lg:grid-cols-[1.2fr_1fr_1fr_1fr] lg:py-10">
      <div className="space-y-3">
        <StoreBrand tone="dark" label="Volver a la tienda" />
        <p className="max-w-xs text-sm leading-relaxed text-[#d1d6df]">Tu marketplace para comprar y vender de forma simple y segura.</p>
        <div className="flex flex-wrap gap-2" role="group" aria-label="Compartí Zonda Market">
          <a className={socialLink} href={`https://wa.me/?text=${shareUrl}`} target="_blank" rel="noreferrer" aria-label="Compartir en WhatsApp">
            <MessageCircle aria-hidden="true" className="size-4.5" />
          </a>
          <a className={socialLink} href={`https://www.facebook.com/sharer/sharer.php?u=${shareUrl}`} target="_blank" rel="noreferrer" aria-label="Compartir en Facebook">
            <Share2 aria-hidden="true" className="size-4.5" />
          </a>
        </div>
      </div>
      {sections.map(({ title, links }) => <nav key={title} aria-label={title}>
        <h2 className="mb-2 font-wordmark text-lg font-bold">{title}</h2>
        <ul role="list" className="text-sm">
          {links.map(([label, to]) => <li key={to} className="flex flex-wrap items-center gap-x-2">
            <Link to={to} className={footerLink}>{label}</Link>
            {to === "/estado" && <FooterAvailability />}
          </li>)}
        </ul>
      </nav>)}
    </div>
    <div className="border-t border-white/15">
      <div className="store-frame flex max-w-7xl flex-wrap items-center justify-between gap-x-6 gap-y-2 py-4 text-xs text-[#d1d6df]">
        <p>© 2026 Zonda Market · Todos los derechos reservados</p>
        <nav aria-label="Información legal" className="flex flex-wrap gap-x-4">
          <Link to="/informacion/terminos" className={footerLink}>Términos y condiciones</Link>
          <Link to="/informacion/privacidad" className={footerLink}>Privacidad</Link>
        </nav>
        <ul aria-label="Medios de pago" role="list" className="flex flex-wrap gap-1.5">
          {["VISA", "Mastercard", "Amex", "Mercado Pago"].map((label) => <li key={label}
            className="rounded-sm border border-white/15 bg-white/7 px-2 py-1 font-bold text-white">{label}</li>)}
        </ul>
      </div>
    </div>
  </footer>;
}

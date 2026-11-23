import { Boxes, ChartNoAxesColumnIncreasing, LayoutList, MessageCircle, Package, Store } from "lucide-react";
import type { ReactNode } from "react";
import { NavLink } from "react-router-dom";
import { listingStatusLabel, type ListingStatus } from "./listing-api";

export const sellerListingsPath = "/vendedor/publicaciones";
export const sellerInventoryPath = "/vendedor/inventario";
export const sellerQuestionsPath = "/vendedor/preguntas";
export const sellerOrdersPath = "/vendedor/pedidos";
export const sellerSalesPath = "/vendedor/ventas";
export const listingCollectionKey = (revision: number) => ["private", "listings", revision] as const;
export const listingDetailKey = (revision: number, id: string) => [...listingCollectionKey(revision), id] as const;
export const unitFormat = new Intl.NumberFormat("es-AR");

export function inventoryPath(listingId?: string, skuId?: string) {
  const params = new URLSearchParams();
  if (listingId) params.set("publicacion", listingId);
  if (skuId) params.set("sku", skuId);
  return sellerInventoryPath + (params.size ? "?" + params.toString() : "");
}

const navigation = [
  { to: "/vendedor", label: "Panel vendedor", short: "Panel", icon: Store, end: true },
  { to: sellerListingsPath, label: "Mis publicaciones", short: "Publicaciones", icon: LayoutList, end: false },
  { to: sellerInventoryPath, label: "Inventario", short: "Inventario", icon: Boxes, end: false },
  { to: sellerQuestionsPath, label: "Preguntas", short: "Preguntas", icon: MessageCircle, end: false },
  { to: sellerOrdersPath, label: "Pedidos recibidos", short: "Pedidos", icon: Package, end: false },
  { to: sellerSalesPath, label: "Ventas y neto estimado", short: "Ventas", icon: ChartNoAxesColumnIncreasing, end: false },
];

export function SellerNavigation() {
  return (
    <nav aria-label="Herramientas de venta" className="grid grid-cols-2 gap-1 rounded-panel border bg-card p-1.5 shadow-panel min-[410px]:grid-cols-4 md:flex md:flex-wrap md:w-fit">
      {navigation.map(({ to, label, short, icon: Icon, end }) => (
        <NavLink key={to} to={to} end={end} aria-label={label}
          className="inline-flex min-h-10 min-w-0 items-center justify-center gap-1 rounded-lg px-0.5 text-xs font-semibold sm:gap-1.5 sm:text-sm text-muted-foreground hover:bg-muted hover:text-foreground aria-[current=page]:bg-secondary aria-[current=page]:text-accent-foreground aria-[current=page]:underline aria-[current=page]:underline-offset-4 sm:px-3">
          <Icon aria-hidden="true" size={15} className="shrink-0 max-[345px]:hidden" />
          <span className="sm:hidden">{short}</span><span className="hidden sm:inline">{label}</span>
        </NavLink>
      ))}
    </nav>
  );
}

export function SellerHeading({ title, description, actions, children }: {
  title: string; description?: string; actions?: ReactNode; children?: ReactNode;
}) {
  return (
    <header className="workspace-heading px-5 py-6 sm:p-8">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="min-w-0 space-y-2">
          {children}
          <h1 className="font-wordmark text-3xl leading-tight font-bold tracking-tight [overflow-wrap:anywhere] sm:text-4xl">{title}</h1>
          {description && <p className="max-w-[52ch] text-sm leading-relaxed text-slate-200 sm:text-base">{description}</p>}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </div>
    </header>
  );
}

const badgeStyle: Record<ListingStatus, string> = {
  ACTIVA: "bg-emerald-50 text-emerald-800",
  BORRADOR: "bg-amber-50 text-amber-900",
  ARCHIVADA: "bg-slate-100 text-slate-700",
};

export function ListingStatusBadge({ status }: { status: ListingStatus }) {
  return <span className={"inline-flex w-fit items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold " + badgeStyle[status]}>
    <span aria-hidden="true" className="size-1.5 rounded-full bg-current" />{listingStatusLabel[status]}
  </span>;
}

export function ProductThumbnail({ url }: { url: string | null }) {
  return (
    <div className="grid size-14 shrink-0 place-items-center overflow-hidden rounded-lg border bg-muted text-slate-400 sm:size-16">
      {url ? <img src={url} alt="" className="size-full object-cover" loading="lazy" decoding="async" />
        : <Package size={24} strokeWidth={1.5} aria-hidden="true" />}
    </div>
  );
}

export function StockTotal({ units, configured = true }: { units: number; configured?: boolean }) {
  return (
    <div className="min-w-0">
      <p className="text-xs font-medium text-muted-foreground">Stock disponible</p>
      {configured ? <p className={units ? "font-semibold tabular-nums" : "font-semibold tabular-nums text-destructive"}>
        {units ? unitFormat.format(units) + " " + (units === 1 ? "unidad" : "unidades") : "0 unidades · Sin stock"}
      </p> : <p className="text-sm font-medium">Sin opciones cargadas</p>}
    </div>
  );
}

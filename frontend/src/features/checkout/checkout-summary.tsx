import { Check, Clock3, LockKeyhole, Package } from "lucide-react";
import type { ReactNode } from "react";
import type { DeliveryAddress } from "@/features/auth/profile-api";
import { attributeEntries, attributeLabel } from "@/features/listings/listing-forms";
import { formattedPrice } from "@/features/listings/public-listing-format";
import { Card, CardContent } from "@/shared/ui/card";
import type { Group, SubOrder } from "./checkout-api";
import { ShipmentTimeline, subOrderLabel } from "./shipment-timeline";

export function addressText(address: DeliveryAddress) {
  return [address.recipient, address.street, address.deliveryDetails, address.city, address.provinceCode, address.postalCode].filter(Boolean).join(" · ");
}

const steps = ["Envío", "Pago", "Confirmación"] as const;
export const checkoutSurface = "border-[#e5e7eb] bg-white shadow-panel";

export function CheckoutHeading({ title, step, quietTitle = false }: { title: string; step: typeof steps[number]; quietTitle?: boolean }) {
  const current = steps.indexOf(step);
  return <header className="space-y-4">
    <h1 className={quietTitle ? "sr-only" : "font-wordmark text-3xl font-extrabold tracking-tight sm:text-4xl"}>{title}</h1>
    <nav aria-label="Pasos de la compra">
      <ol className="flex items-start gap-1 rounded-panel border border-[#e5e7eb] bg-white p-2 text-xs shadow-panel sm:gap-2 sm:p-3 sm:text-sm">
        {steps.map((label, index) => <li key={label} aria-current={label === step ? "step" : undefined}
          className="flex min-w-0 flex-1 flex-col items-center justify-center gap-1.5 rounded-lg px-1 py-2 text-center font-semibold text-muted-foreground aria-[current=step]:bg-accent aria-[current=step]:text-primary sm:flex-row sm:gap-2">
          <span className={`grid size-6 place-items-center rounded-full text-xs ${index === current ? "bg-primary text-white"
            : index < current ? "bg-emerald-100 text-emerald-900" : "bg-muted text-muted-foreground"}`}>
            {index < current ? <Check aria-hidden="true" className="size-3.5" /> : index + 1}
          </span>{label}
        </li>)}
      </ol>
    </nav>
  </header>;
}

type SummaryGroup = Omit<Group, "shipping"> & { shipping: string | null };

function amountTotal(amounts: string[]) {
  const cents = amounts.reduce((sum, amount) => {
    const [whole, fraction = ""] = amount.split(".");
    return sum + BigInt(whole) * 100n + BigInt(fraction.padEnd(2, "0"));
  }, 0n);
  return formattedPrice(`${cents / 100n}.${String(cents % 100n).padStart(2, "0")}`);
}

export function CheckoutSummary({ groups, total, totalLabel = "Total a pagar", children, subOrders, actions, beforeGroups,
  thumbnails, time, showGroups = true }: {
  groups: SummaryGroup[]; total?: string; children?: ReactNode; subOrders?: SubOrder[];
  totalLabel?: string; actions?: (sub: SubOrder) => ReactNode; beforeGroups?: ReactNode;
  thumbnails?: Record<string, string>; time?: string; showGroups?: boolean;
}) {
  const tracking = new Map(subOrders?.map((sub) => [sub.group.sellerId, sub]));
  const items = groups.flatMap((group) => group.items);
  const quantity = items.reduce((sum, item) => sum + item.quantity, 0);
  return <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,13fr)_minmax(0,7fr)] lg:gap-8">
    <div className="min-w-0 space-y-4">
      {beforeGroups}
      {showGroups && <>
        <h2 className="flex items-center gap-2 text-base font-semibold"><Package aria-hidden="true" className="size-4 text-primary" />Productos por vendedor</h2>
        {groups.map((group) => {
          const subOrder = tracking.get(group.sellerId);
          return <CheckoutGroupCard key={group.sellerId} group={group} subOrder={subOrder} thumbnails={thumbnails}>
            {subOrder && actions?.(subOrder)}
          </CheckoutGroupCard>;
        })}
      </>}
    </div>
    <Card role="complementary" aria-label="Resumen de compra"
      className={checkoutSurface + " min-w-0 gap-0 py-5 lg:sticky lg:top-6"}>
      <CardContent className="space-y-4 px-4 sm:px-5">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="font-wordmark text-xl font-bold">Resumen de compra</h2>
          {time && <p className="flex items-center gap-1.5 rounded-full bg-amber-50 px-2.5 py-1.5 text-xs font-semibold text-amber-900">
            <Clock3 aria-hidden="true" className="size-3.5" />Tiempo restante <span role="timer" className="font-bold tabular-nums">{time}</span>
          </p>}
        </div>
        {items.length > 0 && <ul aria-label="Artículos de la orden" role="list" tabIndex={items.length > 4 ? 0 : undefined}
          className={`divide-y border-y ${items.length > 4 ? "max-h-[35vh] overflow-y-auto overscroll-contain" : ""}`}>
          {items.map((item) => <li key={item.skuId} className="grid grid-cols-[auto_minmax(0,1fr)] items-center gap-3 py-2.5">
            <ProductThumbnail url={thumbnails?.[item.skuId]} />
            <div>
              <p className="wrap-anywhere text-sm font-semibold">{item.title}</p>
              <p className="text-xs text-muted-foreground">{item.quantity} {item.quantity === 1 ? "unidad" : "unidades"}</p>
            </div>
          </li>)}
        </ul>}
        <dl className="space-y-2 text-sm tabular-nums">
          <div className="flex flex-wrap justify-between gap-x-2">
            <dt>Subtotal ({quantity})</dt><dd>{amountTotal(groups.map(({ merchandise }) => merchandise))}</dd>
          </div>
          <div className="flex flex-wrap justify-between gap-x-2">
            <dt>Envío total</dt><dd>{total === undefined ? "Por cotizar" : amountTotal(groups.map(({ shipping }) => shipping ?? "0"))}</dd>
          </div>
          <div className="flex flex-wrap items-baseline justify-between gap-x-3 border-t pt-3 font-bold">
            <dt>{totalLabel}</dt><dd className="font-wordmark text-xl">{total === undefined ? "Por cotizar" : formattedPrice(total)}</dd>
          </div>
        </dl>
        {children}
        <p className="flex items-center gap-1.5 text-xs text-muted-foreground"><LockKeyhole aria-hidden="true" className="size-3.5" />Compra segura</p>
      </CardContent>
    </Card>
  </div>;
}

export function CheckoutGroupCard({ group, subOrder, children, title = group.sellerName, thumbnails }: {
  group: SummaryGroup; subOrder?: SubOrder; children?: ReactNode; title?: string; thumbnails?: Record<string, string>;
}) {
  return <Card id={subOrder ? "suborden-" + subOrder.id : undefined} tabIndex={subOrder ? -1 : undefined}
    className={checkoutSurface + " scroll-mt-4 gap-0 py-4"}>
    <CardContent className="space-y-3 px-4 sm:px-6">
      <h2 className="break-words text-lg font-semibold">{title}</h2>
      {subOrder && <div className="space-y-1">
        <p className="w-fit rounded-full bg-accent px-3 py-1 text-sm font-bold text-accent-foreground" role="status">{subOrderLabel(subOrder.status)}</p>
        <p className="break-words text-sm text-muted-foreground">Suborden {subOrder.id}</p>
      </div>}
      <ul className="divide-y">
        {group.items.map((item) => (
          <li key={item.skuId} className={thumbnails
            ? "grid grid-cols-[auto_minmax(0,1fr)] items-center gap-3 py-3 sm:grid-cols-[auto_minmax(0,1fr)_auto]"
            : "flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-3"}>
            {thumbnails && <ProductThumbnail url={thumbnails[item.skuId]} />}
            <div className="min-w-0 space-y-1 [overflow-wrap:anywhere]">
              <p className="font-medium">{item.title}</p>
              <p className="text-sm text-muted-foreground">
                {[item.quantity + (item.quantity === 1 ? " unidad" : " unidades"),
                  ...attributeEntries(item.attributes).map(([name, value]) => attributeLabel(name) + " " + value)].join(" · ")}
              </p>
            </div>
            <span className="col-start-2 text-sm font-medium tabular-nums sm:col-start-3">{formattedPrice(item.subtotal)}</span>
          </li>
        ))}
      </ul>
      <dl className="space-y-1 border-t pt-3 text-sm tabular-nums">
        <div className="flex flex-wrap justify-between gap-x-3"><dt>Productos</dt><dd>{formattedPrice(group.merchandise)}</dd></div>
        {group.shipping !== null && <div className="flex flex-wrap justify-between gap-x-3"><dt>Envío</dt><dd>{formattedPrice(group.shipping)}</dd></div>}
        {group.shipping !== null && <div className="flex flex-wrap justify-between gap-x-3 font-semibold"><dt>Subtotal del vendedor</dt><dd>{formattedPrice(group.subtotal)}</dd></div>}
      </dl>
      {subOrder?.shipment && <ShipmentTimeline shipment={subOrder.shipment} />}
      {children}
    </CardContent>
  </Card>;
}

function ProductThumbnail({ url }: { url?: string }) {
  return <span className="relative grid size-12 shrink-0 place-items-center rounded-lg bg-muted text-muted-foreground">
    <Package aria-hidden="true" className="size-5" />
    {url && <img src={url} alt="" width={48} height={48} loading="lazy"
      onError={(event) => { event.currentTarget.hidden = true; }}
      className="absolute inset-0 size-full rounded-[inherit] object-cover outline-1 -outline-offset-1 outline-black/10" />}
  </span>;
}

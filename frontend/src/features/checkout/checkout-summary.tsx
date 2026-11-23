import { Check, ClipboardCheck, CreditCard, LockKeyhole, MapPin, Package } from "lucide-react";
import type { ReactNode } from "react";
import type { Address } from "@/features/auth/profile-api";
import { attributeEntries, attributeLabel } from "@/features/listings/listing-forms";
import { formattedPrice } from "@/features/listings/public-listing-format";
import { Card, CardContent } from "@/shared/ui/card";
import type { Group, SubOrder } from "./checkout-api";
import { ShipmentTimeline, subOrderLabel } from "./shipment-timeline";

export function addressText(address: Address) {
  return [address.street, address.city, address.provinceCode, address.postalCode].join(" · ");
}

const steps = [
  { label: "Envío", icon: MapPin },
  { label: "Pago", icon: CreditCard },
  { label: "Confirmación", icon: ClipboardCheck },
] as const;

export function CheckoutHeading({ title, step }: { title: string; step: typeof steps[number]["label"] }) {
  const current = steps.findIndex(({ label }) => label === step);
  return <header className="workspace-heading space-y-5 p-4 sm:p-6">
    <div className="flex items-center gap-3">
      <LockKeyhole aria-hidden="true" className="size-11 shrink-0 rounded-xl border border-white/15 bg-white/10 p-2.5 text-brand-gold" />
      <div>
        <p className="text-xs font-semibold text-hero-muted">Zonda Market Checkout</p>
        <h1 className="font-wordmark text-2xl font-extrabold sm:text-3xl">{title}</h1>
      </div>
    </div>
    <nav aria-label="Pasos de la compra">
      <ol className="grid grid-cols-3 gap-1.5 text-xs sm:gap-3 sm:text-sm">
        {steps.map(({ label, icon: Icon }, index) => <li key={label} aria-current={label === step ? "step" : undefined}
          className="flex min-w-0 flex-col items-center justify-center gap-1.5 rounded-lg border border-white/15 bg-white/10 px-1 py-2 text-center text-hero-muted aria-[current=step]:border-brand-gold aria-[current=step]:bg-brand-gold aria-[current=step]:font-bold aria-[current=step]:text-brand-ink sm:flex-row">
          {index < current ? <Check aria-hidden="true" className="size-4 text-brand-gold" /> : <Icon aria-hidden="true" className="size-4" />}
          {label}
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

export function CheckoutSummary({ groups, total, totalLabel = "Total a pagar", children, subOrders, actions, beforeGroups }: {
  groups: SummaryGroup[]; total?: string; children?: ReactNode; subOrders?: SubOrder[];
  totalLabel?: string; actions?: (sub: SubOrder) => ReactNode; beforeGroups?: ReactNode;
}) {
  const tracking = new Map(subOrders?.map((sub) => [sub.group.sellerId, sub]));
  const quantity = groups.reduce((sum, group) => sum + group.items.reduce((count, item) => count + item.quantity, 0), 0);
  return <div className="grid items-start gap-5 md:grid-cols-[minmax(0,2fr)_minmax(14rem,1fr)]">
    <div className="space-y-4">
      {beforeGroups}
      <h2 className="flex items-center gap-2 text-base font-semibold"><Package aria-hidden="true" className="size-4 text-primary" />Productos por vendedor</h2>
      {groups.map((group) => {
        const subOrder = tracking.get(group.sellerId);
        return <CheckoutGroupCard key={group.sellerId} group={group} subOrder={subOrder}>
          {subOrder && actions?.(subOrder)}
        </CheckoutGroupCard>;
      })}
    </div>
    <Card className="gap-0 border-t-4 border-t-brand py-4 md:sticky md:top-28">
      <CardContent className="space-y-4 px-4 sm:px-5">
        <h2 className="border-b pb-3 font-wordmark text-lg font-bold">Resumen de compra</h2>
        <dl className="space-y-2 text-sm tabular-nums">
          <div className="flex flex-wrap justify-between gap-x-2">
            <dt>Productos ({quantity})</dt><dd>{amountTotal(groups.map(({ merchandise }) => merchandise))}</dd>
          </div>
          <div className="flex flex-wrap justify-between gap-x-2">
            <dt>Envío total</dt><dd>{total === undefined ? "Por cotizar" : amountTotal(groups.map(({ shipping }) => shipping ?? "0"))}</dd>
          </div>
          {total !== undefined && <div className="flex flex-wrap justify-between gap-x-2 border-t pt-3 font-bold">
            <dt>{totalLabel}</dt><dd>{formattedPrice(total)}</dd>
          </div>}
        </dl>
        {children}
      </CardContent>
    </Card>
  </div>;
}

export function CheckoutGroupCard({ group, subOrder, children, title = group.sellerName }: {
  group: SummaryGroup; subOrder?: SubOrder; children?: ReactNode; title?: string;
}) {
  return <Card id={subOrder ? "suborden-" + subOrder.id : undefined} tabIndex={subOrder ? -1 : undefined}
    className="scroll-mt-4 gap-0 py-4">
    <CardContent className="space-y-3 px-4 sm:px-6">
      <h2 className="break-words text-lg font-semibold">{title}</h2>
      {subOrder && <div className="space-y-1">
        <p className="w-fit rounded-full bg-accent px-3 py-1 text-sm font-bold text-accent-foreground" role="status">{subOrderLabel(subOrder.status)}</p>
        <p className="break-words text-sm text-muted-foreground">Suborden {subOrder.id}</p>
      </div>}
      <ul className="divide-y">
        {group.items.map((item) => (
          <li key={item.skuId} className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-3">
            <div className="min-w-0 space-y-1 [overflow-wrap:anywhere]">
              <p className="font-medium">{item.title}</p>
              <p className="text-sm text-muted-foreground">
                {[item.quantity + (item.quantity === 1 ? " unidad" : " unidades"),
                  ...attributeEntries(item.attributes).map(([name, value]) => attributeLabel(name) + " " + value)].join(" · ")}
              </p>
            </div>
            <span className="tabular-nums">{formattedPrice(item.subtotal)}</span>
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

import type { ReactNode } from "react";
import type { Address } from "@/features/auth/profile-api";
import { attributeEntries, attributeLabel } from "@/features/listings/listing-forms";
import { formattedPrice } from "@/features/listings/public-listing-format";
import { Card, CardContent } from "@/shared/ui/card";
import type { Group } from "./checkout-api";

export function addressText(address: Address) {
  return [address.street, address.city, address.provinceCode, address.postalCode].join(" · ");
}

export function CheckoutSummary({ groups, total, children }: { groups: Group[]; total: string; children?: ReactNode }) {
  return <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_18rem]">
    <div className="space-y-4">
      {groups.map((group) => (
        <Card key={group.sellerId} className="gap-0 py-4">
          <CardContent className="space-y-3 px-4 sm:px-6">
            <h2 className="break-words text-lg font-semibold">{group.sellerName}</h2>
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
              <div className="flex flex-wrap justify-between gap-x-3"><dt>Envío</dt><dd>{formattedPrice(group.shipping)}</dd></div>
              <div className="flex flex-wrap justify-between gap-x-3 font-semibold"><dt>Subtotal del vendedor</dt><dd>{formattedPrice(group.subtotal)}</dd></div>
            </dl>
          </CardContent>
        </Card>
      ))}
    </div>
    <Card className="gap-0 py-4">
      <CardContent className="space-y-3 px-4 sm:px-6">
        <h2 className="font-semibold">Resumen</h2>
        <p className="flex flex-wrap justify-between gap-x-3 text-lg font-semibold tabular-nums">
          Total <span>{formattedPrice(total)}</span>
        </p>
        {children}
      </CardContent>
    </Card>
  </div>;
}

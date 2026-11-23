import { ArrowRight, Check, Copy, CreditCard, MapPin } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router-dom";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import type { Order } from "./checkout-api";
import { addressText, checkoutSurface } from "./checkout-summary";

function paymentDescription(payment: Order["payment"]) {
  if (payment?.simulated) return "Pago de prueba";
  if (payment?.method === "CARD") return payment.lastFour
    ? "Tarjeta terminada en •••• " + payment.lastFour : "Tarjeta de crédito o débito";
  if (payment?.method === "CHECKOUT_PRO") return "Saldo de Mercado Pago";
  return "Pago acreditado";
}

export function PurchaseReceipt({ order }: { order: Order }) {
  const [copy, setCopy] = useState<"copied" | "failed">();
  const address = order.subOrders[0]?.address;
  async function copyNumber() {
    try {
      await navigator.clipboard.writeText(order.id);
      setCopy("copied");
    } catch {
      setCopy("failed");
    }
  }
  return <Card className={checkoutSurface + " gap-0 py-7 sm:py-9"}>
    <CardContent className="space-y-5 px-4 text-center sm:px-7">
      <div className="space-y-2">
        <span className="checkout-success-mark mx-auto mb-4 grid size-16 place-items-center rounded-full bg-emerald-100 text-emerald-800">
          <Check aria-hidden="true" className="size-8" strokeWidth={2.5} />
        </span>
        <h2 className="font-wordmark text-3xl font-extrabold tracking-tight sm:text-4xl">¡Gracias por tu compra!</h2>
        <p className="text-sm text-muted-foreground">Tu pago se acreditó. Podés seguir cada envío desde Mis órdenes.</p>
      </div>
      <div className="flex flex-wrap items-center justify-center gap-x-3 gap-y-1 rounded-lg bg-muted/65 px-3 py-2">
        <p className="wrap-anywhere text-sm font-semibold tabular-nums">Orden {order.id}</p>
        <Button type="button" variant="ghost" className="min-h-11 px-2 text-primary" onClick={() => void copyNumber()}>
          <Copy aria-hidden="true" />Copiar número
        </Button>
      </div>
      <p role="status" className={copy ? "text-sm text-muted-foreground" : "sr-only"}>
        {copy === "copied" ? "Número de orden copiado." : copy === "failed" ? "Seleccioná el número de orden para copiarlo." : ""}
      </p>
      <dl className="grid gap-3 text-left sm:grid-cols-2">
        {address && <div className="flex items-start gap-2.5 rounded-lg border border-[#e5e7eb] p-3.5">
          <MapPin aria-hidden="true" className="size-5 shrink-0 text-primary" />
          <div className="min-w-0">
            <dt className="text-sm font-semibold">Dirección de entrega</dt>
            <dd className="mt-1 wrap-anywhere text-sm text-muted-foreground">{addressText(address)}</dd>
          </div>
        </div>}
        <div className="flex items-start gap-2.5 rounded-lg border border-[#e5e7eb] p-3.5">
          <CreditCard aria-hidden="true" className="size-5 shrink-0 text-primary" />
          <div>
            <dt className="text-sm font-semibold">Medio de pago</dt>
            <dd className="mt-1 text-sm text-muted-foreground">{paymentDescription(order.payment)}</dd>
          </div>
        </div>
      </dl>
      <div className="flex flex-col justify-center gap-2 sm:flex-row">
        <Button asChild className="min-h-11"><Link to="/cuenta/ordenes">Ver en Mis órdenes <ArrowRight aria-hidden="true" /></Link></Button>
        <Button asChild variant="outline" className="min-h-11"><Link to="/catalogo">Seguir comprando</Link></Button>
      </div>
    </CardContent>
  </Card>;
}

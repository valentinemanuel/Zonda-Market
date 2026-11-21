import { useMutation, useQueryClient } from "@tanstack/react-query";
import { getSessionRevision } from "@/features/auth/session-api";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import { activeOrderKey, orderKey, ordersKey, simulatePayment, type Order, type PaymentOutcome } from "./checkout-api";

type Fixture = NonNullable<Order["testPayment"]>;

const messages: Record<NonNullable<Fixture["result"]>, string> = {
  APPROVED: "Pago de prueba aprobado. Las subórdenes están en preparación.",
  REJECTED: "Pago de prueba rechazado. Se liberó el stock.",
  LATE_APPROVAL: "La aprobación de prueba llegó fuera de plazo y quedó registrada para conciliación.",
  EXPIRED: "El resultado de prueba llegó después del vencimiento.",
};

export function TestPaymentAction({ order, fixture, enabled, revision }: {
  order: Order; fixture: Fixture; enabled: boolean; revision: number;
}) {
  const client = useQueryClient();
  const key = orderKey(revision, order.id);
  const payment = useMutation({
    mutationFn: (outcome: PaymentOutcome) => {
      if (revision !== getSessionRevision()) throw new DOMException("La sesión cambió.", "AbortError");
      return simulatePayment(order.id, fixture.reference, outcome);
    },
    onSuccess: async (result) => {
      if (revision !== getSessionRevision()) return;
      await client.cancelQueries({ queryKey: key });
      if (revision !== getSessionRevision()) return;
      client.setQueryData(key, result);
      void client.invalidateQueries({ queryKey: ["private", "cart", revision] });
      void client.invalidateQueries({ queryKey: activeOrderKey(revision) });
      void client.invalidateQueries({ queryKey: ordersKey(revision) });
    },
    onError: (error) => {
      if (revision === getSessionRevision() && error instanceof ApiError && [404, 409].includes(error.status)) {
        void client.invalidateQueries({ queryKey: key });
      }
    },
  });
  if (!enabled && !fixture.result && !payment.isPending && !payment.isError) return null;

  return <Card className="gap-0 py-4">
    <CardContent className="space-y-3 px-4 sm:px-6">
      <h2 className="text-lg font-semibold">Pago de prueba</h2>
      {fixture.result ? <p role="status">{messages[fixture.result]}</p> : <>
        <p className="text-sm text-muted-foreground">Simulá el resultado sin realizar un cobro.</p>
        {payment.isPending && <p role="status">Registrando resultado…</p>}
        {payment.isError && <p role="alert" className="text-destructive">No pudimos registrar el resultado. Volvé a intentar.</p>}
        {enabled && <div className="flex flex-wrap gap-2">
          <Button type="button" disabled={payment.isPending} onClick={() => payment.mutate("APPROVED")}>Simular aprobación</Button>
          <Button type="button" variant="outline" disabled={payment.isPending} onClick={() => payment.mutate("REJECTED")}>Simular rechazo</Button>
        </div>}
      </>}
    </CardContent>
  </Card>;
}

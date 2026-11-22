import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { lazy, Suspense, useCallback, useEffect, useRef, useState } from "react";
import { getSessionRevision } from "@/features/auth/session-api";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import {
  activeOrderKey, fetchPaymentOptions, orderKey, ordersKey, paymentOptionsKey, simulateAttemptOutcome,
  reconcileCardPayment, startPayment, submitCardPayment, submitPayment, type CardData, type Order,
  type PaymentAttempt, type PaymentMethod, type PaymentOptions, type VerifiedAttemptOutcome,
} from "./checkout-api";
import { TestPaymentAction } from "./test-payment-action";

const CardPaymentForm = lazy(() => import("./card-payment-form").then(({ CardPaymentForm }) => ({ default: CardPaymentForm })));

const methodLabels: Record<PaymentMethod, string> = {
  CARD: "Card Payment",
  CHECKOUT_PRO: "Checkout Pro",
};
const statusLabels: Record<PaymentAttempt["status"], string> = {
  CREADO: "Intento creado. Podés continuar el envío.",
  ENVIADO: "El intento fue enviado. Estamos esperando el resultado.",
  PENDIENTE: "El pago está pendiente de confirmación.",
  RESULTADO_DESCONOCIDO: "El envío quedó sin respuesta. El intento sigue activo.",
  APROBADO: "Pago aprobado.",
  RECHAZADO: "Pago rechazado.",
};
type Command =
  | { type: "start"; method: PaymentMethod; key: string }
  | { type: "submit"; attemptId: string }
  | { type: "consult"; attemptId: string }
  | { type: "simulate"; attemptId: string; outcome: VerifiedAttemptOutcome };

export function PaymentMethods({ order, eligible, revision }: { order: Order; eligible: boolean; revision: number }) {
  const client = useQueryClient();
  const status = useRef<HTMLParagraphElement>(null);
  const [cardError, setCardError] = useState(false);
  const id = order.id;
  const key = paymentOptionsKey(revision, id);
  const options = useQuery({
    queryKey: key, queryFn: ({ signal }) => fetchPaymentOptions(id, signal), retry: false,
  });
  const refresh = useCallback(async () => {
    if (getSessionRevision() !== revision) return;
    await Promise.all([
      client.invalidateQueries({ queryKey: paymentOptionsKey(revision, id) }),
      client.invalidateQueries({ queryKey: orderKey(revision, id) }),
      client.invalidateQueries({ queryKey: activeOrderKey(revision) }),
      client.invalidateQueries({ queryKey: ordersKey(revision) }),
      client.invalidateQueries({ queryKey: ["private", "cart", revision] }),
    ]);
  }, [client, id, revision]);
  const payment = useMutation({
    mutationFn: (command: Command) => {
      if (getSessionRevision() !== revision) throw new DOMException("La sesión cambió.", "AbortError");
      switch (command.type) {
        case "start": return startPayment(order.id, command.method, command.key);
        case "submit": return submitPayment(order.id, command.attemptId);
        case "consult": return reconcileCardPayment(order.id, command.attemptId);
        case "simulate": return simulateAttemptOutcome(order.id, command.attemptId, command.outcome);
      }
    },
    onSettled: refresh,
  });
  const attempt = options.data?.latestAttempt;
  const cardConfig = options.data?.card;
  const cardAttempt = attempt?.nextAction === "INGRESAR_TARJETA" ? attempt.id : undefined;
  const submitCard = useCallback(async (data: CardData) => {
    if (!cardAttempt || getSessionRevision() !== revision) return;
    try {
      const result = await submitCardPayment(id, cardAttempt, data);
      if (getSessionRevision() !== revision) return;
      const cacheKey = paymentOptionsKey(revision, id);
      await client.cancelQueries({ queryKey: cacheKey });
      if (getSessionRevision() !== revision) return;
      client.setQueryData<PaymentOptions>(cacheKey, (previous) => previous?.latestAttempt?.id === cardAttempt
        ? { ...previous, latestAttempt: result } : previous);
    } catch {
      if (getSessionRevision() === revision) setCardError(true);
    } finally {
      await refresh();
      if (getSessionRevision() === revision) status.current?.focus();
    }
  }, [cardAttempt, client, id, refresh, revision]);

  useEffect(() => {
    if (payment.isSuccess) status.current?.focus();
  }, [payment.isSuccess]);
  const methods = eligible ? options.data?.methods ?? [] : [];
  const busy = payment.isPending || options.isFetching;
  const showCard = options.isPending || options.isError || attempt || methods.length > 0 || payment.isError;
  const showFixture = options.isSuccess && !cardConfig && !attempt && !payment.isPending;

  return <>
    {showCard && <Card className="gap-0 py-4">
      <CardContent className="space-y-3 px-4 sm:px-6">
        <h2 className="text-lg font-semibold">Medios de pago</h2>
        {cardConfig ? <p className="text-sm text-muted-foreground">Usá tarjetas de prueba de Mercado Pago.</p>
          : order.testPayment && <p className="text-sm text-muted-foreground">Modo de prueba local.</p>}
        {options.isPending && <p role="status">Consultando medios de pago…</p>}
        {options.isError && <p role="alert" className="text-destructive">No pudimos consultar los medios de pago.</p>}
        {payment.isPending && <p role="status">Actualizando pago…</p>}
        {(payment.isError || cardError) && <p role="alert" className="text-destructive">
          No pudimos completar la acción. Consultá el estado antes de intentar de nuevo.
        </p>}
        {attempt && <div className="space-y-1">
          <p className="font-medium">{methodLabels[attempt.method]}</p>
          <p ref={status} tabIndex={-1} role="status">{attempt.status === "APROBADO" && order.status === "EXPIRADA"
            ? "La aprobación llegó fuera de plazo y quedó registrada para conciliación."
            : cardAttempt && cardConfig ? "Completá el formulario para pagar con tarjeta."
            : cardAttempt ? "El formulario de tarjeta no está disponible."
            : statusLabels[attempt.status]}</p>
        </div>}
        {methods.length > 0 && <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap">
          {methods.map((method) => <Button key={method} type="button" variant="outline" disabled={busy || options.isError}
            onClick={() => payment.mutate({ type: "start", method, key: crypto.randomUUID() })}>
            {method === "CARD" && cardConfig ? "Pagar con tarjeta"
              : method === "CHECKOUT_PRO" && cardConfig ? "Elegir Checkout Pro (simulado)" : "Elegir " + methodLabels[method]}
          </Button>)}
        </div>}
        {(attempt?.nextAction === "CONSULTAR" || options.isError || payment.isError || cardError) && <Button
          type="button" variant="outline" disabled={busy} onClick={() => {
            payment.reset();
            if (attempt?.provider === "MERCADO_PAGO" && attempt.nextAction === "CONSULTAR") {
              setCardError(false);
              payment.mutate({ type: "consult", attemptId: attempt.id });
            } else {
              void options.refetch().then((updated) => {
                if (updated.isSuccess) setCardError(false);
              });
            }
          }}>Consultar estado</Button>}
        {eligible && cardAttempt && cardConfig && <Suspense fallback={<p role="status">Cargando el formulario de tarjeta…</p>}>
          <CardPaymentForm key={cardAttempt} config={cardConfig} blocked={cardError || options.isError} onSubmit={submitCard} />
        </Suspense>}
        {eligible && attempt?.provider === "LOCAL" && attempt.status === "CREADO" && <Button type="button" disabled={busy}
          onClick={() => payment.mutate({ type: "submit", attemptId: attempt.id })}>Continuar intento</Button>}
        {order.testPayment && attempt?.provider === "LOCAL" && attempt.nextAction === "CONSULTAR" && attempt.status !== "CREADO" &&
          <div className="space-y-2">
            <p className="text-sm text-muted-foreground">Simulá una respuesta del proveedor para este intento.</p>
            <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap">
              {(["PENDIENTE", "APROBADO", "RECHAZADO"] as const).map((outcome) =>
                <Button key={outcome} type="button" variant="outline" disabled={busy || attempt.status === outcome}
                  onClick={() => payment.mutate({ type: "simulate", attemptId: attempt.id, outcome })}>
                  Marcar {outcome.toLowerCase()}
                </Button>)}
            </div>
          </div>}
      </CardContent>
    </Card>}
    {showFixture && order.testPayment &&
      <TestPaymentAction order={order} fixture={order.testPayment} enabled={eligible} revision={revision} />}
  </>;
}

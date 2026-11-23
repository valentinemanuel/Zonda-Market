import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CreditCard, Wallet } from "lucide-react";
import { lazy, Suspense, useCallback, useEffect, useRef, useState } from "react";
import { getSessionRevision } from "@/features/auth/session-api";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import {
  activeOrderKey, fetchPaymentOptions, orderKey, ordersKey, paymentOptionsKey, simulateAttemptOutcome,
  reconcileCardPayment, reconcileCheckoutPro, startPayment, submitCardPayment, submitCheckoutPro, submitPayment,
  type CardConfig, type CardData, type Order,
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
  | { type: "open-pro"; attemptId: string }
  | { type: "consult"; attemptId: string; method: PaymentMethod }
  | { type: "simulate"; attemptId: string; outcome: VerifiedAttemptOutcome };

const checkoutOrigins = new Set(["https://www.mercadopago.com.ar", "https://www.mercadopago.com"]);
const checkoutPaths = new Set(["/checkout/v1/redirect", "/checkout/v1/payment/redirect", "/mla/checkout/start"]);

function checkoutDestination(result: PaymentAttempt) {
  if (result.nextAction !== "ABRIR_CHECKOUT_PRO" || !result.checkoutUrl) return null;
  try {
    const url = new URL(result.checkoutUrl);
    return checkoutOrigins.has(url.origin) && checkoutPaths.has(url.pathname) && !url.username && !url.password
      ? url.href : null;
  } catch {
    return null;
  }
}

export function PaymentMethods({ order, eligible, revision, returned }: {
  order: Order; eligible: boolean; revision: number; returned: boolean;
}) {
  const client = useQueryClient();
  const status = useRef<HTMLParagraphElement>(null);
  const commandInFlight = useRef(false);
  const navigationInFlight = useRef(false);
  const [leaving, setLeaving] = useState(false);
  const [cardError, setCardError] = useState(false);
  const [submittingCard, setSubmittingCard] = useState<{ attempt: string; config: CardConfig }>();
  const [checkingReturn, setCheckingReturn] = useState(false);
  const [returnError, setReturnError] = useState(false);
  const id = order.id;
  const key = paymentOptionsKey(revision, id);
  const openCheckout = useCallback((url: string) => {
    if (navigationInFlight.current) return;
    navigationInFlight.current = true;
    setLeaving(true);
    window.location.assign(url);
  }, []);
  useEffect(() => {
    const restore = () => {
      navigationInFlight.current = false;
      setLeaving(false);
    };
    window.addEventListener("pageshow", restore);
    return () => window.removeEventListener("pageshow", restore);
  }, []);
  const options = useQuery({
    queryKey: key, queryFn: ({ signal }) => fetchPaymentOptions(id, signal), retry: false,
    refetchInterval: (query) => {
      const latest = query.state.data?.latestAttempt;
      return eligible && latest?.provider === "MERCADO_PAGO"
        && ["ENVIADO", "PENDIENTE", "RESULTADO_DESCONOCIDO"].includes(latest.status) ? 4000 : false;
    },
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
    mutationFn: async (command: Command) => {
      if (getSessionRevision() !== revision) throw new DOMException("La sesión cambió.", "AbortError");
      switch (command.type) {
        case "start": {
          const attempt = await startPayment(order.id, command.method, command.key);
          if (getSessionRevision() === revision && attempt.nextAction === "ABRIR_CHECKOUT_PRO" && attempt.status === "CREADO") {
            return submitCheckoutPro(order.id, attempt.id);
          }
          return attempt;
        }
        case "submit": return submitPayment(order.id, command.attemptId);
        case "open-pro": return submitCheckoutPro(order.id, command.attemptId);
        case "consult": return command.method === "CARD" ? reconcileCardPayment(order.id, command.attemptId)
          : reconcileCheckoutPro(order.id, command.attemptId);
        case "simulate": return simulateAttemptOutcome(order.id, command.attemptId, command.outcome);
      }
    },
    onSuccess: (result, command) => {
      if (getSessionRevision() !== revision || (command.type !== "start" && command.type !== "open-pro")) return;
      const url = checkoutDestination(result);
      if (url && eligible) openCheckout(url);
    },
    onSettled: refresh,
  });
  function run(command: Command) {
    if (commandInFlight.current || navigationInFlight.current) return;
    commandInFlight.current = true;
    void payment.mutateAsync(command).catch(() => {
      // The mutation exposes failures in the payment panel.
    }).finally(() => { commandInFlight.current = false; });
  }
  const attempt = options.data?.latestAttempt;
  useEffect(() => {
    if (attempt?.status === "APROBADO") void client.invalidateQueries({ queryKey: orderKey(revision, id) });
  }, [attempt?.status, client, id, revision]);
  const cardConfig = options.data?.card;
  const cardAttempt = attempt?.nextAction === "INGRESAR_TARJETA" ? attempt.id : undefined;
  const proAttempt = attempt?.provider === "MERCADO_PAGO" && attempt.method === "CHECKOUT_PRO" ? attempt : null;
  const checkoutUrl = proAttempt ? checkoutDestination(proAttempt) : null;
  const pollAttempt = returned && proAttempt && !["CREADO", "APROBADO", "RECHAZADO"].includes(proAttempt.status)
    ? proAttempt.id : null;
  useEffect(() => {
    if (!pollAttempt) return;
    const controller = new AbortController();
    let timer: number | undefined;
    setCheckingReturn(true);
    const poll = async (remaining: number) => {
      if (controller.signal.aborted || getSessionRevision() !== revision) return;
      try {
        const result = await reconcileCheckoutPro(id, pollAttempt, controller.signal);
        if (controller.signal.aborted || getSessionRevision() !== revision) return;
        await refresh();
        if (controller.signal.aborted || getSessionRevision() !== revision) return;
        if (remaining > 1 && result.status !== "APROBADO" && result.status !== "RECHAZADO") {
          timer = window.setTimeout(() => void poll(remaining - 1), 2000);
          return;
        }
        setReturnError(false);
      } catch {
        if (!controller.signal.aborted && getSessionRevision() === revision) setReturnError(true);
      }
      if (!controller.signal.aborted) setCheckingReturn(false);
    };
    void poll(5);
    return () => {
      controller.abort();
      window.clearTimeout(timer);
      setCheckingReturn(false);
    };
  }, [id, pollAttempt, refresh, revision]);
  const submitCard = useCallback(async (data: CardData) => {
    if (!cardAttempt || !cardConfig || getSessionRevision() !== revision) return;
    setSubmittingCard({ attempt: cardAttempt, config: cardConfig });
    const minimumAnimation = new Promise<void>((resolve) => window.setTimeout(resolve, 900));
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
      await Promise.all([refresh(), minimumAnimation]);
      setSubmittingCard(undefined);
      if (getSessionRevision() === revision) status.current?.focus();
    }
  }, [cardAttempt, cardConfig, client, id, refresh, revision]);

  useEffect(() => {
    if (payment.isSuccess) status.current?.focus();
  }, [payment.isSuccess]);
  const methods = eligible ? options.data?.methods ?? [] : [];
  const busy = payment.isPending || options.isPending || checkingReturn || leaving || !!submittingCard;
  const showCard = submittingCard || options.isPending || options.isError || attempt || methods.length > 0 || payment.isError;
  const visibleCard = submittingCard ?? (eligible && cardAttempt && cardConfig ? { attempt: cardAttempt, config: cardConfig } : undefined);
  const showFixture = options.isSuccess && !cardConfig && !options.data.checkoutPro && !attempt && !payment.isPending;

  return <>
    {showCard && <Card className="gap-0 py-4">
      <CardContent className="space-y-3 px-4 sm:px-6">
        <h2 className="flex items-center gap-3 text-lg font-bold">
          <CreditCard aria-hidden="true" className="size-10 rounded-xl bg-accent p-2 text-primary" />Medios de pago
        </h2>
        {cardConfig ? <p className="text-sm text-muted-foreground">Usá tarjetas de prueba de Mercado Pago.</p>
          : options.data?.checkoutPro ? <p className="text-sm text-muted-foreground">Usá una cuenta de prueba de Mercado Pago con saldo.</p>
          : order.testPayment && <p className="text-sm text-muted-foreground">Modo de prueba local.</p>}
        {options.isPending && <p role="status">Consultando medios de pago…</p>}
        {options.isError && <p role="alert" className="text-destructive">No pudimos consultar los medios de pago.</p>}
        {(payment.isPending || leaving) && <p role="status">Actualizando pago…</p>}
        {checkingReturn && <p role="status">Consultando el pago con Mercado Pago…</p>}
        {returnError && <p role="alert" className="text-destructive">No pudimos consultar el pago. Volvé a intentarlo.</p>}
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
        {methods.length > 0 && <div className="grid gap-3 sm:grid-cols-2">
          {methods.map((method) => <Button key={method} type="button" variant="outline" disabled={busy || options.isError}
            className="h-auto min-h-14 justify-start whitespace-normal rounded-panel text-left"
            onClick={() => run({ type: "start", method, key: crypto.randomUUID() })}>
            {method === "CARD" ? <CreditCard aria-hidden="true" /> : <Wallet aria-hidden="true" />}
            {method === "CHECKOUT_PRO" && options.data?.checkoutPro ? "Pagar con saldo de Mercado Pago"
              : method === "CARD" && cardConfig ? "Pagar con tarjeta"
              : method === "CHECKOUT_PRO" && cardConfig ? "Elegir Checkout Pro (simulado)" : "Elegir " + methodLabels[method]}
          </Button>)}
        </div>}
        {(attempt?.nextAction === "CONSULTAR" || proAttempt?.status === "PENDIENTE" || options.isError
          || payment.isError || cardError || returnError) && <Button
          type="button" variant="outline" disabled={busy} onClick={() => {
            payment.reset();
            setReturnError(false);
            if (attempt?.provider === "MERCADO_PAGO" &&
                (attempt.nextAction === "CONSULTAR" || proAttempt?.status === "PENDIENTE")) {
              setCardError(false);
              run({ type: "consult", attemptId: attempt.id, method: attempt.method });
            } else {
              void options.refetch().then((updated) => {
                if (updated.isSuccess) setCardError(false);
              });
            }
          }}>Consultar estado</Button>}
        {eligible && !returned && checkoutUrl && <Button type="button" disabled={busy}
          onClick={() => openCheckout(checkoutUrl)}>
          Continuar en Mercado Pago
        </Button>}
        {eligible && proAttempt?.nextAction === "ABRIR_CHECKOUT_PRO" && proAttempt.status === "CREADO" && <Button
          type="button" disabled={busy} onClick={() => run({ type: "open-pro", attemptId: proAttempt.id })}>
          Abrir Mercado Pago
        </Button>}
        {visibleCard && <Suspense fallback={<p role="status">Cargando el formulario de tarjeta…</p>}>
          <CardPaymentForm key={visibleCard.attempt} config={visibleCard.config}
            blocked={!!submittingCard || cardError || options.isError} onSubmit={submitCard} />
        </Suspense>}
        {eligible && attempt?.provider === "LOCAL" && attempt.status === "CREADO" && <Button type="button" disabled={busy}
          onClick={() => run({ type: "submit", attemptId: attempt.id })}>Continuar intento</Button>}
        {order.testPayment && attempt?.provider === "LOCAL" && attempt.nextAction === "CONSULTAR" && attempt.status !== "CREADO" &&
          <div className="space-y-2">
            <p className="text-sm text-muted-foreground">Simulá una respuesta del proveedor para este intento.</p>
            <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap">
              {(["PENDIENTE", "APROBADO", "RECHAZADO"] as const).map((outcome) =>
                <Button key={outcome} type="button" variant="outline" disabled={busy || attempt.status === outcome}
                  onClick={() => run({ type: "simulate", attemptId: attempt.id, outcome })}>
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

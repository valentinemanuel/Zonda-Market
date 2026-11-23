import { CardPayment, initMercadoPago } from "@mercadopago/sdk-react";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ComponentProps } from "react";
import { Button } from "@/shared/ui/button";
import type { CardConfig, CardData } from "./checkout-api";

const customization: ComponentProps<typeof CardPayment>["customization"] = {
  paymentMethods: { types: { included: ["credit_card", "debit_card"] }, maxInstallments: 48 },
};

export function CardPaymentForm({ config, blocked, onSubmit }: {
  config: CardConfig;
  blocked: boolean;
  onSubmit: (card: CardData) => Promise<void>;
}) {
  const [state, setState] = useState<"loading" | "ready" | "failed">("loading");
  const [formError, setFormError] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const sending = useRef(false);
  const current = useRef({ blocked, onSubmit });
  const { publicKey, amount, payerEmail } = config;
  const initialization = useMemo(() => ({ amount: Number(amount), payer: { email: payerEmail } }), [amount, payerEmail]);

  // The SDK re-creates the Brick when the identity of onSubmit changes.
  useLayoutEffect(() => { current.current = { blocked, onSubmit }; }, [blocked, onSubmit]);

  useEffect(() => {
    initMercadoPago(publicKey, { locale: "es-AR" });
    const timer = window.setTimeout(() => setState((current) => current === "loading" ? "failed" : current), 12_000);
    return () => window.clearTimeout(timer);
  }, [publicKey]);

  const ready = useCallback(() => setState("ready"), []);
  const failed = useCallback<NonNullable<ComponentProps<typeof CardPayment>["onError"]>>((error) => {
    if (error.type === "critical") setState("failed");
    else setFormError(true);
  }, []);
  const submit = useCallback<NonNullable<ComponentProps<typeof CardPayment>["onSubmit"]>>(async (data, additional) => {
    if (current.current.blocked || sending.current) return;
    const paymentType = additional?.paymentTypeId;
    if (paymentType !== "credit_card" && paymentType !== "debit_card") {
      setFormError(true);
      return;
    }
    sending.current = true;
    setFormError(false);
    setSubmitting(true);
    try {
      const identification = data.payer.identification;
      await current.current.onSubmit({
        token: data.token,
        paymentMethodId: data.payment_method_id,
        paymentType,
        installments: data.installments,
        payer: {
          email: data.payer.email || payerEmail,
          ...(identification?.type && identification.number ? { identification } : {}),
        },
      });
    } finally {
      sending.current = false;
      setSubmitting(false);
    }
  }, [payerEmail]);

  return <div className="min-w-0 space-y-2">
    {state === "loading" && <p role="status">Cargando el formulario de tarjeta…</p>}
    {submitting && <p role="status">Enviando el pago con tarjeta…</p>}
    {formError && state === "ready" && <p role="alert" className="text-destructive">
      No pudimos preparar el pago. Revisá los campos y volvé a intentar.
    </p>}
    {state === "failed"
      ? <div className="space-y-2">
          <p role="alert" className="text-destructive">No pudimos cargar el formulario de Mercado Pago.</p>
          <Button type="button" variant="outline" onClick={() => window.location.reload()}>Recargar formulario</Button>
        </div>
      : <fieldset aria-label="Datos de tarjeta" className="min-w-0"
          disabled={blocked || submitting} aria-busy={state === "loading" || submitting}>
          <CardPayment initialization={initialization} customization={customization} locale="es-AR"
            onReady={ready} onError={failed} onSubmit={submit} />
        </fieldset>}
  </div>;
}

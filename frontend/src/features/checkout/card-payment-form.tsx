import { CardPayment, initMercadoPago } from "@mercadopago/sdk-react";
import { CreditCard, LockKeyhole } from "lucide-react";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ComponentProps } from "react";
import { Button } from "@/shared/ui/button";
import { formattedPrice } from "@/features/listings/public-listing-format";
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
  const [prefix, setPrefix] = useState("");
  const [mask, setMask] = useState<{ holder?: string; lastFour?: string }>();
  const sending = useRef(false);
  const current = useRef({ blocked, onSubmit });
  const { publicKey, amount, payerEmail } = config;
  const initialization = useMemo(() => ({ amount: Number(amount), payer: { email: payerEmail } }), [amount, payerEmail]);
  const appearance = useMemo(() => ({
    ...customization,
    visual: {
      hideFormTitle: true,
      texts: { formSubmit: "Pagar " + formattedPrice(amount) },
      style: { customVariables: { baseColor: "#b83b0b", baseColorFirstVariant: "#96300b",
        inputBackgroundColor: "#ffffff", borderRadiusMedium: "8px", fontWeightSemiBold: "600" } },
    },
  }), [amount]);

  // The SDK re-creates the Brick when the identity of onSubmit changes.
  useLayoutEffect(() => { current.current = { blocked, onSubmit }; }, [blocked, onSubmit]);

  useEffect(() => {
    initMercadoPago(publicKey, { locale: "es-AR" });
    const timer = window.setTimeout(() => setState((current) => current === "loading" ? "failed" : current), 12_000);
    return () => window.clearTimeout(timer);
  }, [publicKey]);

  const ready = useCallback(() => setState("ready"), []);
  const binChanged = useCallback((value: string) => {
    setPrefix(value?.replace(/\D/g, "").slice(0, 4) ?? "");
    setMask(undefined);
  }, []);
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
    const lastFour = /^[0-9]{4}$/.test(additional?.lastFourDigits ?? "") ? additional?.lastFourDigits : undefined;
    setMask({ lastFour, holder: additional?.cardholderName });
    setFormError(false);
    setSubmitting(true);
    try {
      const identification = data.payer.identification;
      await current.current.onSubmit({
        token: data.token,
        paymentMethodId: data.payment_method_id,
        paymentType,
        installments: data.installments,
        ...(lastFour ? { lastFour } : {}),
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

  return <div className="min-w-0 space-y-4">
    <h3 className="font-wordmark text-lg font-bold">Pago con tarjeta de crédito o débito</h3>
    {state !== "failed" && <div aria-hidden="true"
      className="mx-auto grid max-w-sm gap-4 rounded-2xl bg-linear-to-br from-brand-ink via-[#24334c] to-[#48435b] p-4 text-white shadow-float sm:gap-5 sm:p-5">
      <div className="flex items-center justify-between gap-2">
        <CreditCard className="size-7 text-brand-gold" />
        <span className="inline-flex items-center gap-1.5 text-xs text-white/85"><LockKeyhole className="size-3.5" />Mercado Pago</span>
      </div>
      <p data-card-preview="number" className="font-mono text-base tracking-wider tabular-nums sm:text-xl">
        {prefix.padEnd(4, "•")} •••• •••• {mask?.lastFour ?? "••••"}
      </p>
      <div className="flex justify-between gap-3 text-xs text-white/90">
        <span className="min-w-0 truncate">{mask?.holder || "Titular de la tarjeta"}</span>
        <span className="shrink-0">MM/AA</span>
      </div>
    </div>}
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
          <CardPayment initialization={initialization} customization={appearance} locale="es-AR"
            onReady={ready} onBinChange={binChanged} onError={failed} onSubmit={submit} />
        </fieldset>}
  </div>;
}

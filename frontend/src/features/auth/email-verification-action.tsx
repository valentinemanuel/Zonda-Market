import { useMutation } from "@tanstack/react-query";
import { Button } from "@/shared/ui/button";
import { requestEmailLink } from "./identity-email-api";

export function EmailVerificationAction({ email }: { email: string }) {
  const send = useMutation({ mutationFn: () => requestEmailLink("email-verification", email) });
  return (
    <div className="mt-3 space-y-2">
      <Button type="button" className="min-h-11" disabled={send.isPending} onClick={() => send.mutate()}>
        {send.isPending ? "Enviando…" : send.isSuccess ? "Reenviar enlace" : "Enviar enlace de verificación"}
      </Button>
      {send.isPending && <p role="status" className="sr-only">Enviando enlace de verificación…</p>}
      {send.isSuccess && <p role="status" className="text-sm">Revisá tu correo para continuar con la verificación.</p>}
      {send.isError && <p role="alert" className="text-sm text-destructive">
        No pudimos enviar la solicitud. Volvé a intentar.
      </p>}
    </div>
  );
}

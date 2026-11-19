import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { z } from "zod";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { emailField, newPasswordField } from "./credential-fields";
import { confirmEmailLink, requestEmailLink } from "./identity-email-api";

const emailSchema = z.object({ email: emailField });
const passwordSchema = z.object({ password: newPasswordField });
const fieldClass = "w-full rounded-lg border border-input px-3 py-2.5";
const linkClass = "mt-4 inline-flex min-h-11 items-center text-primary underline underline-offset-4";

export function PasswordRequestPage() {
  const [sent, setSent] = useState(false);
  const { register, handleSubmit, setError, formState: { errors, isSubmitting } } = useForm({
    resolver: zodResolver(emailSchema),
    defaultValues: { email: "" },
  });

  const submit = handleSubmit(async ({ email }) => {
    try {
      await requestEmailLink("password-reset", email);
      setSent(true);
    } catch {
      setError("root", { message: "No pudimos enviar la solicitud. Volvé a intentar." });
    }
  });

  return (
    <>
      <h1 className="text-2xl font-semibold">Recuperar acceso</h1>
      {sent ? (
        <p role="status" className="mt-4">Si ese correo tiene una cuenta con contraseña, vas a recibir un enlace para continuar.</p>
      ) : (
        <form className="mt-6 space-y-4" aria-busy={isSubmitting} noValidate onSubmit={submit}>
          <div className="space-y-1.5">
            <label htmlFor="recovery-email" className="block text-sm font-medium">Correo electrónico</label>
            <input id="recovery-email" type="email" autoComplete="email" required maxLength={254}
              className={fieldClass} aria-invalid={Boolean(errors.email)}
              aria-describedby={errors.email ? "recovery-email-error" : undefined} {...register("email")} />
            {errors.email && <p role="alert" id="recovery-email-error" className="text-sm text-destructive">{errors.email.message}</p>}
          </div>
          {errors.root && <p role="alert" className="text-sm text-destructive">{errors.root.message}</p>}
          <Button type="submit" className="min-h-11 w-full" disabled={isSubmitting}>
            {isSubmitting ? "Enviando…" : "Enviar enlace de recuperación"}
          </Button>
          {isSubmitting && <p role="status" className="sr-only">Enviando enlace de recuperación…</p>}
        </form>
      )}
      <Link to="/ingresar" className={linkClass}>Volver a ingresar</Link>
    </>
  );
}

export function VerificationLinkPage() {
  return <ConfirmLinkPage resetPassword={false} />;
}

export function ResetLinkPage() {
  return <ConfirmLinkPage resetPassword />;
}

function ConfirmLinkPage({ resetPassword }: { resetPassword: boolean }) {
  const { hash, pathname, state } = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const token = new URLSearchParams(hash.slice(1)).get("token") ?? "";
  const done = !hash && state?.emailLinkConfirmed === true;
  const { register, handleSubmit, reset, setError, formState: { errors, isSubmitting } } = useForm({
    resolver: resetPassword ? zodResolver(passwordSchema) : undefined,
    defaultValues: { password: "" },
  });
  const title = resetPassword ? "Restablecer contraseña" : "Verificar correo";
  const next = resetPassword ? "/ingresar" : "/cuenta";

  const submit = handleSubmit(async ({ password }) => {
    try {
      await confirmEmailLink(resetPassword ? "password-reset" : "email-verification", token,
        resetPassword ? password : undefined);
      if (!resetPassword) {
        void queryClient.invalidateQueries({ queryKey: ["private", "me"] });
      }
      reset();
      navigate(pathname, { replace: true, state: { emailLinkConfirmed: true } });
    } catch (error) {
      if (error instanceof ApiError && error.code === "INVALID_EMAIL_LINK") {
        reset();
        navigate(pathname, { replace: true });
        return;
      }
      const message = error instanceof ApiError && error.status === 429
        ? "Hubo demasiados intentos. Volvé a intentar más tarde."
        : "No pudimos confirmar el enlace. Volvé a intentar.";
      setError("root", { message });
    }
  });

  return (
    <>
      <h1 className="text-2xl font-semibold">{title}</h1>
      {done ? (
        <>
          <p role="status" className="mt-4">
            {resetPassword ? "Contraseña actualizada. Ya podés ingresar con la nueva." : "Tu correo está verificado."}
          </p>
          <Link to={next} className={linkClass}>{resetPassword ? "Ingresar" : "Ir a Mi cuenta"}</Link>
        </>
      ) : !/^[A-Za-z0-9_-]{43}$/.test(token) ? (
        <>
          <p role="alert" className="mt-4 text-destructive">Este enlace venció o ya no está disponible.</p>
          <Link to={resetPassword ? "/recuperar-acceso" : next} className={linkClass}>
            {resetPassword ? "Pedir un enlace nuevo" : "Volver a Mi cuenta"}
          </Link>
        </>
      ) : (
        <form className="mt-6 space-y-4" aria-busy={isSubmitting} noValidate onSubmit={submit}>
          {resetPassword && (
            <div className="space-y-1.5">
              <label htmlFor="new-password" className="block text-sm font-medium">Nueva contraseña</label>
              <input id="new-password" type="password" autoComplete="new-password" maxLength={72} required
                className={fieldClass} aria-invalid={Boolean(errors.password)}
                aria-describedby={errors.password ? "new-password-error" : "new-password-hint"} {...register("password")} />
              {errors.password
                ? <p id="new-password-error" role="alert" className="text-sm text-destructive">{errors.password.message}</p>
                : <p id="new-password-hint" className="text-sm text-muted-foreground">Usá al menos 12 caracteres.</p>}
            </div>
          )}
          {errors.root && <p role="alert" className="text-sm text-destructive">{errors.root.message}</p>}
          <Button type="submit" className="min-h-11 w-full" disabled={isSubmitting}>
            {isSubmitting ? "Confirmando…" : resetPassword ? "Guardar contraseña" : "Confirmar correo"}
          </Button>
          {isSubmitting && <p role="status" className="sr-only">Confirmando enlace…</p>}
        </form>
      )}
    </>
  );
}

import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { z } from "zod";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { listingPublicPath } from "@/features/listings/public-listing-format";
import { authenticate } from "./session-api";
import { useGoogleOptions } from "./google-auth";
import { emailField, newPasswordField, passwordField } from "./credential-fields";

const credentials = z.object({ email: emailField, password: passwordField });
const registration = credentials.extend({ password: newPasswordField });

export function LoginPage() {
  return <AuthPage mode="login" />;
}

export function RegisterPage() {
  return <AuthPage mode="register" />;
}

function AuthPage({ mode }: { mode: "login" | "register" }) {
  const createAccount = mode === "register";
  const title = createAccount ? "Crear cuenta" : "Iniciar sesión";
  const navigate = useNavigate();
  const { state } = useLocation();
  const listingSlug = typeof state?.listingSlug === "string" && /^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(state.listingSlug)
    ? state.listingSlug : null;
  const cartListingSlug = typeof state?.cartListingSlug === "string" && /^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(state.cartListingSlug)
    ? state.cartListingSlug : null;
  const google = useGoogleOptions();
  const { register, handleSubmit, setError, formState: { errors, isSubmitting } } = useForm({
    resolver: zodResolver(createAccount ? registration : credentials),
    defaultValues: { email: "", password: "" },
  });
  const submit = handleSubmit(async (data) => {
    try {
      await authenticate(mode, data);
      const orderHash = typeof state?.orderHash === "string" && /^#suborden-[0-9a-f-]{36}$/i.test(state.orderHash)
        ? state.orderHash : "";
      const returnPath = cartListingSlug ? listingPublicPath(cartListingSlug)
        : listingSlug ? listingPublicPath(listingSlug) + "#preguntas"
        : typeof state?.returnToOrder === "string" && /^[0-9a-f-]{36}$/i.test(state.returnToOrder)
          ? (state.checkoutResult === true ? "/checkout/resultado/" : "/checkout/reserva/") + state.returnToOrder + orderHash
        : typeof state?.returnToSellerSubOrder === "string" && /^[0-9a-f-]{36}$/i.test(state.returnToSellerSubOrder)
          ? "/vendedor/pedidos?suborden=" + state.returnToSellerSubOrder
        : typeof state?.returnToDispute === "string" && /^[0-9a-f-]{36}$/i.test(state.returnToDispute)
          ? "/reclamos/" + state.returnToDispute
        : state?.returnToNotifications === true ? "/notificaciones"
        : state?.returnToSellerSales === true ? "/vendedor/ventas"
        : state?.returnToOrders === true ? "/mis-ordenes"
        : state?.returnToCheckout === true ? "/checkout"
        : state?.returnToCart === true ? "/carrito"
        : createAccount ? "/cuenta" : "/catalogo";
      navigate(returnPath, {
        replace: true,
        state: listingSlug || cartListingSlug ? { catalogSearch: state?.catalogSearch } : undefined,
      });
    } catch (error) {
      const status = error instanceof ApiError ? error.status : 0;
      const message = status === 401 ? "El correo o la contraseña son incorrectos."
        : status === 409 ? "El correo ya está registrado."
        : status === 400 ? "Revisá el correo y la contraseña."
        : "No pudimos completar la solicitud. Volvé a intentar.";
      setError("root", { message });
    }
  });

  return (
    <>
      <h1 className="store-section-title">{title}</h1>
      <form className="mt-6 space-y-4" noValidate onSubmit={submit} aria-busy={isSubmitting}>
        <div className="space-y-1.5">
          <label htmlFor="email" className="block text-sm font-medium">Correo electrónico</label>
          <input id="email" type="email" autoComplete="email" maxLength={254} required
            className="w-full rounded-lg border border-input px-3 py-2.5"
            aria-invalid={Boolean(errors.email)} aria-describedby={errors.email ? "email-error" : undefined}
            {...register("email")} />
          {errors.email && <p role="alert" id="email-error" className="text-sm text-destructive">{errors.email.message}</p>}
        </div>
        <div className="space-y-1.5">
          <label htmlFor="password" className="block text-sm font-medium">Contraseña</label>
          <input id="password" type="password" autoComplete={createAccount ? "new-password" : "current-password"}
            maxLength={72} required className="w-full rounded-lg border border-input px-3 py-2.5"
            aria-invalid={Boolean(errors.password)}
            aria-describedby={errors.password ? "password-error" : createAccount ? "password-hint" : undefined}
            {...register("password")} />
          {errors.password
            ? <p role="alert" id="password-error" className="text-sm text-destructive">{errors.password.message}</p>
            : createAccount && <p id="password-hint" className="text-sm text-muted-foreground">Usá al menos 12 caracteres.</p>}
        </div>
        {errors.root && <p role="alert" className="text-sm text-destructive">{errors.root.message}</p>}
        <Button type="submit" className="min-h-11 w-full" disabled={isSubmitting}>
          {isSubmitting ? "Enviando…" : title}
        </Button>
      </form>
      {google.data?.enabled && (
        <Button asChild variant="outline" className="mt-4 min-h-11 w-full">
          <a href="/oauth2/authorization/google">Continuar con Google</a>
        </Button>
      )}
      {!createAccount && (
        <div className="mt-3">
          <Link className="inline-flex min-h-11 items-center text-primary underline underline-offset-4" to="/recuperar-acceso">
            Olvidé mi contraseña
          </Link>
        </div>
      )}
      <Link className="mt-5 inline-flex min-h-11 items-center font-medium text-primary underline underline-offset-4"
        to={createAccount ? "/ingresar" : "/registro"} state={state}>
        {createAccount ? "Ya tengo una cuenta" : "Crear una cuenta"}
      </Link>
    </>
  );
}

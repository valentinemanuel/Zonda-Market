import { zodResolver } from "@hookform/resolvers/zod";
import { ArrowRight, Eye, EyeOff, LockKeyhole, ShieldCheck, ShoppingBag, Truck, UserRound } from "lucide-react";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { z } from "zod";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import { listingPublicPath } from "@/features/listings/public-listing-format";
import { authenticate } from "./session-api";
import { useGoogleOptions } from "./google-auth";
import { emailField, newPasswordField, passwordField } from "./credential-fields";

const credentials = z.object({ email: emailField, password: passwordField, rememberMe: z.boolean() });
const registration = credentials.extend({ password: newPasswordField });
const benefits = [
  { icon: Truck, title: "Envíos con seguimiento" },
  { icon: ShieldCheck, title: "Pagos protegidos" },
  { icon: ShoppingBag, title: "Comprá y vendé en un solo lugar" },
];

export function LoginPage() {
  return <AuthPage mode="login" />;
}

export function RegisterPage() {
  return <AuthPage mode="register" />;
}

function AuthPage({ mode }: { mode: "login" | "register" }) {
  const createAccount = mode === "register";
  const title = createAccount ? "Crear cuenta" : "Iniciar sesión";
  const [showPassword, setShowPassword] = useState(false);
  const navigate = useNavigate();
  const { state } = useLocation();
  const listingSlug = typeof state?.listingSlug === "string" && /^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(state.listingSlug)
    ? state.listingSlug : null;
  const cartListingSlug = typeof state?.cartListingSlug === "string" && /^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(state.cartListingSlug)
    ? state.cartListingSlug : null;
  const checkoutSku = typeof state?.checkoutSku === "string" && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(state.checkoutSku)
    ? state.checkoutSku : null;
  const google = useGoogleOptions();
  const { register, handleSubmit, setError, formState: { errors, isSubmitting } } = useForm({
    resolver: zodResolver(createAccount ? registration : credentials),
    defaultValues: { email: "", password: "", rememberMe: false },
  });
  const submit = handleSubmit(async (data) => {
    try {
      const { rememberMe, ...credentials } = data;
      await authenticate(mode, createAccount ? credentials : { ...credentials, rememberMe });
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
        : state?.returnToCheckout === true ? (checkoutSku ? "/checkout?sku=" + encodeURIComponent(checkoutSku) : "/checkout")
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
    <div className="mx-auto grid w-full max-w-[70rem] gap-4 md:min-h-[34rem] md:grid-cols-[minmax(0,1fr)_minmax(0,1fr)] md:gap-0 md:rounded-2xl md:border md:bg-card md:shadow-float">
      <aside className="auth-benefits order-2 min-w-0 rounded-2xl p-5 text-white md:order-0 md:rounded-e-none md:p-8 lg:flex lg:flex-col lg:justify-center lg:p-11">
        <p className="max-w-[12ch] font-wordmark text-3xl leading-[1.02] font-extrabold tracking-tight lg:text-5xl">
          {createAccount ? "Encontrá tu lugar" : "Tu próxima compra"} <span className="text-brand-gold">{createAccount ? "en Zonda" : "empieza acá"}</span>
        </p>
        <ul role="list" className="mt-5 grid max-w-sm gap-2.5 lg:mt-8">
          {benefits.map(({ icon: Icon, title }) => <li key={title}
            className="flex items-center gap-3 rounded-xl border border-white/18 bg-white/9 p-2.5 text-sm font-semibold shadow-panel backdrop-blur-sm">
            <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-white/10 text-brand-gold">
              <Icon aria-hidden="true" className="size-4.5" />
            </span>{title}
          </li>)}
        </ul>
      </aside>
      <Card className="order-1 min-w-0 justify-center rounded-2xl bg-card py-0 md:order-0 md:rounded-s-none md:border-0 md:shadow-none">
      <CardContent className="mx-auto w-full max-w-lg p-5 sm:p-8 lg:p-10">
      <header className="mb-5">
        <UserRound aria-hidden="true" className="mb-3 size-10 rounded-xl bg-accent p-2.5 text-primary" />
        <h1 className="font-wordmark text-3xl font-extrabold tracking-tight sm:text-4xl">{title}</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          {createAccount ? "Usá tu cuenta para comprar, vender y seguir tus pedidos." : "Accedé a tus compras y retomá tu carrito."}
        </p>
      </header>
      {google.data?.enabled && <>
        <Button asChild variant="outline" className="relative min-h-12 w-full border-[#d1d5db] bg-white px-10 font-semibold text-[#1f1f1f] hover:bg-[#f8fafc] hover:text-[#1f1f1f]">
          <a href="/oauth2/authorization/google">
            <img src="/brand/google-g.svg" alt="" width={20} height={20} className="absolute start-3 size-5" />Continuar con Google
          </a>
        </Button>
        <p className="my-4 flex items-center gap-3 text-center text-xs text-muted-foreground before:h-px before:flex-1 before:bg-border after:h-px after:flex-1 after:bg-border">
          {createAccount ? "o registrate con tu email" : "o ingresá con tu email"}
        </p>
      </>}
      <form className="space-y-4" noValidate onSubmit={submit} aria-busy={isSubmitting}>
        <div className="space-y-1.5">
          <label htmlFor="email" className="block text-sm font-medium">Correo electrónico</label>
          <input id="email" type="email" autoComplete="username" maxLength={254} required
            className="auth-field min-h-12 w-full rounded-lg border border-input bg-[#f9fafb] px-3 py-2.5"
            aria-invalid={Boolean(errors.email)} aria-describedby={errors.email ? "email-error" : undefined}
            {...register("email")} />
          {errors.email && <p role="alert" id="email-error" className="text-sm text-destructive">{errors.email.message}</p>}
        </div>
        <div className="space-y-1.5">
          <label htmlFor="password" className="block text-sm font-medium">Contraseña</label>
          <div className="relative">
            <input id="password" type={showPassword ? "text" : "password"} autoComplete={createAccount ? "new-password" : "current-password"}
              maxLength={72} required className="auth-field min-h-12 w-full rounded-lg border border-input bg-[#f9fafb] py-2.5 pr-12 pl-3"
              aria-invalid={Boolean(errors.password)}
              aria-describedby={errors.password ? "password-error" : createAccount ? "password-hint" : undefined}
              {...register("password")} />
            <Button type="button" variant="ghost" size="icon" className="absolute top-0.5 right-0.5 size-11"
              aria-label={showPassword ? "Ocultar clave" : "Mostrar clave"} onClick={() => setShowPassword(!showPassword)}>
              {showPassword ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
            </Button>
          </div>
          {errors.password
            ? <p role="alert" id="password-error" className="text-sm text-destructive">{errors.password.message}</p>
            : createAccount && <p id="password-hint" className="text-sm text-muted-foreground">Usá al menos 12 caracteres.</p>}
        </div>
        {!createAccount && <div className="flex flex-wrap items-center justify-between gap-x-3">
          <label className="inline-flex min-h-11 cursor-pointer items-center gap-2 text-sm font-medium">
            <input type="checkbox" className="size-4 accent-primary" {...register("rememberMe")} />Recordarme
          </label>
          <Link className="inline-flex min-h-11 items-center text-sm font-semibold text-primary underline-offset-4 hover:underline" to="/recuperar-acceso">
            ¿Olvidaste tu contraseña?
          </Link>
        </div>}
        {errors.root && <p role="alert" className="text-sm text-destructive">{errors.root.message}</p>}
        <Button type="submit" className="min-h-12 w-full" disabled={isSubmitting}>
          <LockKeyhole aria-hidden="true" />{isSubmitting ? "Enviando…" : title}<ArrowRight aria-hidden="true" />
        </Button>
      </form>
      <div className="mt-4 flex flex-wrap items-center justify-center gap-x-2 border-t pt-2 text-sm">
        <span className="text-muted-foreground">{createAccount ? "¿Ya estás registrado?" : "¿Sos nuevo en Zonda?"}</span>
        <Link className="inline-flex min-h-11 items-center font-semibold text-primary underline underline-offset-4"
          to={createAccount ? "/ingresar" : "/registro"} state={state}>
          {createAccount ? "Ya tengo una cuenta" : "Crear una cuenta"}
        </Link>
      </div>
      </CardContent>
      </Card>
    </div>
  );
}

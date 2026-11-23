import { useQuery } from "@tanstack/react-query";
import { CircleCheck, Clock3, LogOut, ShieldCheck, UserRound } from "lucide-react";
import { useState, useSyncExternalStore } from "react";
import { Link, Navigate, useNavigate } from "react-router-dom";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { currentAccount, getSessionRevision, logout, subscribeToSession } from "./session-api";
import { GoogleSecurity } from "./google-security";
import { EmailVerificationAction } from "./email-verification-action";
import { AccountProfile, AccountSeller } from "./account-profile";
import { AccountView, AccountSection } from "./account-layout";

export function AccountPage() {
  const navigate = useNavigate();
  const [closing, setClosing] = useState(false);
  const [logoutError, setLogoutError] = useState(false);
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);

  const account = useQuery({
    queryKey: ["private", "me", revision],
    queryFn: ({ signal }) => currentAccount(signal),
    retry: false,
    staleTime: 30_000,
  });

  async function closeSession() {
    setClosing(true);
    setLogoutError(false);
    try {
      await logout();
      navigate("/ingresar", { replace: true });
    } catch {
      setLogoutError(true);
      setClosing(false);
    }
  }

  if (account.error instanceof ApiError && account.error.status === 401) {
    return <Navigate to="/ingresar" replace />;
  }
  return (
    <AccountView title="Mi cuenta" description="Gestioná tus datos y encontrá tus direcciones y órdenes."
      action={account.isSuccess && <div className="flex flex-wrap gap-2">
        {account.data.roles.includes("ADMIN") && (
          <Button asChild className="min-h-11"><Link to="/admin">Moderación</Link></Button>
        )}
        <Button type="button" variant="outline"
          className="min-h-11 border-hero-border bg-hero-panel text-white hover:bg-hero-border hover:text-white"
          disabled={closing} onClick={() => void closeSession()}>
          <LogOut aria-hidden="true" />
          {closing ? "Cerrando sesión…" : "Cerrar sesión"}
        </Button>
      </div>}>
      {logoutError && <p role="alert" className="mb-4 text-sm text-destructive">No pudimos cerrar la sesión. Volvé a intentar.</p>}
      {account.isPending && <p role="status" className="rounded-2xl border bg-card p-6 text-muted-foreground">Cargando tu cuenta…</p>}
      {account.isError && (
        <div className="space-y-4 rounded-2xl border bg-card p-6">
          <p role="alert" className="text-destructive">No pudimos cargar tu cuenta. Volvé a intentar.</p>
          <Button type="button" onClick={() => void account.refetch()}>Reintentar</Button>
        </div>
      )}
      {account.isSuccess && (
        <div className="grid items-start gap-4 lg:grid-cols-2 lg:gap-5">
          <AccountSection id="profile-title" title="Perfil" icon={UserRound}>
            <AccountProfile key={revision} account={account.data} revision={revision} />
          </AccountSection>
          <AccountSection id="account-security" title="Seguridad de cuenta" icon={ShieldCheck}>
            <p className="text-sm font-medium">Correo electrónico</p>
            <p className="mt-1 text-sm wrap-anywhere">{account.data.email}</p>
            <p className={`mt-3 inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${
              account.data.emailVerified ? "bg-emerald-50 text-success" : "bg-amber-50 text-amber-900"
            }`}>
              {account.data.emailVerified ? <CircleCheck aria-hidden="true" className="size-4" /> : <Clock3 aria-hidden="true" className="size-4" />}
              {account.data.emailVerified ? "Verificado" : "Pendiente de verificación"}
            </p>
            {!account.data.emailVerified && <EmailVerificationAction key={account.data.id} email={account.data.email} />}
            <GoogleSecurity key={revision} revision={revision} />
          </AccountSection>
          <AccountSeller key={revision} account={account.data} revision={revision} />
        </div>
      )}
    </AccountView>
  );
}

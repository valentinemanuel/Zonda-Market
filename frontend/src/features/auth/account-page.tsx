import { useQuery } from "@tanstack/react-query";
import { useState, useSyncExternalStore } from "react";
import { Navigate, useNavigate } from "react-router-dom";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { currentAccount, getSessionRevision, logout, subscribeToSession } from "./session-api";

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
  if (account.isError) {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-semibold">Mi cuenta</h1>
        <p role="alert" className="text-destructive">No pudimos cargar tu cuenta. Volvé a intentar.</p>
        <Button type="button" onClick={() => void account.refetch()}>Reintentar</Button>
      </div>
    );
  }
  if (account.isPending) {
    return <p role="status" className="text-muted-foreground">Cargando tu cuenta…</p>;
  }

  return (
    <>
      <h1 className="text-2xl font-semibold">Mi cuenta</h1>
      <dl className="mt-6 space-y-1">
        <dt className="text-sm font-medium text-muted-foreground">Correo electrónico</dt>
        <dd className="break-all">{account.data.email}</dd>
        <dt className="pt-3 text-sm font-medium text-muted-foreground">Estado del correo</dt>
        <dd>{account.data.emailVerified ? "Verificado" : "Pendiente de verificación"}</dd>
      </dl>
      {logoutError && <p role="alert" className="mt-4 text-sm text-destructive">No pudimos cerrar la sesión. Volvé a intentar.</p>}
      <Button type="button" variant="outline" className="mt-6 min-h-11" disabled={closing}
        onClick={() => void closeSession()}>
        {closing ? "Cerrando sesión…" : "Cerrar sesión"}
      </Button>
    </>
  );
}

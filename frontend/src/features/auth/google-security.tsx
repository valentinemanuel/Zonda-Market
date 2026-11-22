import { useQuery } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { googleLinkStatus, startGoogleLink, useGoogleOptions } from "./google-auth";

export function GoogleSecurity({ revision }: { revision: number }) {
  const options = useGoogleOptions();
  const status = useQuery({
    queryKey: ["private", "google-link", revision],
    queryFn: ({ signal }) => googleLinkStatus(signal),
    enabled: options.data?.enabled === true,
    retry: false,
  });

  if (!options.data?.enabled) {
    return null;
  }
  return (
    <div className="mt-5 border-t pt-4">
      <h3 className="text-sm font-semibold">Acceso con Google</h3>
      {status.isPending ? <p role="status" className="mt-2">Consultando vinculación…</p>
        : status.isError ? (
          <div className="mt-2 space-y-2">
            <p role="alert" className="text-destructive">No pudimos consultar la vinculación con Google.</p>
            <Button variant="outline" onClick={() => void status.refetch()}>Reintentar</Button>
          </div>
        ) : status.data.linked ? <p className="mt-2">Google vinculado</p> : <LinkGoogleForm />}
    </div>
  );
}

function LinkGoogleForm() {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const password = new FormData(event.currentTarget).get("current-password")?.toString() ?? "";
    setError("");
    setPending(true);
    try {
      const { authorizationUrl } = await startGoogleLink(password);
      window.location.assign(authorizationUrl);
    } catch (failure) {
      setError(failure instanceof ApiError && failure.code === "INVALID_CREDENTIALS"
        ? "La contraseña es incorrecta."
        : "No pudimos iniciar la vinculación. Volvé a intentar.");
      setPending(false);
    }
  }

  return (
    <details className="mt-2">
      <summary className="min-h-11 content-center cursor-pointer font-medium text-primary">Vincular Google</summary>
      <form onSubmit={(event) => void submit(event)} className="mt-2 space-y-3" aria-busy={pending}>
        <p className="text-sm text-muted-foreground">Confirmá tu contraseña de Zonda y elegí el mismo correo en Google.</p>
        <label htmlFor="link-password" className="block text-sm font-medium">Contraseña actual</label>
        <input id="link-password" name="current-password" type="password" autoComplete="current-password"
          maxLength={72} required className="w-full rounded-lg border border-input px-3 py-2.5"
          aria-describedby={error ? "google-link-error" : undefined} />
        {error && <p role="alert" id="google-link-error" className="text-sm text-destructive">{error}</p>}
        <Button type="submit" disabled={pending}>{pending ? "Conectando…" : "Continuar con la vinculación"}</Button>
      </form>
    </details>
  );
}

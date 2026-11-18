import { useQuery } from "@tanstack/react-query";
import { replace } from "react-router-dom";
import { ApiError, request } from "@/shared/api/http-client";
import { authenticatedRequest, exchangeGoogleCode } from "./session-api";

export function useGoogleOptions() {
  return useQuery({
    queryKey: ["google-options"],
    queryFn: async ({ signal }): Promise<{ enabled: boolean }> =>
      (await request("/api/v1/auth/oauth/google", { signal })).json(),
    staleTime: 60_000,
    retry: false,
  });
}

export async function googleLinkStatus(signal: AbortSignal): Promise<{ linked: boolean }> {
  return (await authenticatedRequest("/api/v1/me/identities/google", { signal })).json();
}

export async function startGoogleLink(password: string): Promise<{ authorizationUrl: string }> {
  return (await authenticatedRequest("/api/v1/me/identities/google/start", {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Zonda-CSRF": "1" },
    body: JSON.stringify({ password }),
  })).json();
}

export async function googleReturnLoader() {
  const url = new URL(window.location.href);
  const params = new URLSearchParams(url.hash.slice(1));
  window.history.replaceState(window.history.state, "", url.pathname + url.search);
  const failure = params.get("error");

  if (failure === "denied") {
    return { message: "Cancelaste el acceso con Google. Podés volver a intentar." };
  }
  if (failure === "unavailable") {
    return { message: "El acceso con Google no está disponible desde esta dirección." };
  }
  const code = params.get("code");
  if (!code || !/^[A-Za-z0-9_-]{43}$/.test(code)) {
    return { message: "No pudimos confirmar el acceso con Google. Volvé a iniciar el proceso." };
  }

  try {
    await exchangeGoogleCode(code);
    return replace("/cuenta");
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.code === "GOOGLE_LINK_REQUIRED") {
        return { message: "Ese correo ya tiene una cuenta. Ingresá con tu contraseña y vinculá Google desde Seguridad de cuenta." };
      }
      if (error.code === "GOOGLE_EMAIL_MISMATCH") {
        return { message: "Elegí en Google el mismo correo que usás en Zonda." };
      }
      if (error.code === "GOOGLE_IDENTITY_CONFLICT") {
        return { message: "La cuenta o la identidad de Google ya tienen una vinculación." };
      }
    }
    return { message: "No pudimos confirmar el acceso con Google. Volvé a iniciar el proceso." };
  }
}

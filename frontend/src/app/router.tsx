import { createBrowserRouter, Link, Navigate } from "react-router-dom";
import { CategoryPage } from "@/features/categories/category-page";
import { StatusPage } from "@/features/status/status-page";
import { AccountPage } from "@/features/auth/account-page";
import { googleReturnLoader } from "@/features/auth/google-auth";
import { GoogleReturnPage } from "@/features/auth/google-return-page";
import { PrivateLayout, SiteLayout } from "./layouts";

export const router = createBrowserRouter([
  {
    element: <SiteLayout />,
    children: [
      { index: true, element: <Navigate to="/estado" replace /> },
      { path: "estado", element: <StatusPage /> },
      { path: "categorias/:id", element: <CategoryPage /> },
      {
        element: <PrivateLayout />,
        hydrateFallbackElement: <p role="status">Cargando…</p>,
        children: [
          { path: "cuenta", element: <AccountPage /> },
          { path: "ingresar", lazy: async () => ({ Component: (await import("@/features/auth/auth-page")).LoginPage }) },
          { path: "registro", lazy: async () => ({ Component: (await import("@/features/auth/auth-page")).RegisterPage }) },
          { path: "recuperar-acceso", lazy: async () => ({ Component: (await import("@/features/auth/email-link-pages")).PasswordRequestPage }) },
          { path: "verificar-correo", lazy: async () => ({ Component: (await import("@/features/auth/email-link-pages")).VerificationLinkPage }) },
          { path: "restablecer-clave", lazy: async () => ({ Component: (await import("@/features/auth/email-link-pages")).ResetLinkPage }) },
          { path: "oauth/retorno", loader: googleReturnLoader, element: <GoogleReturnPage />,
            hydrateFallbackElement: <p role="status">Confirmando acceso con Google…</p> },
        ],
      },
      {
        path: "*",
        element: (
          <>
            <h1 className="text-2xl font-semibold">Página no encontrada</h1>
            <Link to="/estado" className="mt-4 inline-block underline underline-offset-4">Volver al estado del sistema</Link>
          </>
        ),
      },
    ],
  },
]);

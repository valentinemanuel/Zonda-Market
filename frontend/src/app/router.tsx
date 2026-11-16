import { createBrowserRouter, Link, Navigate } from "react-router-dom";
import { StatusPage } from "@/features/status/status-page";
import { PrivateLayout, SiteLayout } from "./layouts";

export const router = createBrowserRouter([
  {
    element: <SiteLayout />,
    children: [
      { index: true, element: <Navigate to="/estado" replace /> },
      { path: "estado", element: <StatusPage /> },
      {
        path: "cuenta",
        element: <PrivateLayout />,
        children: [
          {
            index: true,
            element: (
              <>
                <h1 className="text-2xl font-semibold">Cuenta</h1>
                <p className="mt-2 text-muted-foreground">El acceso a tu cuenta estará disponible en la etapa de identidad.</p>
                <Link to="/estado" className="mt-6 inline-block underline underline-offset-4">Volver al estado del sistema</Link>
              </>
            ),
          },
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

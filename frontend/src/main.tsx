import "@fontsource-variable/inter";
import "./index.css";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider } from "react-router-dom";
import { router } from "./app/router";
import { subscribeToSession } from "./features/auth/session-api";

const queryClient = new QueryClient();
subscribeToSession(() => queryClient.removeQueries({ queryKey: ["private"] }));
const root = document.getElementById("root");

if (!root) {
  throw new Error("Falta el nodo raíz de la aplicación");
}

createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);

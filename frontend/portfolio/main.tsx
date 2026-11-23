import "@fontsource-variable/inter";
import "./portfolio.css";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter, Link, Navigate, RouterProvider, useParams } from "react-router-dom";
import { SiteLayout } from "@/app/layouts";
import { subscribeToSession } from "@/features/auth/session-api";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import { setHttpTransport } from "@/shared/api/http-client";

function SellerCatalog() {
  const { slug = "" } = useParams();
  return <Navigate to={{ pathname: "/catalogo", search: new URLSearchParams({ seller: slug }).toString() }} replace />;
}

function DemoUnavailable() {
  return <Card className="mx-auto max-w-2xl">
    <CardContent className="space-y-4">
      <h1 className="store-section-title">Disponible en el proyecto completo</h1>
      <p className="text-muted-foreground">La demo de portfolio incluye el catálogo, las fichas y el carrito. Podés probar cuentas, pedidos y checkout al levantar Zonda Market con Docker Compose.</p>
      <div className="flex flex-wrap gap-2">
        <Button asChild><Link to="/catalogo">Volver al catálogo</Link></Button>
        <Button asChild variant="outline"><a href="https://github.com/valentinemanuel/Zonda-Market#arranque-local">Ver instalación</a></Button>
      </div>
    </CardContent>
  </Card>;
}

setHttpTransport(async (path, init) => (await import("./transport")).servePortfolio(path, init));
const queryClient = new QueryClient();
subscribeToSession(() => queryClient.removeQueries({ queryKey: ["private"] }));

const router = createBrowserRouter([{
  element: <SiteLayout demoNotice={<div className="bg-brand-ink py-1.5 text-center text-xs font-medium text-brand-gold">
    <span className="store-frame block">Demo de portfolio · Datos ficticios y carrito de prueba</span>
  </div>} />,
  hydrateFallbackElement: <p role="status" className="store-frame py-5">Cargando Zonda Market…</p>,
  children: [
    { index: true, element: <Navigate to="/catalogo" replace /> },
    { path: "catalogo", lazy: async () => ({ Component: (await import("@/features/listings/catalog-page")).PublicCatalogPage }) },
    { path: "publicaciones/:slug", lazy: async () => {
      const { PublicListingPage } = await import("@/features/listings/listing-detail");
      return { Component: () => <PublicListingPage readOnlyQuestions /> };
    } },
    { path: "categorias/:id", lazy: async () => ({ Component: (await import("@/features/categories/category-page")).CategoryPage }) },
    { path: "vendedores/:slug", element: <SellerCatalog /> },
    { path: "carrito", lazy: async () => ({ Component: (await import("@/features/cart/cart-page")).CartPage }) },
    { path: "informacion/:topic", lazy: async () => ({ Component: (await import("@/app/store-information")).StoreInformation }) },
    { path: "*", element: <DemoUnavailable /> },
  ],
}]);

const root = document.getElementById("root");
if (!root) throw new Error("Falta el nodo raíz de la demo");
createRoot(root).render(<StrictMode>
  <QueryClientProvider client={queryClient}>
    <RouterProvider router={router} />
  </QueryClientProvider>
</StrictMode>);

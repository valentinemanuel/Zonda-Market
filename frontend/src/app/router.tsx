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
      { index: true, element: <Navigate to="/catalogo" replace /> },
      { path: "catalogo", lazy: async () => ({ Component: (await import("@/features/listings/catalog-page")).PublicCatalogPage }) },
      { path: "estado", element: <StatusPage /> },
      { path: "carrito", lazy: async () => ({ Component: (await import("@/features/cart/cart-page")).CartPage }) },
      { path: "checkout", lazy: async () => ({ Component: (await import("@/features/checkout/checkout-page")).CheckoutPage }) },
      { path: "checkout/reserva/:id", lazy: async () => ({ Component: (await import("@/features/checkout/reservation-page")).ReservationPage }) },
      { path: "vendedor", lazy: async () => ({ Component: (await import("@/features/auth/seller-pages")).SellerPanelPage }) },
      { path: "vendedor/inventario", lazy: async () => ({ Component: (await import("@/features/listings/seller-catalog-page")).InventoryPage }) },
      { path: "vendedor/preguntas", lazy: async () => ({ Component: (await import("@/features/listings/listing-questions")).SellerQuestionsPage }) },
      { path: "vendedor/publicaciones", lazy: async () => ({ Component: (await import("@/features/listings/seller-catalog-page")).ListingsPage }) },
      { path: "vendedor/publicaciones/nueva", lazy: async () => ({ Component: (await import("@/features/listings/listing-pages")).ListingEditorPage }) },
      { path: "vendedor/publicaciones/:listingId", lazy: async () => ({ Component: (await import("@/features/listings/listing-pages")).ListingEditorPage }) },
      { path: "publicaciones/:slug", lazy: async () => ({ Component: (await import("@/features/listings/listing-detail")).PublicListingPage }) },
      { path: "categorias/:id", element: <CategoryPage /> },
      {
        element: <PrivateLayout />,
        hydrateFallbackElement: <p role="status">Cargando…</p>,
        children: [
          { path: "cuenta", element: <AccountPage /> },
          { path: "cuenta/direcciones", lazy: async () => ({ Component: (await import("@/features/auth/addresses-page")).AddressesPage }) },
          { path: "vendedores/:slug", lazy: async () => ({ Component: (await import("@/features/auth/seller-pages")).PublicSellerPage }) },
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

import { createBrowserRouter, Link, Navigate, useLocation } from "react-router-dom";
import { CategoryPage } from "@/features/categories/category-page";
import { StatusPage } from "@/features/status/status-page";
import { AccountPage } from "@/features/auth/account-page";
import { AccountLayout } from "@/features/auth/account-layout";
import { googleReturnLoader } from "@/features/auth/google-auth";
import { GoogleReturnPage } from "@/features/auth/google-return-page";
import { AuthLayout, PrivateLayout, SiteLayout } from "./layouts";

function LegacyOrdersRedirect() {
  const { search, hash } = useLocation();
  return <Navigate to={{ pathname: "/cuenta/ordenes", search, hash }} replace />;
}

export const router = createBrowserRouter([
  {
    element: <AuthLayout />,
    hydrateFallbackElement: <p role="status">Cargando…</p>,
    children: [
      { path: "ingresar", lazy: async () => ({ Component: (await import("@/features/auth/auth-page")).LoginPage }) },
      { path: "registro", lazy: async () => ({ Component: (await import("@/features/auth/auth-page")).RegisterPage }) },
    ],
  },
  {
    element: <SiteLayout />,
    children: [
      { index: true, element: <Navigate to="/catalogo" replace /> },
      { path: "catalogo", lazy: async () => ({ Component: (await import("@/features/listings/catalog-page")).PublicCatalogPage }) },
      { path: "estado", element: <StatusPage /> },
      { path: "informacion/:topic", lazy: async () => ({ Component: (await import("./store-information")).StoreInformation }) },
      { path: "carrito", lazy: async () => ({ Component: (await import("@/features/cart/cart-page")).CartPage }) },
      { path: "checkout", lazy: async () => ({ Component: (await import("@/features/checkout/checkout-page")).CheckoutPage }) },
      { path: "checkout/reserva/:id", lazy: async () => ({ Component: (await import("@/features/checkout/reservation-page")).ReservationPage }) },
      { path: "checkout/resultado/:id", lazy: async () => ({ Component: (await import("@/features/checkout/reservation-page")).CheckoutResultPage }) },
      { path: "mis-ordenes", element: <LegacyOrdersRedirect /> },
      { path: "reclamos/:id", lazy: async () => ({ Component: (await import("@/features/disputes/dispute-page")).DisputePage }) },
      { path: "notificaciones", lazy: async () => ({ Component: (await import("@/features/notifications/notifications-page")).NotificationsPage }) },
      { path: "vendedor", lazy: async () => ({ Component: (await import("@/features/auth/seller-pages")).SellerPanelPage }) },
      { path: "vendedor/pedidos", lazy: async () => ({ Component: (await import("@/features/checkout/seller-orders-page")).SellerOrdersPage }) },
      { path: "vendedor/ventas", lazy: async () => ({ Component: (await import("@/features/sales/seller-sales-page")).SellerSalesPage }) },
      { path: "vendedor/inventario", lazy: async () => ({ Component: (await import("@/features/listings/seller-catalog-page")).InventoryPage }) },
      { path: "vendedor/preguntas", lazy: async () => ({ Component: (await import("@/features/listings/listing-questions")).SellerQuestionsPage }) },
      { path: "vendedor/publicaciones", lazy: async () => ({ Component: (await import("@/features/listings/seller-catalog-page")).ListingsPage }) },
      { path: "vendedor/publicaciones/nueva", lazy: async () => ({ Component: (await import("@/features/listings/listing-pages")).ListingEditorPage }) },
      { path: "vendedor/publicaciones/:listingId", lazy: async () => ({ Component: (await import("@/features/listings/listing-pages")).ListingEditorPage }) },
      { path: "publicaciones/:slug", lazy: async () => ({ Component: (await import("@/features/listings/listing-detail")).PublicListingPage }) },
      { path: "categorias/:id", element: <CategoryPage /> },
      { path: "cuenta", element: <AccountLayout />, children: [
        { index: true, element: <AccountPage /> },
        { path: "direcciones", lazy: async () => ({ Component: (await import("@/features/auth/addresses-page")).AddressesPage }) },
        { path: "ordenes", lazy: async () => ({ Component: (await import("@/features/checkout/orders-page")).OrdersPage }) },
      ] },
      { path: "admin", element: <Navigate to="/admin/publicaciones" replace /> },
      { path: "admin/publicaciones", lazy: async () => {
        const { ModerationPage } = await import("@/features/admin/moderation-page");
        return { Component: () => <ModerationPage resource="listings" /> };
      } },
      { path: "admin/cuentas", lazy: async () => {
        const { ModerationPage } = await import("@/features/admin/moderation-page");
        return { Component: () => <ModerationPage resource="users" /> };
      } },
      { path: "admin/categorias", lazy: async () => ({
        Component: (await import("@/features/admin/category-admin-page")).CategoryAdminPage,
      }) },
      { path: "admin/reclamos", lazy: async () => ({
        Component: (await import("@/features/disputes/dispute-admin-page")).DisputeAdminPage,
      }) },
      { path: "admin/auditoria", lazy: async () => ({
        Component: (await import("@/features/admin/audit-page")).AuditPage,
      }) },
      { path: "admin/incidencias", lazy: async () => ({
        Component: (await import("@/features/admin/payment-incidents-page")).PaymentIncidentsPage,
      }) },
      { path: "admin/operacion", lazy: async () => ({
        Component: (await import("@/features/admin/operations-page")).OperationsPage,
      }) },
      {
        element: <PrivateLayout />,
        hydrateFallbackElement: <p role="status">Cargando…</p>,
        children: [
          { path: "vendedores/:slug", lazy: async () => ({ Component: (await import("@/features/auth/seller-pages")).PublicSellerPage }) },
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
            <Link to="/estado" className="mt-4 inline-block underline underline-offset-4">Ver disponibilidad de Zonda Market</Link>
          </>
        ),
      },
    ],
  },
]);

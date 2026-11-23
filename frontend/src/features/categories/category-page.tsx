import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, ArrowUpRight, CircleAlert, LayoutGrid } from "lucide-react";
import { Link, useParams } from "react-router-dom";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { Card, CardContent, CardHeader } from "@/shared/ui/card";
import { getCategory } from "./category-api";

export function CategoryPage() {
  const { id = "" } = useParams<"id">();
  const { data: category, error, isPending, isFetching, refetch } = useQuery({
    queryKey: ["category", id],
    queryFn: ({ signal }) => getCategory(id, signal),
    retry: false,
  });
  const apiError = error instanceof ApiError ? error : null;
  const clientError = apiError?.status === 400 || apiError?.status === 404;

  return (
    <section className="mx-auto max-w-3xl space-y-5">
      <Link to="/estado" className="inline-flex min-h-11 items-center justify-center gap-2 rounded-md text-sm font-semibold text-primary hover:underline">
        <ArrowLeft size={16} aria-hidden="true" />
        Volver al estado del sistema
      </Link>

      <Card className="gap-0 overflow-hidden">
        {isPending ? (
          <CardContent role="status" className="space-y-4 p-6 sm:p-8">
            <h1 className="text-2xl font-bold">Cargando categoría…</h1>
            <div aria-hidden="true" className="h-4 w-2/3 rounded bg-muted motion-safe:animate-pulse" />
          </CardContent>
        ) : error || !category ? (
          <CardContent className="p-6 sm:p-8">
            <div role="alert">
              <div className="mb-4 inline-flex rounded-lg bg-destructive-soft p-3 text-destructive">
                <CircleAlert size={24} aria-hidden="true" />
              </div>
              <h1 className="text-2xl font-bold">
                {apiError?.status === 400 ? "Enlace de categoría inválido" : apiError?.status === 404 ? "Categoría no disponible" : "No pudimos cargar la categoría"}
              </h1>
              <p className="mt-2 text-sm text-muted-foreground">
                {apiError?.status === 400 ? "Revisá la dirección e intentá abrir una categoría válida." : apiError?.status === 404 ? "La categoría pudo haber sido retirada del catálogo." : "Probá de nuevo en unos instantes."}
              </p>
              {apiError?.traceId && (
                <p className="mt-4 text-sm text-muted-foreground">
                  Referencia <code className="font-mono break-all">{apiError.traceId}</code>
                </p>
              )}
            </div>
            {!clientError && (
              <Button type="button" className="mt-5 min-h-11 rounded-lg" disabled={isFetching} onClick={() => void refetch()}>
                {isFetching ? "Cargando…" : "Reintentar"}
              </Button>
            )}
          </CardContent>
        ) : (
          <>
            <CardHeader className="workspace-heading gap-4 rounded-none p-6 sm:p-8">
              <div className="w-fit rounded-lg bg-white/10 p-3 text-brand-gold">
                <LayoutGrid size={24} aria-hidden="true" />
              </div>
              <h1 className="font-wordmark text-3xl font-extrabold tracking-tight sm:text-4xl">{category.name}</h1>
              <Button asChild className="min-h-11 w-fit">
                <Link to={"/catalogo?category=" + category.id}>Explorar publicaciones</Link>
              </Button>
            </CardHeader>
            {category.parentId && (
              <CardContent className="border-t px-6 py-3 sm:px-8">
                <Link to={`/categorias/${category.parentId}`} className="inline-flex min-h-11 items-center justify-center gap-2 text-sm font-semibold text-primary hover:underline">
                  Ver categoría superior <ArrowUpRight size={16} aria-hidden="true" />
                </Link>
              </CardContent>
            )}
          </>
        )}
      </Card>
    </section>
  );
}

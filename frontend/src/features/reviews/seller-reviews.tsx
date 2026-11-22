import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "react-router-dom";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import { ReputationSummary } from "./reputation";
import { fetchSellerReviews, sellerReviewsKey, type Reputation } from "./reviews-api";

const dateFormat = new Intl.DateTimeFormat("es-AR", {
  dateStyle: "medium", timeZone: "America/Argentina/Buenos_Aires",
});

export function SellerReviews({ slug, reputation }: { slug: string; reputation: Reputation }) {
  const [params, setParams] = useSearchParams();
  const raw = Number(params.get("pagina"));
  const page = Number.isInteger(raw) && raw > 0 && raw <= 2147483647 ? raw : 0;
  const reviews = useQuery({
    queryKey: [...sellerReviewsKey, slug, page],
    queryFn: ({ signal }) => fetchSellerReviews(slug, page, signal),
    retry: false,
  });

  return <section className="space-y-3 border-t pt-4" aria-labelledby="reputation-title">
    <h2 id="reputation-title" className="text-lg font-semibold">Reputación</h2>
    <ReputationSummary {...(reviews.data?.reputation ?? reputation)} />
    {reviews.isPending ? <p role="status">Cargando reseñas…</p>
      : reviews.isError ? <div className="space-y-2">
        <p role="alert" className="text-destructive">No pudimos cargar las reseñas.</p>
        <Button type="button" variant="outline" onClick={() => void reviews.refetch()}>Reintentar</Button>
      </div>
      : <>
        {reviews.data.content.length > 0 ? <ol role="list" aria-label="Reseñas del vendedor" className="space-y-3">
          {reviews.data.content.map((review) => <li key={review.id}>
            <Card className="gap-0 py-4">
              <CardContent className="space-y-2 px-4 sm:px-6">
                <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
                  <h3 className="font-semibold">{review.authorAlias}</h3>
                  <time className="text-sm text-muted-foreground" dateTime={review.createdAt}>
                    {dateFormat.format(new Date(review.createdAt))}
                  </time>
                </div>
                <p className="text-sm font-medium">{review.rating} de 5 estrellas</p>
                {review.comment && <p className="whitespace-pre-wrap [overflow-wrap:anywhere]">{review.comment}</p>}
              </CardContent>
            </Card>
          </li>)}
        </ol> : page > 0 && <p>No hay reseñas en esta página.</p>}
        {(page > 0 || reviews.data.hasNext) && <nav aria-label="Paginación de reseñas" className="flex gap-2">
          <Button variant="outline" disabled={page === 0} onClick={() => setParams({ pagina: String(page - 1) })}>Anterior</Button>
          <Button variant="outline" disabled={!reviews.data.hasNext}
            onClick={() => setParams({ pagina: String(page + 1) })}>Siguiente</Button>
        </nav>}
      </>}
  </section>;
}

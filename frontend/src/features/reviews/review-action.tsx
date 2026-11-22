import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Star } from "lucide-react";
import { useEffect, useId, useRef, type FormEvent } from "react";
import { profileControl } from "@/features/auth/profile-fields";
import { getSessionRevision } from "@/features/auth/session-api";
import { orderKey, type Order, type SubOrder } from "@/features/checkout/checkout-api";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { publishReview, reviewPath, sellerReviewsKey } from "./reviews-api";

const ratings = [1, 2, 3, 4, 5];

export function ReviewAction({ sub, orderId, revision }: { sub: SubOrder; orderId: string; revision: number }) {
  const client = useQueryClient();
  const formId = useId();
  const submitted = useRef(false);
  const posted = useRef<HTMLParagraphElement>(null);
  const key = orderKey(revision, orderId);

  async function markReviewed() {
    if (revision !== getSessionRevision()) return;
    await client.cancelQueries({ queryKey: key });
    if (revision !== getSessionRevision()) return;
    client.setQueryData<Order>(key, (order) => order && ({
      ...order, subOrders: order.subOrders.map((item) => item.id === sub.id ? { ...item, reviewed: true } : item),
    }));
    void client.invalidateQueries({ queryKey: key });
    void client.invalidateQueries({ queryKey: sellerReviewsKey });
    void client.invalidateQueries({ queryKey: ["seller"] });
    void client.invalidateQueries({ queryKey: ["listing-public"] });
  }

  const review = useMutation({
    mutationFn: ({ rating, comment }: { rating: number; comment: string }) => publishReview(sub.id, rating, comment),
    onSuccess: markReviewed,
    onError: (error) => {
      if (error instanceof ApiError && error.code === "REVIEW_ALREADY_EXISTS") return markReviewed();
    },
  });
  useEffect(() => {
    if (sub.reviewed && submitted.current) posted.current?.focus();
  }, [sub.reviewed]);

  if (sub.status !== "ENTREGADA") return null;
  if (sub.reviewed) return <p role="status" ref={posted} tabIndex={-1}
    className="rounded-md border-t pt-3 font-medium text-success">Reseña publicada.</p>;

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (review.isPending || revision !== getSessionRevision()) return;
    const fields = new FormData(event.currentTarget);
    submitted.current = true;
    review.mutate({ rating: Number(fields.get("rating")), comment: String(fields.get("comment")) });
  }

  return <form aria-labelledby={formId + "-heading"} action={reviewPath(sub.id)} method="post" onSubmit={submit}
    aria-busy={review.isPending} className="space-y-3 border-t pt-3">
    <h3 id={formId + "-heading"} className="font-semibold">Calificá esta entrega</h3>
    <fieldset disabled={review.isPending} className="min-w-0 space-y-2">
      <legend className="text-sm font-medium">Estrellas (obligatorio)</legend>
      <div className="grid max-w-xs grid-cols-5 gap-1">
        {ratings.map((rating) => <label key={rating}
          className="review-rating relative flex min-h-14 cursor-pointer flex-col items-center justify-center gap-0.5 rounded-md border text-sm has-[:checked]:border-primary has-[:checked]:bg-secondary has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-primary">
          <input type="radio" name="rating" value={rating} required className="size-4 accent-primary" />
          <span className="flex items-center gap-0.5 font-medium">
            <Star size={13} aria-hidden="true" className="shrink-0 text-amber-800" />
            {rating}<span className="sr-only">{rating === 1 ? " estrella" : " estrellas"}</span>
          </span>
        </label>)}
      </div>
    </fieldset>
    <div className="space-y-1">
      <label htmlFor={formId + "-comment"} className="block text-sm font-medium">Comentario (opcional)</label>
      <textarea id={formId + "-comment"} name="comment" rows={3} maxLength={1000} readOnly={review.isPending}
        className={profileControl + " w-full text-base"} aria-describedby={formId + "-hint"} />
      <p id={formId + "-hint"} className="text-sm text-muted-foreground">
        Hasta 1.000 caracteres. Tu reseña será pública y se mostrará con un alias.
      </p>
    </div>
    {review.isError && <p role="alert" className="text-destructive">
      {review.error instanceof ApiError && review.error.code === "REVIEW_DELIVERY_REQUIRED"
        ? "La suborden ya no está disponible para calificar."
        : "No pudimos publicar la reseña. Volvé a intentar."}
    </p>}
    <Button type="submit" className="min-h-12 w-full sm:w-auto" disabled={review.isPending}>
      {review.isPending ? "Publicando…" : "Publicar reseña"}
    </Button>
  </form>;
}

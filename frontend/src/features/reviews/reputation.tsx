import { Star } from "lucide-react";
import type { Reputation } from "./reviews-api";

const formatRating = new Intl.NumberFormat("es-AR", { maximumFractionDigits: 2 });

export function ReputationSummary({ reviewCount, averageRating }: Reputation) {
  if (reviewCount === 0 || averageRating === null) {
    return <p className="text-sm text-muted-foreground">Todavía no tiene reseñas.</p>;
  }
  return <p className="flex items-center gap-1.5 text-sm text-muted-foreground">
    <Star size={16} aria-hidden="true" className="shrink-0 fill-amber-400 text-amber-800" />
    <span><strong className="text-foreground">{formatRating.format(averageRating)} de 5</strong>
      {" · "}{reviewCount} {reviewCount === 1 ? "reseña" : "reseñas"}</span>
  </p>;
}

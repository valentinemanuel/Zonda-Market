import { authenticatedRequest } from "@/features/auth/session-api";
import { request } from "@/shared/api/http-client";

export type Reputation = { averageRating: number | null; reviewCount: number };
export type Review = { id: string; rating: number; comment: string | null; createdAt: string; authorAlias: string };
type ReviewPage = { content: Review[]; page: number; size: number; hasNext: boolean; reputation: Reputation };

export const sellerReviewsKey = ["seller-reviews"] as const;
export const reviewPath = (id: string) => `/api/v1/me/sub-orders/${encodeURIComponent(id)}/review`;

export async function publishReview(id: string, rating: number, comment: string): Promise<Review> {
  return (await authenticatedRequest(reviewPath(id), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ rating, comment }),
  })).json();
}

export async function fetchSellerReviews(slug: string, page: number, signal: AbortSignal): Promise<ReviewPage> {
  return (await request(`/api/v1/sellers/${encodeURIComponent(slug)}/reviews?page=${page}`, { signal })).json();
}

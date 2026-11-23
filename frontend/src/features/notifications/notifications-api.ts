import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { authenticatedRequest, getSessionRevision } from "@/features/auth/session-api";

const path = "/api/v1/me/notifications";
const key = (revision: number) => ["private", "notifications", revision] as const;

export type Notification = {
  id: string;
  type: "PAYMENT_APPROVED" | "SUB_ORDER_SHIPPED" | "DELIVERY_CONFIRMED" | "DISPUTE_OPENED" | "DISPUTE_ASSIGNED" | "DISPUTE_RESOLVED";
  createdAt: string;
  read: boolean;
  resourceId: string;
  href: string;
};

type NotificationPage = {
  content: Notification[];
  page: number;
  size: number;
  hasNext: boolean;
  unreadCount: number;
};

export function useNotifications(revision: number, page: number, enabled = true, poll = false) {
  return useQuery({
    queryKey: [...key(revision), page],
    queryFn: async ({ signal }): Promise<NotificationPage> =>
      (await authenticatedRequest(path + "?page=" + page, { signal })).json(),
    enabled,
    retry: false,
    refetchInterval: (query) => poll && query.state.data ? 10_000 : false,
  });
}

export function useMarkNotificationRead(revision: number) {
  const client = useQueryClient();
  const queryKey = key(revision);
  return useMutation({
    mutationFn: async (id: string): Promise<Notification> => {
      if (revision !== getSessionRevision()) throw new DOMException("La sesión cambió.", "AbortError");
      return (await authenticatedRequest(`${path}/${encodeURIComponent(id)}/read`, { method: "PATCH" })).json();
    },
    onMutate: async (id) => {
      await client.cancelQueries({ queryKey });
      if (revision !== getSessionRevision()) return;
      const previous = client.getQueriesData<NotificationPage>({ queryKey });
      client.setQueriesData<NotificationPage>({ queryKey }, (page) => page && ({
        ...page,
        content: page.content.map((entry) => entry.id === id ? { ...entry, read: true } : entry),
        unreadCount: Math.max(0, page.unreadCount - 1),
      }));
      return previous;
    },
    onError: (_error, _id, previous) => {
      if (revision !== getSessionRevision()) return;
      for (const [pageKey, page] of previous ?? []) client.setQueryData(pageKey, page);
    },
    onSettled: () => {
      if (revision === getSessionRevision()) void client.invalidateQueries({ queryKey });
    },
  });
}

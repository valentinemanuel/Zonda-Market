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
    scope: { id: `read-notifications:${revision}` },
    mutationFn: async (id: string) => {
      await client.cancelQueries({ queryKey });
      if (revision !== getSessionRevision()) throw new DOMException("La sesión cambió.", "AbortError");
      const previous = client.getQueriesData<NotificationPage>({ queryKey });
      if (previous.some(([, page]) => page?.content.some((entry) => entry.id === id && entry.read))) return;
      client.setQueriesData<NotificationPage>({ queryKey }, (page) => page && ({
        ...page,
        content: page.content.map((entry) => entry.id === id ? { ...entry, read: true } : entry),
        unreadCount: Math.max(0, page.unreadCount - 1),
      }));
      try {
        await authenticatedRequest(`${path}/${encodeURIComponent(id)}/read`, { method: "PATCH" });
      } catch (error) {
        if (revision === getSessionRevision()) {
          for (const [pageKey, page] of previous) client.setQueryData(pageKey, page);
        }
        throw error;
      }
    },
    onSettled: () => {
      if (revision === getSessionRevision()) void client.invalidateQueries({ queryKey });
    },
  });
}

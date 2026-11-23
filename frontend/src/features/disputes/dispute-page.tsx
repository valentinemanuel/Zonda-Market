import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState, useSyncExternalStore, type FormEvent } from "react";
import { Link, useParams } from "react-router-dom";
import { getSessionRevision, subscribeToSession } from "@/features/auth/session-api";
import { signInRequired } from "@/features/cart/cart-api";
import { reservationPath } from "@/features/checkout/checkout-api";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import { LoadError } from "@/features/admin/admin-page";
import { assignDispute, disputeActive, disputeDetail, disputeError, disputeKey, disputeMessagePath, disputeQueueKey,
  disputeStatuses, postDisputeMessage, type DisputeDetail } from "./dispute-api";
import { disputeControl, disputeDate } from "./dispute-ui";

const authors = { BUYER: "Comprador", SELLER: "Vendedor", ADMIN: "Administración" };

export function DisputePage() {
  const { id } = useParams();
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);
  if (!id || !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(id)) {
    return <p role="alert">No encontramos ese reclamo.</p>;
  }
  return <Detail key={revision + ":" + id} id={id} revision={revision} />;
}

function Detail({ id, revision }: { id: string; revision: number }) {
  const [page, setPage] = useState(0);
  const key = disputeKey(revision, id);
  const query = useQuery({
    queryKey: [...key, page], queryFn: ({ signal }) => disputeDetail(id, page, signal),
    placeholderData: keepPreviousData, retry: false,
  });
  const heading = useRef<HTMLHeadingElement>(null);
  const title = query.isSuccess;
  useEffect(() => { if (title && page === 0) heading.current?.focus(); }, [title, page]);

  return <div className="mx-auto max-w-3xl space-y-5">
    <h1 ref={heading} tabIndex={-1} className="text-3xl font-semibold">Reclamo privado</h1>
    {query.isPending && <p role="status">Cargando reclamo…</p>}
    {signInRequired(query.error) && <Button asChild>
      <Link to="/ingresar" state={{ returnToDispute: id }}>Iniciar sesión para ver el reclamo</Link>
    </Button>}
    {query.error instanceof ApiError && query.error.status === 404 && <p role="alert">No encontramos ese reclamo.</p>}
    {query.isError && !signInRequired(query.error) && !(query.error instanceof ApiError && query.error.status === 404)
      && <LoadError message="No pudimos cargar el reclamo." retry={query.refetch} />}
    {query.isSuccess && <Conversation detail={query.data} revision={revision} setPage={setPage} loading={query.isPlaceholderData} />}
  </div>;
}

function Conversation({ detail, revision, setPage, loading }: {
  detail: DisputeDetail; revision: number; setPage: (page: number) => void; loading: boolean;
}) {
  const client = useQueryClient();
  const [body, setBody] = useState("");
  const [notice, setNotice] = useState("");
  const attempt = useRef<{ text: string; key: string }>(null);
  const messageField = useRef<HTMLTextAreaElement>(null);
  const key = disputeKey(revision, detail.id);
  const post = useMutation({
    mutationFn: ({ text, key }: { text: string; key: string }) => postDisputeMessage(detail.id, text, key),
    onSuccess: async () => {
      attempt.current = null;
      setBody("");
      setNotice("Mensaje enviado.");
      setPage(0);
      await client.invalidateQueries({ queryKey: key });
    },
  });
  const assign = useMutation({
    mutationFn: () => assignDispute(detail.id),
    onSuccess: async () => {
      setNotice("Reclamo en mediación.");
      await Promise.all([client.invalidateQueries({ queryKey: key }),
        client.invalidateQueries({ queryKey: disputeQueueKey(revision) })]);
    },
    onError: (failure) => {
      if (failure instanceof ApiError && failure.status === 409) void client.invalidateQueries({ queryKey: key });
    },
  });
  const back = detail.viewer === "ADMIN" ? "/admin/reclamos" : detail.viewer === "SELLER"
    ? "/vendedor/pedidos?suborden=" + detail.subOrderId : reservationPath(detail.orderId) + "#suborden-" + detail.subOrderId;

  function send(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const text = body.trim();
    if (post.isPending) return;
    if (!text) {
      messageField.current?.setCustomValidity("Escribí un mensaje.");
      messageField.current?.reportValidity();
      return;
    }
    if (attempt.current?.text !== text) attempt.current = { text, key: crypto.randomUUID() };
    post.mutate(attempt.current);
  }

  return <>
    <Link className="text-sm underline underline-offset-4" to={back}>{detail.viewer === "ADMIN" ? "Volver a la bandeja" : "Volver al pedido"}</Link>
    <Card className="gap-0 py-4"><CardContent className="space-y-3 px-4">
      <p className="font-semibold" role="status">{disputeStatuses[detail.status]}</p>
      <p className="break-all text-sm text-muted-foreground">Suborden {detail.subOrderId}</p>
      <time className="text-sm text-muted-foreground" dateTime={detail.openedAt}>{disputeDate.format(new Date(detail.openedAt))}</time>
      <h2 className="break-words text-lg font-semibold">{detail.reason}</h2>
      <p className="whitespace-pre-wrap [overflow-wrap:anywhere]">{detail.description}</p>
      {detail.viewer === "ADMIN" && detail.status === "ABIERTA" && <Button type="button" className="min-h-12"
        disabled={assign.isPending} onClick={() => assign.mutate()}>{assign.isPending ? "Tomando caso…" : "Tomar para mediación"}</Button>}
      {assign.isError && <p role="alert" className="text-destructive">{disputeError(assign.error, "No pudimos tomar el caso.")}</p>}
    </CardContent></Card>
    <section className="space-y-3" aria-labelledby="dispute-history">
      <h2 id="dispute-history" className="text-xl font-semibold">Historial del reclamo</h2>
      {loading && <p role="status">Cargando historial…</p>}
      {detail.messages.length === 0 ? <p>No hay mensajes en esta página.</p> : (
        <ol className="space-y-2" role="list">
          {detail.messages.map((msg) => <li key={msg.id} className="space-y-1 rounded-lg border bg-card p-3">
            <p className="flex flex-wrap justify-between gap-2 text-sm font-semibold">{authors[msg.authorRole]}
              <time dateTime={msg.createdAt} className="font-normal text-muted-foreground">{disputeDate.format(new Date(msg.createdAt))}</time>
            </p>
            <p className="whitespace-pre-wrap [overflow-wrap:anywhere]">{msg.body}</p>
          </li>)}
        </ol>
      )}
      {(detail.page > 0 || detail.hasNext) && <nav aria-label="Páginas del historial" className="flex flex-wrap gap-2">
        <Button variant="outline" disabled={loading || detail.page === 0} onClick={() => setPage(detail.page - 1)}>Más recientes</Button>
        <Button variant="outline" disabled={loading || !detail.hasNext} onClick={() => setPage(detail.page + 1)}>Más antiguos</Button>
      </nav>}
    </section>
    <p role="status" className="text-success empty:sr-only">{notice}</p>
    {disputeActive(detail.status) && <form method="post" action={disputeMessagePath(detail.id)}
      aria-labelledby="dispute-message-title" className="space-y-3 rounded-lg border bg-card p-4" onSubmit={send}>
      <h2 id="dispute-message-title" className="text-lg font-semibold">Responder</h2>
      <label className="grid gap-1 text-sm font-medium">Mensaje (obligatorio)
        <textarea ref={messageField} name="body" className={disputeControl} required maxLength={2000} rows={4} value={body}
          disabled={post.isPending} onChange={(event) => {
            event.currentTarget.setCustomValidity("");
            setBody(event.target.value);
            setNotice("");
          }} />
      </label>
      {post.isError && <p role="alert" className="text-destructive">
        {disputeError(post.error, "No pudimos enviar el mensaje. Volvé a intentar.")}
      </p>}
      <Button type="submit" className="min-h-12" disabled={post.isPending}>
        {post.isPending ? "Enviando…" : "Enviar mensaje"}
      </Button>
    </form>}
  </>;
}

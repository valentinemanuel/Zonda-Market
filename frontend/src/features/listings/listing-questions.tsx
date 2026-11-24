import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState, useSyncExternalStore, type FormEvent } from "react";
import { Link, Navigate, useLocation, useParams } from "react-router-dom";
import { profileControl, profileLink } from "@/features/auth/profile-fields";
import { authenticatedRequest, currentAccount, getSessionRevision, subscribeToSession } from "@/features/auth/session-api";
import { ApiError, getJson } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import { listingPublicPath } from "./public-listing-format";
import { SellerHeading, SellerNavigation } from "./seller-workspace";

type Question = { id: string; text: string; createdAt: string; answer: string | null; answeredAt: string | null };
type PendingQuestion = Pick<Question, "id" | "text" | "createdAt"> & { listingSlug: string; listingTitle: string };
type QuestionPage<T> = { content: T[]; page: number; hasNext: boolean };
const date = new Intl.DateTimeFormat("es-AR", { dateStyle: "medium" });
const sellerQueueKey = ["private", "seller-questions"] as const;

function message(error: unknown) {
  if (error instanceof ApiError) {
    if (error.status === 401) return "Tu sesión venció. Iniciá sesión para enviar el mensaje.";
    if (error.status === 404) return "La publicación o la pregunta ya no está disponible.";
    if (error.status === 409) return "La pregunta ya tiene respuesta. Actualizá la cola.";
    if (error.status === 429) return "Alcanzaste el límite por hora. Volvé a intentar más tarde.";
    if (error.status === 400) return "Revisá el texto antes de enviar.";
  }
  return "No pudimos enviar el mensaje. Volvé a intentar.";
}

function MessageForm({ kind, send }: { kind: "question" | "answer"; send: (text: string) => Promise<void> }) {
  const id = useId();
  const [text, setText] = useState("");
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const question = kind === "question";
  const max = question ? 500 : 1000;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || !text.trim()) return;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await send(text.trim());
      setText("");
      setNotice(question ? "Pregunta publicada." : "Respuesta publicada.");
    } catch (error) {
      setError(message(error));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="space-y-2" onSubmit={submit} aria-busy={busy}>
      <label htmlFor={id} className="block text-sm font-medium">{question ? "Tu pregunta" : "Tu respuesta"}</label>
      <textarea id={id} className={profileControl + " min-h-24 resize-y"} maxLength={max}
        aria-describedby={id + "-hint"} rows={3} value={text} readOnly={busy} required
        onChange={(event) => setText(event.currentTarget.value)} />
      <p id={id + "-hint"} className="text-sm text-muted-foreground">El mensaje será público. Hasta {max} caracteres.</p>
      <Button type="submit" className="min-h-11" disabled={busy || !text.trim()}>
        {busy ? "Publicando…" : question ? "Publicar pregunta" : "Publicar respuesta"}
      </Button>
      {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
      {notice && <p role="status" className="text-sm text-success">{notice}</p>}
    </form>
  );
}

async function sendMessage(path: string, method: "POST" | "PUT", text: string) {
  await authenticatedRequest(path, {
    method, headers: { "Content-Type": "application/json" }, body: JSON.stringify({ text }),
  });
}

function QuestionPagination({ page, hasNext, change }: { page: number; hasNext: boolean; change: (page: number) => void }) {
  if (page === 0 && !hasNext) return null;
  return <nav aria-label="Páginas de preguntas" className="flex flex-wrap items-center gap-3">
    <Button type="button" variant="outline" disabled={!page} onClick={() => change(page - 1)}>Anteriores</Button>
    <span className="text-sm">Página {page + 1}</span>
    <Button type="button" variant="outline" disabled={!hasNext} onClick={() => change(page + 1)}>Siguientes</Button>
  </nav>;
}

export function ListingQuestions({ listingId, readOnly = false }: { listingId: string; readOnly?: boolean }) {
  const { slug = "" } = useParams();
  const { hash, state } = useLocation();
  const section = useRef<HTMLElement>(null);
  const [page, setPage] = useState(0);
  const cache = useQueryClient();
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);
  const path = "/api/v1/listings/" + listingId + "/questions";
  const questions = useQuery({
    queryKey: ["listing-questions", listingId, page],
    queryFn: ({ signal }) => getJson(path + "?page=" + page, signal) as Promise<QuestionPage<Question>>,
    retry: false,
  });
  const account = useQuery({
    queryKey: ["private", "me", revision], queryFn: ({ signal }) => currentAccount(signal),
    enabled: !readOnly, retry: false, staleTime: 30_000,
  });
  useEffect(() => {
    if (hash === "#preguntas") section.current?.scrollIntoView();
  }, [hash]);

  return <section ref={section} id="preguntas" aria-labelledby="preguntas-titulo" className="mx-auto max-w-7xl scroll-mt-32 space-y-4 rounded-panel border bg-card p-4 shadow-panel sm:p-6">
    <h2 id="preguntas-titulo" className="text-xl font-semibold">Preguntas y respuestas</h2>
    {questions.isPending && <p role="status">Cargando preguntas…</p>}
    {questions.isError && <div className="space-y-2">
      <p role="alert" className="text-destructive">No pudimos cargar las preguntas.</p>
      <Button type="button" variant="outline" onClick={() => void questions.refetch()}>Reintentar preguntas</Button>
    </div>}
    {questions.data && <>
      {questions.data.content.length === 0 && <p className="text-muted-foreground">Todavía no hay preguntas en esta página.</p>}
      <ul className="space-y-3">
        {questions.data.content.map((item) => <li key={item.id}>
          <Card className="rounded-lg py-4"><CardContent className="space-y-3 px-4">
            <p className="whitespace-pre-wrap font-medium [overflow-wrap:anywhere]">{item.text}</p>
            <time className="block text-sm text-muted-foreground" dateTime={item.createdAt}>{date.format(new Date(item.createdAt))}</time>
            {item.answer && <div className="space-y-1 rounded-md border-l-2 border-primary bg-muted px-3 py-2">
              <p className="text-sm font-medium">Respuesta del vendedor</p>
              <p className="whitespace-pre-wrap [overflow-wrap:anywhere]">{item.answer}</p>
            </div>}
          </CardContent></Card>
        </li>)}
      </ul>
      <QuestionPagination page={page} hasNext={questions.data.hasNext} change={setPage} />
    </>}
    {!readOnly && account.isPending && <p role="status" className="text-sm text-muted-foreground">Comprobando sesión…</p>}
    {!readOnly && account.isError && (account.error instanceof ApiError && account.error.status === 401
      ? <Link className={profileLink} to="/ingresar"
          state={{ listingSlug: slug, catalogSearch: state?.catalogSearch }}>Iniciá sesión para preguntar</Link>
      : <Button type="button" variant="outline" onClick={() => void account.refetch()}>Volver a comprobar la sesión</Button>)}
    {!readOnly && account.data && <Card className="rounded-lg py-4"><CardContent className="px-4">
      <MessageForm key={revision} kind="question" send={async (text) => {
        await sendMessage(path, "POST", text);
        setPage(0);
        void cache.invalidateQueries({ queryKey: ["listing-questions", listingId] });
        void cache.invalidateQueries({ queryKey: sellerQueueKey });
      }} />
    </CardContent></Card>}
  </section>;
}

export function SellerQuestionsPage() {
  const [page, setPage] = useState(0);
  const [answered, setAnswered] = useState(false);
  const cache = useQueryClient();
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);
  const queue = useQuery({
    queryKey: [...sellerQueueKey, revision, page],
    queryFn: async ({ signal }): Promise<QuestionPage<PendingQuestion>> =>
      (await authenticatedRequest("/api/v1/seller/questions?page=" + page, { signal })).json(),
    retry: false,
  });
  if (queue.error instanceof ApiError && queue.error.status === 401) return <Navigate to="/ingresar" replace />;

  return <div className="mx-auto max-w-5xl space-y-4">
    <SellerNavigation />
    <SellerHeading title="Preguntas de tus publicaciones" />
    {answered && <p role="status" className="text-success">Respuesta publicada.</p>}
    {queue.isPending && <p role="status">Cargando preguntas pendientes…</p>}
    {queue.isError && (queue.error instanceof ApiError && queue.error.status === 403
      ? <p role="alert">Activá tu perfil de vendedor desde <Link className={profileLink} to="/cuenta">Mi cuenta</Link>.</p>
      : <div className="space-y-2">
        <p role="alert" className="text-destructive">No pudimos cargar las preguntas pendientes.</p>
        <Button type="button" variant="outline" onClick={() => void queue.refetch()}>Reintentar preguntas</Button>
      </div>)}
    {queue.data && <>
      <Button type="button" variant="outline" onClick={() => void queue.refetch()}>Actualizar cola</Button>
      {queue.data.content.length === 0 && <p>No tenés preguntas pendientes en esta página.</p>}
      {queue.data.content.map((item) => <Card key={revision + ":" + item.id} className="rounded-lg">
        <CardContent className="space-y-4 px-4 sm:px-6">
          <h2 className="font-semibold [overflow-wrap:anywhere]"><Link className={profileLink}
            to={listingPublicPath(item.listingSlug) + "#preguntas"}>{item.listingTitle}</Link></h2>
          <p className="whitespace-pre-wrap [overflow-wrap:anywhere]">{item.text}</p>
          <time className="block text-sm text-muted-foreground" dateTime={item.createdAt}>{date.format(new Date(item.createdAt))}</time>
          <MessageForm kind="answer" send={async (text) => {
            await sendMessage("/api/v1/seller/questions/" + item.id + "/answer", "PUT", text);
            setAnswered(true);
            void cache.invalidateQueries({ queryKey: ["listing-questions"] });
            void cache.invalidateQueries({ queryKey: sellerQueueKey });
          }} />
        </CardContent>
      </Card>)}
      <QuestionPagination page={page} hasNext={queue.data.hasNext} change={setPage} />
    </>}
  </div>;
}

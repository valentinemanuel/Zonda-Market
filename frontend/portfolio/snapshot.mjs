import { writeFile } from "node:fs/promises";

// Run against an empty Compose project seeded with E7-02 only.
const origin = "http://127.0.0.1:4318";

async function get(path) {
  const response = await fetch(origin + "/api/v1" + path, { signal: AbortSignal.timeout(10000) });
  if (!response.ok) throw new Error(`${path} respondió ${response.status}`);
  return response.json();
}

async function summaries(sort) {
  const first = await get(`/listings?size=48&sort=${sort}`);
  const remaining = await Promise.all(Array.from({ length: first.totalPages - 1 }, (_, index) =>
    get(`/listings?size=48&sort=${sort}&page=${index + 1}`)));
  return [first, ...remaining].flatMap(({ content }) => content);
}

const [categories, featured, newest] = await Promise.all([
  get("/categories"), summaries("relevance"), summaries("newest"),
]);
if (featured.length !== 80 || featured.some(({ slug, seller }) => !slug.startsWith("demo-") || !seller.startsWith("demo-"))) {
  throw new Error("El entorno debe contener las 80 publicaciones ficticias de E7-02 exclusivamente.");
}

const entries = [];
for (let offset = 0; offset < featured.length; offset += 8) {
  const batch = featured.slice(offset, offset + 8);
  entries.push(...await Promise.all(batch.map(async (summary) => {
    const [detail, questions] = await Promise.all([
      get(`/listings/${encodeURIComponent(summary.slug)}`), get(`/listings/${summary.id}/questions`),
    ]);
    return { summary, detail, questions };
  })));
}

const data = [
  "{",
  `  "categories": ${JSON.stringify(categories)},`,
  `  "newest": ${JSON.stringify(newest.map(({ slug }) => slug))},`,
  '  "listings": [',
  entries.map((entry) => "    " + JSON.stringify(entry)).join(",\n"),
  "  ]",
  "}",
];
await writeFile(new URL("./catalog.snapshot.json", import.meta.url), data.join("\n") + "\n");

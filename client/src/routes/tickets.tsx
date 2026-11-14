import { createFileRoute } from "@tanstack/react-router";

const description = "Every bet at your tables: open picks, locked pots, results and washes.";

export const Route = createFileRoute("/tickets")({
  head: () => ({
    meta: [
      { title: "Tickets | P2Picks" },
      { name: "description", content: description },
      { property: "og:title", content: "Tickets | P2Picks" },
      { property: "og:description", content: description },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  // ?table=<id> narrows the list to one table.
  validateSearch: (search: Record<string, unknown>): { table?: string } =>
    typeof search["table"] === "string" ? { table: search["table"] } : {},
  // The root renders BackendApp for every path; this route only provides the path and page metadata.
  component: () => null,
});

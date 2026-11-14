import { createFileRoute } from "@tanstack/react-router";

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [
      { title: "P2Picks | Your tables and tickets" },
      {
        name: "description",
        content: "Live NFL and NBA scores, your open bet tickets, and every table you play at.",
      },
      { property: "og:title", content: "P2Picks | Your tables and tickets" },
      {
        property: "og:description",
        content: "Live NFL and NBA scores, your open bet tickets, and every table you play at.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  // The root renders BackendApp for every path; this route only provides the path and page metadata.
  component: () => null,
});

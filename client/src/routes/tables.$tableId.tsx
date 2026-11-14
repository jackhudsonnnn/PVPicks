import { createFileRoute } from "@tanstack/react-router";

export const Route = createFileRoute("/tables/$tableId")({
  head: () => ({
    meta: [
      { title: "Table | P2Picks" },
      {
        name: "description",
        content: "Take a seat, chat with your tablemates and bet on live NFL and NBA games.",
      },
      { property: "og:title", content: "Table | P2Picks" },
      {
        property: "og:description",
        content: "Take a seat, chat with your tablemates and bet on live NFL and NBA games.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  // The root renders BackendApp for every path; this route only provides the path and page metadata.
  component: () => null,
});

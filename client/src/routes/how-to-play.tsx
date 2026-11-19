import { createFileRoute } from "@tanstack/react-router";

export const Route = createFileRoute("/how-to-play")({
  head: () => ({
    meta: [
      { title: "How to Play | P2Picks" },
      {
        name: "description",
        content: "Game modes, the bet lifecycle and pooled points: how P2Picks works.",
      },
      { property: "og:title", content: "How to Play | P2Picks" },
      {
        property: "og:description",
        content: "Game modes, the bet lifecycle and pooled points: how P2Picks works.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  // The root renders App for every path; this route only provides the path and page metadata.
  component: () => null,
});

import { createFileRoute } from "@tanstack/react-router";

export const Route = createFileRoute("/preferences")({
  head: () => ({
    meta: [
      { title: "Preferences | P2Picks" },
      {
        name: "description",
        content: "Your P2Picks table invites, profile, safety and display settings.",
      },
      { property: "og:title", content: "Preferences | P2Picks" },
      {
        property: "og:description",
        content: "Your P2Picks table invites, profile, safety and display settings.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  // The root renders BackendApp for every path; this route only provides the path and page metadata.
  component: () => null,
});

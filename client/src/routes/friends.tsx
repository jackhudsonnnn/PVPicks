import { createFileRoute } from "@tanstack/react-router";

export const Route = createFileRoute("/friends")({
  head: () => ({
    meta: [
      { title: "Friends | P2Picks" },
      {
        name: "description",
        content: "Follow people, follow back, and see your friends and followers on P2Picks.",
      },
      { property: "og:title", content: "Friends | P2Picks" },
      {
        property: "og:description",
        content: "Follow people, follow back, and see your friends and followers on P2Picks.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  // The root renders BackendApp for every path; this route only provides the path and page metadata.
  component: () => null,
});

import { createFileRoute } from "@tanstack/react-router";
import { HowToPlay } from "@/features/guide/components/guide-page";

export const Route = createFileRoute("/how-to-play")({
  head: () => ({
    meta: [
      { title: "How to Play | P2Picks" },
      {
        name: "description",
        content:
          "Game modes, the bet lifecycle and pooled payouts: how P2Picks works without a house.",
      },
      { property: "og:title", content: "How to Play | P2Picks" },
      {
        property: "og:description",
        content:
          "Game modes, the bet lifecycle and pooled payouts: how P2Picks works without a house.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: HowToPlay,
});

import { createFileRoute, Navigate } from "@tanstack/react-router";

export const Route = createFileRoute("/sign-up")({
  head: () => ({
    meta: [
      { title: "Create account | P2Picks" },
      {
        name: "description",
        content: "Make a P2Picks account to start picking NFL and NBA games with your friends.",
      },
      { property: "og:title", content: "Create account | P2Picks" },
      {
        property: "og:description",
        content: "Make a P2Picks account to start picking NFL and NBA games with your friends.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  // Signed-out visitors see the sign-up form from the root gate; signed-in people go home.
  component: () => <Navigate to="/" replace />,
});

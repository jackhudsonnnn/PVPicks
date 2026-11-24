import { createFileRoute } from "@tanstack/react-router";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

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
  // ?tab=peers opens the Peers (members) tab, e.g. from a ticket's player avatars; ?profile=<user id> opens that
  // person's profile, e.g. from a ticket's proposer; ?resend=<bet id> opens the proposal sheet filled in with that
  // finished bet's terms.
  validateSearch: (
    search: Record<string, unknown>,
  ): { tab?: "peers"; profile?: string; resend?: string } => ({
    ...(search["tab"] === "peers" ? { tab: "peers" as const } : {}),
    ...(typeof search["profile"] === "string" && UUID.test(search["profile"])
      ? { profile: search["profile"] }
      : {}),
    ...(typeof search["resend"] === "string" && UUID.test(search["resend"])
      ? { resend: search["resend"] }
      : {}),
  }),
  // The root renders App for every path; this route only provides the path and page metadata.
  component: () => null,
});

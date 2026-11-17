import { createFileRoute } from "@tanstack/react-router";

export const Route = createFileRoute("/setup")({
  head: () => ({ meta: [{ title: "Set your password | P2Picks" }] }),
  // The root renders App for every path; this route only provides the path and page metadata.
  component: () => null,
});

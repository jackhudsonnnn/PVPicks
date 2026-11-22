import { createFileRoute } from "@tanstack/react-router";

export const Route = createFileRoute("/dev")({
  head: () => ({ meta: [{ title: "Dev data | P2Picks" }] }),
  // The root renders App for every path; this route only provides the path and page metadata.
  component: () => null,
});

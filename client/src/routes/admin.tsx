import { createFileRoute } from "@tanstack/react-router";

export const Route = createFileRoute("/admin")({
  head: () => ({ meta: [{ title: "Admin | P2Picks" }] }),
  // The root renders App for every path; this route only provides the path and page metadata.
  component: () => null,
});

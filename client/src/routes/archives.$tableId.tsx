import { createFileRoute } from "@tanstack/react-router";

export const Route = createFileRoute("/archives/$tableId")({
  head: () => ({
    meta: [{ title: "Deleted table | P2Picks" }],
  }),
  // The root renders BackendApp for every path; this route only provides the path and page metadata.
  component: () => null,
});

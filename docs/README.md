# Documentation

The current developer handbook and roadmap live in [`site/`](site/index.md).
The dev backend serves the built site at `/docs/`. Install and build with
`npm ci --prefix docs` and `npm --prefix docs run build`.

The VitePress 1.x and Mermaid 11 dependencies currently use scoped overrides
for patched Vite 6 and KaTeX releases outside their declared ranges. Recheck
these overrides when upgrading the upstream packages; validate the docs build,
local dev server and Mermaid diagrams before removing or changing them.

- [`reference/`](reference/domain-rules.md): working domain, contract and test references.
- [`adr/`](adr/0001-modular-monolith.md): architecture decision records.
The guides, current references and accepted decisions describe current
behavior and planned work.

To view while editing, run `npm --prefix docs run dev` (live reload at
`http://localhost:5173/docs/`, or the next free port; pass `-- --port 5180` to
pick one). The running dev backend also serves the last build at
`http://127.0.0.1:8090/docs/`; rebuild with `npm --prefix docs run build`.

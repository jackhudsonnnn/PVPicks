# Documentation decisions

These decisions apply to the current developer handbook.

## Source of truth

The guides, roadmap, domain rules and accepted ADRs describe current design
decisions and implementation status. Draft contracts describe intended
interfaces and must not be mistaken for implemented behavior.

## Availability and presentation

The documentation is served at `/docs/` by the dev backend, without login on
the dev server. It is not enabled for LAN or production. The dev backend must
not be exposed to a network: these routes do not independently restrict source
addresses. The site uses a
dark theme, local search and locally bundled Mermaid diagrams; it does not
depend on a CDN.

## Operational safety

The existing environment may be shared. Preserve databases and credentials;
destructive resets or migrations of shared data require explicit approval.
Tests should use disposable resources. The project roadmap permits parallel
work where dependencies allow.

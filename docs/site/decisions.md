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

The project is pre-MVP and the dev environment is local and not shared:
development data is disposable, and the database, infrastructure and local
credentials may be reset, migrated or regenerated. Credentials stay out of
source control and the dev stack stays off the network. Tests use disposable
resources. Before any shared, LAN or production environment exists, this
changes to preserving data and approving destructive operations. The project
roadmap permits parallel work where dependencies allow.

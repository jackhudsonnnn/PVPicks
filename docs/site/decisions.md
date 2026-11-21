# Documentation decisions

These decisions apply to the current developer handbook.

## Source of truth

The guides, roadmap, domain rules and accepted ADRs describe current design
decisions and implementation status. Draft contracts describe intended
interfaces and must not be mistaken for implemented behavior.

## Availability and presentation

The documentation is served at `/docs/` by the backend. In dev it needs no
sign-in. On LAN and in production only a signed-in admin can read it, from
the **Handbook** link in the admin portal; anyone else is sent to `/admin`
(owner decision, 2026-11-21), because it describes security and operations.
The dev backend must not be exposed to a network. The site uses a
dark theme, local search and locally bundled Mermaid diagrams; it does not
depend on a CDN.

## Operational safety

- **Dev** is local and not shared. Its data is disposable, and the database,
  infrastructure and local credentials may be reset, migrated or
  regenerated. The dev stack stays off the network.
- **LAN** data belongs to real players while a party is on: no resets,
  wipes or destructive operations during one. The host saves (optionally)
  and wipes it afterwards ([LAN watch party](/lan)).
- **Production**, once it exists, keeps its data and backs it up, and
  destructive operations need the owner's approval.

In every mode, credentials stay out of source control and tests use
disposable resources. The [roadmap](/roadmap) permits parallel work where
dependencies allow.

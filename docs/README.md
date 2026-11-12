# Documentation

The current developer handbook and roadmap live in [`site/`](site/index.md).
The dev backend serves the built site at `/docs/`. Install and build with
`npm ci --prefix docs` and `npm --prefix docs run build`.

- [`reference/`](reference/domain-rules.md): working domain, contract and test references.
- [`adr/`](adr/0001-modular-monolith.md): architecture decision records.
- [`architecture-diary.md`](architecture-diary.md): current decisions and progress.
- [`archive/`](archive/p2picks-backend-kt_v3.md): preserved historical plans and diary.

The new guides and roadmap are authoritative; archived statements to the
contrary are historical. Reconcile unresolved historical requirements before
implementation rather than assuming they were all migrated.

---
layout: home
hero:
  name: P2Picks
  text: Engineering handbook
  tagline: The practical guide to building, running, and understanding the project.
  actions:
    - theme: brand
      text: Start developing
      link: /getting-started
    - theme: alt
      text: Explore the architecture
      link: /architecture
features:
  - title: Know the system
    details: Follow the current modular-monolith design, its boundaries, and the paths data takes.
    link: /architecture
  - title: See what is real
    details: Separate implemented dev features from drafts, prototypes, and planned work.
    link: /status
  - title: Find the next milestone
    details: Track the project across frontend, backend, infrastructure, and security.
    link: /roadmap
---

::: warning Development only
The backend is not ready for real users or network exposure. The frontend is a
separate browser-local prototype, not an API client.
:::

## Documentation hierarchy

These guides, the [roadmap](/roadmap), current domain rules, and accepted ADRs
are the current working documentation. The v3 plan and original architecture
diary are preserved as **historical** source material; their earlier claims of
authority and destructive reset instructions are superseded. The OpenAPI file
is a draft contract, not proof that an endpoint exists.

The transition is not an exhaustive rule-by-rule rewrite. If a current guide
does not cover a historical rule, check the [reference and archive](/reference),
then record an explicit current decision before relying on it.

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
The backend is not ready for real users or network exposure. The frontend
connects sign-in, table creation and management, members and invitations,
friends (follows and blocks), U2Pick betting, settle-up receipts, table chat
and a live sports ticker; your profile and preferences, Quick Pick
subscriptions and sports bets remain unavailable.
:::

## Documentation hierarchy

These guides, the [roadmap](/roadmap), current domain rules, and accepted ADRs
are the current working documentation. The OpenAPI file is a draft contract,
not proof that an endpoint exists. See the [references](/reference) for
detailed rules and implementation guidance.

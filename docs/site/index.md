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
  - title: See what is implemented
    details: What's built today, and what's still a draft or planned.
    link: /status
  - title: Find the next milestone
    details: Track the project across frontend, backend, infrastructure, and security.
    link: /roadmap
---

::: info Where it stands
P2Picks runs in three modes. **dev** is local, with a demo world. **LAN** is a
watch party on one Wi-Fi network and has run real parties
([LAN watch party](/lan)). **prod** isn't set up yet: it's the next
milestone, the MVP, after more security hardening and UI cleanup
([Roadmap](/roadmap)). [Project status](/status) lists what's built.
:::

## Documentation hierarchy

These guides, the [roadmap](/roadmap), current domain rules, and accepted ADRs
are the current working documentation. The OpenAPI file is a draft contract,
not proof that an endpoint exists. See the [references](/reference) for
detailed rules and implementation guidance.

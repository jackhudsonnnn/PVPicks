import { withMermaid } from "vitepress-plugin-mermaid";

const config = withMermaid({
  title: "P2Picks Engineering",
  description: "Developer documentation for the P2Picks project.",
  base: "/docs/",
  appearance: "force-dark",
  cleanUrls: false,
  mermaid: { securityLevel: "strict", theme: "dark" },
  vite: { optimizeDeps: { include: ["mermaid > fastdom", "mermaid > fastdom/extensions/fastdom-promised.js"] } },
  themeConfig: {
    siteTitle: "P2Picks / Engineering",
    search: { provider: "local" },
    nav: [
      { text: "Guide", link: "/getting-started" },
      { text: "Architecture", link: "/architecture" },
      { text: "Roadmap", link: "/roadmap" },
      { text: "Reference", link: "/reference" }
    ],
    sidebar: [
      {
        text: "START HERE",
        items: [
          { text: "Overview", link: "/" },
          { text: "Getting started", link: "/getting-started" },
          { text: "Project status", link: "/status" }
        ]
      },
      {
        text: "BUILD THE SYSTEM",
        items: [
          { text: "Architecture", link: "/architecture" },
          { text: "Backend", link: "/backend" },
          { text: "Frontend", link: "/frontend" },
          { text: "Domain & privacy", link: "/domain" },
          { text: "Player stats", link: "/player-stats" },
          { text: "Dev sports data", link: "/dev-sports-data" },
          { text: "Demo world", link: "/demo-data" },
          { text: "Roadmap", link: "/roadmap" }
        ]
      },
      {
        text: "GO DEEPER",
        items: [
          { text: "References", link: "/reference" },
          { text: "Domain rules", link: "/reference/domain-rules" },
          { text: "API behavior notes", link: "/reference/api-notes" },
          { text: "Realtime contract", link: "/reference/realtime-contract" },
          { text: "Test guide", link: "/reference/test-guide" },
          {
            text: "Decision records",
            collapsed: true,
            items: [
              { text: "0001 Modular monolith", link: "/adr/0001-modular-monolith" },
              { text: "0002 Transactional outbox", link: "/adr/0002-transactional-outbox" },
              { text: "0003 Redis as cache only", link: "/adr/0003-redis-cache-only" },
              { text: "0004 Sports feed", link: "/adr/0004-sports-feed" },
              { text: "0005 Sports data model", link: "/adr/0005-sports-data-model" },
              { text: "Template", link: "/adr/0000-template" }
            ]
          },
          { text: "Documentation decisions", link: "/decisions" }
        ]
      }
    ],
    outline: { level: [2, 3], label: "ON THIS PAGE" },
    footer: { message: "Internal development documentation · Local dev only" }
  }
});

// The plugin prebundles debug even though the installed Mermaid tree no longer uses it.
config.vite.optimizeDeps.include = config.vite.optimizeDeps.include.filter((name) => name !== "debug");
export default config;

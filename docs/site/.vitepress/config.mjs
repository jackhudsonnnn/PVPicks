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
          { text: "Domain & privacy", link: "/domain" },
          { text: "Roadmap", link: "/roadmap" }
        ]
      },
      {
        text: "GO DEEPER",
        items: [
          { text: "References", link: "/reference" },
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

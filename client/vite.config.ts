// @lovable.dev/vite-tanstack-config already includes the following — do NOT add them manually
// or the app will break with duplicate plugins:
//   - TanStack devtools (dev-only, first), tanstackStart, viteReact, tailwindcss, tsConfigPaths,
//     nitro (build-only using cloudflare as a default target), VITE_* env injection, @ path alias,
//     React/TanStack dedupe, error logger plugins, and sandbox detection (port/host/strictPort).
// You can pass additional config via defineConfig({ vite: { ... }, etc... }) if needed.
import { defineConfig } from "@lovable.dev/vite-tanstack-config";

const backend = new URL(process.env["PVPICKS_DEV_BACKEND_URL"] ?? "http://127.0.0.1:8080");
if (
  backend.protocol !== "http:" ||
  !["127.0.0.1", "localhost"].includes(backend.hostname) ||
  backend.username ||
  backend.password ||
  backend.pathname !== "/" ||
  backend.search ||
  backend.hash
) {
  throw new Error("PVPICKS_DEV_BACKEND_URL must be an HTTP loopback origin.");
}

export default defineConfig({
  vite: {
    server: {
      host: "127.0.0.1",
      port: 5173,
      strictPort: true,
      proxy: {
        "/api": {
          target: backend.origin,
          rewrite: (path) => path.replace(/^\/api(?=\/|$)/, ""),
          // Realtime updates use a WebSocket at /api/ws.
          ws: true,
        },
      },
    },
  },
  tanstackStart: {
    // Redirect TanStack Start's bundled server entry to src/server.ts (our SSR error wrapper).
    // nitro/vite builds from this
    server: { entry: "server" },
  },
});

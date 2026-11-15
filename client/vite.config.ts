// @lovable.dev/vite-tanstack-config already includes the following — do NOT add them manually
// or the app will break with duplicate plugins:
//   - TanStack devtools (dev-only, first), tanstackStart, viteReact, tailwindcss, tsConfigPaths,
//     nitro (build-only using cloudflare as a default target), VITE_* env injection, @ path alias,
//     React/TanStack dedupe, error logger plugins, and sandbox detection (port/host/strictPort).
// You can pass additional config via defineConfig({ vite: { ... }, etc... }) if needed.
import { defineConfig } from "@lovable.dev/vite-tanstack-config";
import { loadEnv } from "vite";

// The shell wins; otherwise a git-ignored `.env.local` can set it per machine. Only PVPICKS_* keys are read.
const backend = new URL(
  process.env["PVPICKS_DEV_BACKEND_URL"] ??
    loadEnv("development", process.cwd(), "PVPICKS_")["PVPICKS_DEV_BACKEND_URL"] ??
    "http://127.0.0.1:8080",
);
// In Docker Compose the dev server runs in its own container: it listens on every interface (Compose publishes it
// on loopback only) and reaches the backend by its service name.
const inDocker = process.env["PVPICKS_CLIENT_IN_DOCKER"] === "true";
const allowedBackendHosts = inDocker ? ["backend"] : ["127.0.0.1", "localhost"];
if (
  backend.protocol !== "http:" ||
  !allowedBackendHosts.includes(backend.hostname) ||
  backend.username ||
  backend.password ||
  backend.pathname !== "/" ||
  backend.search ||
  backend.hash
) {
  throw new Error(
    inDocker
      ? "PVPICKS_DEV_BACKEND_URL must be http://backend:<port> in Docker Compose."
      : "PVPICKS_DEV_BACKEND_URL must be an HTTP loopback origin.",
  );
}

export default defineConfig({
  vite: {
    server: {
      host: inDocker ? "0.0.0.0" : "127.0.0.1",
      port: 5173,
      strictPort: true,
      proxy: {
        "/api": {
          target: backend.origin,
          rewrite: (path) => path.replace(/^\/api(?=\/|$)/, ""),
          // Realtime updates use a WebSocket at /api/ws.
          ws: true,
        },
        // The docs (built into the backend, dev only), on the app's own address.
        "/docs": { target: backend.origin },
      },
    },
  },
  tanstackStart: {
    // Redirect TanStack Start's bundled server entry to src/server.ts (our SSR error wrapper).
    // nitro/vite builds from this
    server: { entry: "server" },
  },
});

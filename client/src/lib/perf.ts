/**
 * Browser performance reporting for the admin Perf tab. `request()` records every API call's method, path, status
 * and duration here; page loads and largest contentful paint are added once per load. While signed in, samples
 * go to `POST /perf` about once a minute and when the tab is hidden; the server files API calls under the route
 * they matched and drops anything it doesn't recognize. Reporting never surfaces errors to the player.
 */

interface Sample {
  kind: "api" | "page" | "lcp";
  method?: string;
  path: string;
  status?: number;
  ms: number;
}

const MAX_BUFFER = 200;
const BATCH = 100;
const FLUSH_MS = 60_000;
let buffer: Sample[] = [];

function push(sample: Sample) {
  if (buffer.length >= MAX_BUFFER) return;
  buffer.push(sample);
}

/** Called by `request()` for each API call; status 0 means no response. */
export function recordApiCall(method: string, path: string, status: number, ms: number) {
  if (path.startsWith("/perf")) return;
  push({
    kind: "api",
    method: method.toUpperCase(),
    path: path.split("?")[0] ?? path,
    status,
    ms: Math.round(ms),
  });
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** The app route for a page path: ids become `$id`, so `/tables/4f…` reports as `/tables/$id`. */
export function pageRoute(pathname: string): string | null {
  const segments = pathname.split("/").filter(Boolean).slice(0, 5);
  const route = `/${segments.map((s) => (UUID.test(s) ? "$id" : s.toLowerCase())).join("/")}`;
  return /^\/([a-z0-9-]+|\$id)?(\/([a-z0-9-]+|\$id)){0,4}$/.test(route) ? route : null;
}

/** The pending samples, emptied (tests and the flush). */
export function takeSamples(limit = BATCH): Sample[] {
  const taken = buffer.slice(0, limit);
  buffer = buffer.slice(limit);
  return taken;
}

function observePage() {
  const route = pageRoute(window.location.pathname);
  if (!route || typeof performance === "undefined") return () => {};
  const recordLoad = () => {
    const navigation = performance.getEntriesByType?.("navigation")[0] as
      PerformanceNavigationTiming | undefined;
    if (navigation && navigation.loadEventEnd > 0)
      push({ kind: "page", path: route, ms: Math.round(navigation.loadEventEnd) });
  };
  if (document.readyState === "complete") setTimeout(recordLoad, 0);
  else window.addEventListener("load", () => setTimeout(recordLoad, 0), { once: true });

  if (
    typeof PerformanceObserver === "undefined" ||
    !PerformanceObserver.supportedEntryTypes?.includes("largest-contentful-paint")
  )
    return () => {};
  let latest = 0;
  const observer = new PerformanceObserver((list) => {
    for (const entry of list.getEntries()) latest = Math.max(latest, entry.startTime);
  });
  observer.observe({ type: "largest-contentful-paint", buffered: true });
  // LCP is final once the user interacts or the page hides.
  const finish = () => {
    if (latest > 0) push({ kind: "lcp", path: route, ms: Math.round(latest) });
    latest = 0;
    observer.disconnect();
  };
  window.addEventListener("pointerdown", finish, { once: true });
  document.addEventListener("visibilitychange", finish, { once: true });
  return () => observer.disconnect();
}

async function flush() {
  const samples = takeSamples();
  if (samples.length === 0) return;
  try {
    const token = await fetch("/api/auth/csrf", { credentials: "same-origin", cache: "no-store" })
      .then((response) => (response.ok ? response.json() : null))
      .then((body: { token?: string } | null) => body?.token);
    if (!token) return;
    await fetch("/api/perf", {
      method: "POST",
      credentials: "same-origin",
      keepalive: true,
      headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": token },
      body: JSON.stringify({ samples }),
    });
  } catch {
    // Timings are best effort: a failed report is dropped, never retried or shown.
  }
}

/** Starts reporting for a signed-in session; returns a stop function. */
export function startPerfReporting(): () => void {
  const stopPage = observePage();
  const timer = setInterval(() => void flush(), FLUSH_MS);
  const onHide = () => {
    if (document.visibilityState === "hidden") void flush();
  };
  document.addEventListener("visibilitychange", onHide);
  return () => {
    clearInterval(timer);
    document.removeEventListener("visibilitychange", onHide);
    stopPage();
    buffer = [];
  };
}

import { vi } from "vitest";

export function useIsolatedBackend() {
  const port = Number(process.env["PVPICKS_TEST_PORT"]);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Invalid test port");
  const nativeFetch = globalThis.fetch;
  const cookies = new Map<string, string>();
  vi.stubGlobal("fetch", async (input: string, init?: RequestInit) => {
    if (!input.startsWith("/api/")) throw new Error("Unexpected request URL");
    const headers = new Headers(init?.headers);
    headers.set("Origin", "http://127.0.0.1:8080");
    if (cookies.size) headers.set("Cookie", [...cookies.values()].join("; "));
    const response = await nativeFetch(`http://127.0.0.1:${port}${input.slice(4)}`, {
      ...init,
      headers,
    });
    for (const cookie of response.headers.getSetCookie()) {
      const pair = cookie.split(";", 1)[0];
      if (!pair || !pair.includes("=")) throw new Error("Invalid cookie");
      cookies.set(pair.slice(0, pair.indexOf("=")), pair);
    }
    return response;
  });
}

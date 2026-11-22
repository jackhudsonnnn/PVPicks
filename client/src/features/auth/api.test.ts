import { afterEach, describe, expect, it, vi } from "vitest";
import { json, user } from "@/test/api-fixtures";
import { currentUser, signIn, signOut } from "./api";

afterEach(() => vi.unstubAllGlobals());

describe("session API", () => {
  it("uses a fresh CSRF token for login and verifies the session", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json({ token: "login-token" }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(json(user));
    vi.stubGlobal("fetch", fetch);
    await expect(signIn("testuser", "password")).resolves.toEqual(user);
    expect(fetch.mock.calls.map(([url]) => url)).toEqual([
      "/api/auth/csrf",
      "/api/auth/login",
      "/api/me",
    ]);
    expect(fetch.mock.calls[1]?.[1]).toMatchObject({
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": "login-token" },
      body: JSON.stringify({ username: "testuser", password: "password" }),
    });
  });

  it("uses a new token to revoke the session", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json({ token: "logout-token" }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetch);
    await signOut();
    expect(fetch.mock.calls[1]?.[0]).toBe("/api/auth/logout");
    expect(fetch.mock.calls[1]?.[1]).toMatchObject({
      method: "POST",
      headers: { "X-CSRF-TOKEN": "logout-token" },
    });
  });

  it("reports failed credentials separately from an unverifiable new session", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json({ token: "login-token" }))
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(json({ token: "next-token" }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(null, { status: 401 }));
    vi.stubGlobal("fetch", fetch);
    await expect(signIn("testuser", "bad")).rejects.toMatchObject({ status: 401 });
    await expect(signIn("testuser", "password")).rejects.toThrow("session could not be verified");
  });

  it("distinguishes an absent session from service failure and invalid data", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(new Response(null, { status: 503 }))
      .mockResolvedValueOnce(json({ id: "invalid" }));
    vi.stubGlobal("fetch", fetch);
    await expect(currentUser()).resolves.toBeNull();
    await expect(currentUser()).rejects.toMatchObject({ status: 503 });
    await expect(currentUser()).rejects.toThrow("unexpected response");
  });
});

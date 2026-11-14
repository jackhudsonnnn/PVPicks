import { afterEach, describe, expect, it, vi } from "vitest";
import {
  BackendError,
  currentUser,
  getBet,
  getProfile,
  getTable,
  listTableBets,
  listTables,
  signIn,
  signOut,
} from "./backend-api";

const user = {
  id: "123e4567-e89b-42d3-a456-426614174000",
  username: "testuser",
  hue: 2,
  role: "PLAYER" as const,
};
const table = {
  id: "123e4567-e89b-42d3-a456-426614174001",
  name: "Test table",
  emoji: "🏟️",
  memberIds: [user.id],
  hostIds: [user.id],
  remainderRule: "host",
  hostsOnlyBets: false,
  u2pickEnabled: true,
  quickPicksEnabled: false,
  quickPickSharing: false,
  subscribedTo: [],
  disabledPermissions: {},
};
const bet = {
  id: "123e4567-e89b-42d3-a456-426614174002",
  tableId: table.id,
  proposerId: user.id,
  modeId: "u2pick",
  config: {},
  question: "Who wins?",
  choices: ["Yes", "No"],
  wagerCents: 100,
  forFun: false,
  status: "ACTIVE",
  locksAt: "2026-11-11T12:00:00+00:00",
  pointsSettled: false,
  pickCount: 1,
  myPick: "Yes",
  myParticipation: "picked",
};
const json = (value: unknown) => new Response(JSON.stringify(value), { status: 200 });

afterEach(() => vi.unstubAllGlobals());

describe("backend session and table API", () => {
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

  it("reads table list and detail and rejects invalid identities", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json([table]))
      .mockResolvedValueOnce(json(table));
    vi.stubGlobal("fetch", fetch);
    await expect(listTables()).resolves.toEqual([table]);
    await expect(getTable(table.id)).resolves.toEqual(table);
    expect(fetch.mock.calls[1]?.[0]).toBe(`/api/tables/${table.id}`);
    await expect(getTable("../auth/logout")).rejects.toBeInstanceOf(BackendError);
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it("preserves generic UUIDs and validates string-keyed response records", async () => {
    const genericId = "123e4567-e89b-02d3-0456-426614174000";
    const withPermissions = {
      ...table,
      id: genericId,
      disabledPermissions: { [user.id]: ["bets"] },
    };
    const withConfig = { ...bet, config: { options: ["Yes", "No"], count: 2 } };
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json([withPermissions]))
      .mockResolvedValueOnce(json([withConfig]))
      .mockResolvedValueOnce(json([{ ...withConfig, config: { options: { invalid: true } } }]));
    vi.stubGlobal("fetch", fetch);
    await expect(listTables()).resolves.toEqual([withPermissions]);
    await expect(listTableBets(table.id)).resolves.toEqual([withConfig]);
    await expect(listTableBets(table.id)).rejects.toThrow("unexpected response");
  });

  it("reads active and locked bet projections from the correct endpoints", async () => {
    const locked = {
      ...bet,
      status: "PENDING",
      picks: [{ userId: user.id, choice: "Yes" }],
      noGuessUserIds: [],
      remainderRule: "host",
      remainderHostId: user.id,
    };
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json([bet]))
      .mockResolvedValueOnce(json(locked));
    vi.stubGlobal("fetch", fetch);
    await expect(listTableBets(table.id)).resolves.toEqual([bet]);
    await expect(getBet(table.id, bet.id)).resolves.toEqual(locked);
    expect(fetch.mock.calls.map(([url]) => url)).toEqual([
      `/api/tables/${table.id}/bets`,
      `/api/bets/${bet.id}`,
    ]);
    await expect(getBet(table.id, "../auth/logout")).rejects.toMatchObject({ status: 404 });
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it("rejects leaked active picks, malformed projections and mismatched identities", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json([{ ...bet, picks: [{ userId: user.id, choice: "No" }] }]))
      .mockResolvedValueOnce(json({ ...bet, myPick: null }))
      .mockResolvedValueOnce(json({ ...bet, tableId: user.id }))
      .mockResolvedValueOnce(json([{ ...bet, tableId: user.id }]));
    vi.stubGlobal("fetch", fetch);
    await expect(listTableBets(table.id)).rejects.toThrow("unexpected response");
    await expect(getBet(table.id, bet.id)).rejects.toThrow("unexpected response");
    await expect(getBet(table.id, bet.id)).rejects.toThrow("unexpected response");
    await expect(listTableBets(table.id)).rejects.toThrow("unexpected response");
  });

  it("reads live and deleted profiles, rejects mismatches and propagates lookup errors", async () => {
    const deleted = { id: table.id, displayName: "Deleted user", deleted: true };
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json(user))
      .mockResolvedValueOnce(json(deleted))
      .mockResolvedValueOnce(json(user))
      .mockResolvedValueOnce(json({ ...deleted, deleted: false }))
      .mockResolvedValueOnce(new Response(null, { status: 404 }));
    vi.stubGlobal("fetch", fetch);
    const { role: _role, ...profile } = user;
    await expect(getProfile(user.id)).resolves.toEqual(profile);
    await expect(getProfile(table.id)).resolves.toEqual(deleted);
    expect(fetch.mock.calls[0]?.[0]).toBe(`/api/users/${user.id}`);
    await expect(getProfile(table.id)).rejects.toThrow("unexpected response");
    await expect(getProfile(table.id)).rejects.toThrow("unexpected response");
    await expect(getProfile(table.id)).rejects.toMatchObject({ status: 404 });
    await expect(getProfile("../auth/logout")).rejects.toMatchObject({ status: 404 });
    expect(fetch).toHaveBeenCalledTimes(5);
  });
});

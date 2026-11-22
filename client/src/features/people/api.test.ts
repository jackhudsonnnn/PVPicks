import { afterEach, describe, expect, it, vi } from "vitest";
import { json, table, user } from "@/test/api-fixtures";
import { getProfile } from "./api";

afterEach(() => vi.unstubAllGlobals());

describe("profiles API", () => {
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

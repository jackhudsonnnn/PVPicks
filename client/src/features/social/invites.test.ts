import { describe, expect, it } from "vitest";
import type { Table } from "@/features/table/types";
import type { Follows } from "./follows";
import { DEFAULT_INVITE_PREFS, addablePlayers, inviteOutcome, type InvitePrefs } from "./invites";

// host and friend follow each other; fan follows host; host follows stranger only.
const f: Follows = { host: ["friend", "stranger"], friend: ["host"], fan: ["host"] };

describe("table invite outcome", () => {
  it("friends with Open join right away", () => {
    expect(inviteOutcome(f, { friends: "open", following: "closed" }, "friend", "host")).toBe(
      "add",
    );
  });

  it("friends with Request get a request", () => {
    expect(inviteOutcome(f, { friends: "request", following: "open" }, "friend", "host")).toBe(
      "request",
    );
  });

  it("the Following setting applies when you follow the host but they don't follow back", () => {
    const p: InvitePrefs = { friends: "open", following: "request" };
    expect(inviteOutcome(f, p, "fan", "host")).toBe("request");
    expect(inviteOutcome(f, { ...p, following: "closed" }, "fan", "host")).toBe("blocked");
    expect(inviteOutcome(f, { ...p, following: "open" }, "fan", "host")).toBe("add");
  });

  it("someone who doesn't follow the host can never be added", () => {
    const allOpen: InvitePrefs = { friends: "open", following: "open" };
    expect(inviteOutcome(f, allOpen, "stranger", "host")).toBe("blocked");
  });

  it("defaults: friends Open, following Request", () => {
    expect(DEFAULT_INVITE_PREFS).toEqual({ friends: "open", following: "request" });
  });
});

describe("add all friends / followers", () => {
  const table: Table = { id: "t", name: "T", emoji: "🏈", memberIds: ["host"], hostIds: ["host"] };

  it("friends are the mutual follows; followers include friends", () => {
    const r = addablePlayers(f, () => DEFAULT_INVITE_PREFS, table, [], "host");
    expect(r.friends).toEqual(["friend"]);
    expect(r.followers.sort()).toEqual(["fan", "friend"]);
  });

  it("skips members, pending requests and closed settings", () => {
    const t = { ...table, memberIds: ["host", "friend"] };
    const invites = [{ id: "i", tableId: "t", fromId: "host", userId: "fan" }];
    expect(addablePlayers(f, () => DEFAULT_INVITE_PREFS, t, invites, "host").followers).toEqual([]);
    const closed = addablePlayers(
      f,
      () => ({ friends: "closed", following: "closed" }),
      table,
      [],
      "host",
    );
    expect(closed).toEqual({ friends: [], followers: [] });
  });
});

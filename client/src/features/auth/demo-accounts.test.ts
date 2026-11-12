import { describe, expect, it } from "vitest";
import { checkLogin } from "./demo-accounts";

describe("demo sign-in", () => {
  it("each demo account signs in with its own password", () => {
    expect(checkLogin("newbie", "newbie123")).toBe("newbie");
    expect(checkLogin("intermediate", "inter123")).toBe("intermediate");
    expect(checkLogin("pro", "pro123")).toBe("pro");
    expect(checkLogin("admin", "admin123")).toBe("admin");
  });

  it("username ignores case and spaces, password does not", () => {
    expect(checkLogin("  PRO ", "pro123")).toBe("pro");
    expect(checkLogin("pro", "PRO123")).toBeNull();
  });

  it("wrong password or unknown user fails", () => {
    expect(checkLogin("pro", "newbie123")).toBeNull();
    expect(checkLogin("jack", "pro123")).toBeNull();
  });
});

import { DEMO_ACCOUNTS, PLAYER_ACCOUNTS } from "./demo-accounts";
import { seedFor } from "@/features/mock/profiles";
import { ME } from "@/features/mock/seed";
import { accountStorageKeys } from "./storage-keys";

describe("each demo account is its own person", () => {
  it("no two accounts share a username", () => {
    const names = DEMO_ACCOUNTS.map((a) => a.persona.username);
    expect(new Set(names).size).toBe(DEMO_ACCOUNTS.length);
  });

  it("signing in as newbie / intermediate / pro shows that account's username, not Jack", () => {
    const me = (id: (typeof PLAYER_ACCOUNTS)[number]) =>
      seedFor(id).users.find((u) => u.id === ME)!.username;
    expect(me("newbie")).toBe("newbie_nate");
    expect(me("intermediate")).toBe("inter_ivy");
    expect(me("pro")).toBe("pro_pat");
  });

  it("a signed-up account gets its own username in the brand-new world", () => {
    const u = seedFor("newbie", { username: "casey_l", hue: 3 }).users;
    expect(u.find((x) => x.id === ME)).toMatchObject({ username: "casey_l" });
  });

  it("saved profile, inbox and blocks never share a key between accounts", () => {
    const a = Object.values(accountStorageKeys("newbie"));
    const b = Object.values(accountStorageKeys("pro"));
    expect(a.some((k) => b.includes(k))).toBe(false);
  });
});

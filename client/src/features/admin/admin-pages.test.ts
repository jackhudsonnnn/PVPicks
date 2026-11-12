import { describe, expect, it } from "vitest";
import { applyAdminAction, auditUser } from "./user-audit";
import { healthStatus, healthTick, type HealthPoint } from "./health";
import { seedFor } from "@/features/mock/profiles";
import { ME } from "@/features/mock/seed";

describe("admin actions", () => {
  it("third strike freezes the account", () => {
    let f = {};
    for (let i = 0; i < 3; i++) f = applyAdminAction(f, { type: "strike" });
    expect(f).toMatchObject({ strikes: 3, frozen: true });
  });
  it("strikes cap at 3 and can be cleared", () => {
    let f: import("./user-audit").AdminFlags = { strikes: 3 };
    f = applyAdminAction(f, { type: "strike" });
    expect(f.strikes).toBe(3);
    expect(applyAdminAction(f, { type: "clearStrikes" }).strikes).toBe(0);
  });
  it("blank notes are ignored", () => {
    expect(applyAdminAction({}, { type: "note", text: "  " }).notes).toBeUndefined();
  });
});

describe("user audit", () => {
  it("net matches the sum of the user's resolved deltas", () => {
    const w = seedFor("pro");
    const a = auditUser(ME, w, []);
    const expected = w.bets
      .filter((b) => b.status === "resolved" && !b.forFun)
      .reduce((s, b) => s + (b.deltas?.[ME] ?? 0), 0);
    expect(a.net).toBeCloseTo(expected, 2);
    expect(a.tickets.every((b) => b.participants.some((p) => p.userId === ME))).toBe(true);
  });
});

describe("site health", () => {
  it("p95 is never below p50", () => {
    let pts: HealthPoint[] = [];
    for (let i = 0; i < 50; i++) pts = healthTick(pts, Math.random);
    expect(pts).toHaveLength(40);
    expect(pts.every((p) => p.p95 >= p.p50)).toBe(true);
  });
  it("3 errors in the last 10 samples is degraded", () => {
    const pts = Array.from({ length: 10 }, (_, i) => ({
      t: i,
      p50: 80,
      p95: 150,
      errors: i < 3 ? 1 : 0,
    }));
    expect(healthStatus(pts)).toBe("degraded");
    expect(healthStatus(pts.map((p) => ({ ...p, errors: 0 })))).toBe("healthy");
    expect(healthStatus([])).toBe("down");
  });
});

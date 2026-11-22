import { describe, expect, it } from "vitest";
import { ticketTab } from "./ticket-tabs";

const order = ["ACTIVE", "PENDING", "SETTLED", "WASHED"] as const;
const tab = (current: (typeof order)[number], ...empty: (typeof order)[number][]) =>
  ticketTab(order, current, new Set(empty));

describe("ticketTab", () => {
  it("stays on a tab with tickets", () => {
    expect(tab("ACTIVE", "PENDING")).toBe("ACTIVE");
  });

  it("moves to the next tab with tickets when the current one empties", () => {
    expect(tab("ACTIVE", "ACTIVE")).toBe("PENDING");
    expect(tab("ACTIVE", "ACTIVE", "PENDING")).toBe("SETTLED");
  });

  it("falls back to an earlier tab when nothing after it has tickets", () => {
    expect(tab("WASHED", "WASHED", "SETTLED")).toBe("PENDING");
  });

  it("stays put when every tab is empty", () => {
    expect(tab("PENDING", ...order)).toBe("PENDING");
  });
});

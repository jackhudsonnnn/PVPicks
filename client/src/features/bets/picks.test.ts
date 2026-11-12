import { describe, expect, it } from "vitest";
import { applyPick, isValidPick } from "./picks";
import { PASS } from "./types";

describe("picking joins the bet", () => {
  const start = [{ userId: "a", selection: "Yes" }];

  it("choosing an answer adds you to the bet", () => {
    expect(applyPick(start, "me", "No")).toEqual([...start, { userId: "me", selection: "No" }]);
  });

  it("clearing your guess keeps you in the bet with no guess", () => {
    const joined = applyPick(start, "me", "No");
    expect(applyPick(joined, "me", PASS)).toEqual([...start, { userId: "me", selection: PASS }]);
  });

  it("clearing when you never joined changes nothing", () => {
    expect(applyPick(start, "me", PASS)).toBe(start);
  });

  it("changing your answer updates it in place", () => {
    expect(applyPick(start, "a", "No")).toEqual([{ userId: "a", selection: "No" }]);
  });
});

describe("valid picks", () => {
  it("only the bet's choices or Pass count as a pick", () => {
    const b = { choices: ["Yes", "No"] };
    expect(isValidPick(b, "Yes")).toBe(true);
    expect(isValidPick(b, PASS)).toBe(true);
    expect(isValidPick(b, "Maybe")).toBe(false);
  });

  it("treats a sports pass choice as a pick, not no guess", () => {
    const b = { choices: ["pass", "run"] };
    expect(isValidPick(b, "pass")).toBe(true);
    const joined = applyPick([], "me", "pass");
    expect(joined).toEqual([{ userId: "me", selection: "pass" }]);
    expect(applyPick(joined, "me", PASS)).toEqual([{ userId: "me", selection: PASS }]);
  });
});

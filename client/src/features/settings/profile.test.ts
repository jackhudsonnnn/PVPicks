import { describe, expect, it } from "vitest";
import { normalizeUsername, usernameError } from "./profile";

describe("username rules", () => {
  it("usernames are 3–15 characters", () => {
    expect(usernameError("ab", [])).toBeDefined();
    expect(usernameError("abc", [])).toBeUndefined();
    expect(usernameError("a".repeat(15), [])).toBeUndefined();
    expect(usernameError("a".repeat(16), [])).toBeDefined();
  });

  it("only letters, numbers and underscores", () => {
    expect(usernameError("big_dog_99", [])).toBeUndefined();
    expect(usernameError("jack.hudson", [])).toBeDefined();
  });

  it("are unique ignoring case", () => {
    expect(usernameError("MayaPlays", ["mayaplays"])).toBeDefined();
    expect(usernameError("mayaplays", ["MAYAPLAYS"])).toBeDefined();
    expect(normalizeUsername("  JackH ")).toBe("jackh");
  });
});

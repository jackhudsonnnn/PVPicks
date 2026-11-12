import { describe, expect, it } from "vitest";
import {
  checkLocalLogin,
  createLocalAccount,
  personaOf,
  signUpErrors,
  type SignUpInput,
} from "./local-accounts";

const ok: SignUpInput = {
  username: "casey_l",
  password: "goodpass1",
  confirm: "goodpass1",
};

describe("sign-up rules", () => {
  it("a complete form has no errors", () => {
    expect(signUpErrors(ok, [])).toEqual({});
  });

  it("the password has to be typed the same twice", () => {
    expect(signUpErrors({ ...ok, confirm: "goodpass2" }, []).confirm).toBe(
      "Passwords don't match.",
    );
  });

  it("passwords need at least 8 characters", () => {
    expect(
      signUpErrors({ ...ok, password: "short12", confirm: "short12" }, []).password,
    ).toBeDefined();
    expect(
      signUpErrors({ ...ok, password: "exactly8", confirm: "exactly8" }, []).password,
    ).toBeUndefined();
  });

  it("usernames are 3-15 letters, numbers or underscores", () => {
    expect(signUpErrors({ ...ok, username: "ab" }, []).username).toBeDefined();
    expect(signUpErrors({ ...ok, username: "a".repeat(16) }, []).username).toBeDefined();
    expect(signUpErrors({ ...ok, username: "has space" }, []).username).toBeDefined();
  });

  it("a taken username is rejected regardless of case", () => {
    expect(signUpErrors({ ...ok, username: "PRO" }, ["pro"]).username).toBe(
      "Someone already has that username.",
    );
  });
});

describe("signed-up sign-in", () => {
  it("signs in with the right password only, username ignores case", async () => {
    const a = await createLocalAccount(ok, "salt1", 0);
    expect(a.passwordHash).not.toContain("goodpass1");
    expect(await checkLocalLogin([a], "  CASEY_L ", "goodpass1")).toBe(a);
    expect(await checkLocalLogin([a], "casey_l", "GOODPASS1")).toBeNull();
    expect(await checkLocalLogin([a], "someone", "goodpass1")).toBeNull();
  });

  it("the person shown in the app is their username", async () => {
    const a = await createLocalAccount(ok, "salt1", 0);
    expect(personaOf(a)).toMatchObject({ username: "casey_l" });
  });
});

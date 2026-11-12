import { describe, expect, it } from "vitest";
import {
  followersOf,
  followingOf,
  friendsOf,
  isFriend,
  relationOf,
  setFollow,
  type Follows,
} from "./follows";

const f: Follows = { me: ["a", "b"], a: ["me"], c: ["me"] };

describe("follows", () => {
  it("people who follow each other are friends", () => {
    expect(isFriend(f, "me", "a")).toBe(true);
    expect(isFriend(f, "me", "b")).toBe(false);
    expect(friendsOf(f, "me")).toEqual(["a"]);
  });

  it("lists following and followers, friends included", () => {
    expect(followingOf(f, "me")).toEqual(["a", "b"]);
    expect(followersOf(f, "me").sort()).toEqual(["a", "c"]);
  });

  it("describes the relation from my side", () => {
    expect(relationOf(f, "me", "a")).toBe("friend");
    expect(relationOf(f, "me", "b")).toBe("following");
    expect(relationOf(f, "me", "c")).toBe("follower");
    expect(relationOf(f, "me", "z")).toBe("none");
  });

  it("following back makes a friend, unfollowing undoes it", () => {
    const g = setFollow(f, "me", "c", true);
    expect(isFriend(g, "me", "c")).toBe(true);
    expect(isFriend(setFollow(g, "me", "c", false), "me", "c")).toBe(false);
  });

  it("you can't follow yourself", () => {
    expect(setFollow(f, "me", "me", true)).toBe(f);
  });
});

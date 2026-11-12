/** Who each user follows: follower id → ids they follow. Friends are people who follow each other. */
export type Follows = Record<string, string[]>;

export const isFollowing = (f: Follows, from: string, to: string) => (f[from] ?? []).includes(to);

export const isFriend = (f: Follows, a: string, b: string) =>
  a !== b && isFollowing(f, a, b) && isFollowing(f, b, a);

/** Everyone `id` follows (friends included). */
export const followingOf = (f: Follows, id: string) => (f[id] ?? []).filter((x) => x !== id);

/** Everyone who follows `id` (friends included). */
export const followersOf = (f: Follows, id: string) =>
  Object.keys(f).filter((u) => u !== id && isFollowing(f, u, id));

/** People who follow `id` and are followed back. */
export const friendsOf = (f: Follows, id: string) =>
  followingOf(f, id).filter((x) => isFollowing(f, x, id));

/** How `me` relates to `other`. */
export type Relation = "friend" | "following" | "follower" | "none";

export function relationOf(f: Follows, me: string, other: string): Relation {
  const out = isFollowing(f, me, other);
  const back = isFollowing(f, other, me);
  if (out && back) return "friend";
  if (out) return "following";
  if (back) return "follower";
  return "none";
}

export function setFollow(f: Follows, from: string, to: string, on: boolean): Follows {
  if (from === to || isFollowing(f, from, to) === on) return f;
  const list = f[from] ?? [];
  return { ...f, [from]: on ? [...list, to] : list.filter((x) => x !== to) };
}

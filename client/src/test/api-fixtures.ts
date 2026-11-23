/** Valid API payloads for client API tests: one player, their table and an active bet they picked. */

export const user = {
  id: "123e4567-e89b-42d3-a456-426614174000",
  username: "testuser",
  hue: 2,
  role: "PLAYER" as const,
};
export const table = {
  id: "123e4567-e89b-42d3-a456-426614174001",
  name: "Test table",
  emoji: "🏟️",
  memberIds: [user.id],
  hostIds: [user.id],
  hostsOnlyBets: false,
  u2pickEnabled: true,
  pollsEnabled: true,
  jackblackAccess: "everyone" as const,
  holdemAccess: "everyone" as const,
  quickPicksEnabled: false,
  quickPickSharing: false,
  disabledPermissions: {},
};
export const bet = {
  id: "123e4567-e89b-42d3-a456-426614174002",
  tableId: table.id,
  proposerId: user.id,
  modeId: "u2pick",
  config: {},
  question: "Who wins?",
  choices: ["Yes", "No"],
  wagerCents: 100,
  forFun: false,
  status: "ACTIVE",
  locksAt: "2026-11-11T12:00:00+00:00",
  createdAt: "2026-11-11T11:58:00+00:00",
  pointsSettled: false,
  pickCount: 1,
  myPick: "Yes",
  myParticipation: "picked",
  viewerCanCall: false,
  viewerCanOverride: false,
};
/** A 200 JSON response. */
export const json = (value: unknown) => new Response(JSON.stringify(value), { status: 200 });

/** A small GIF library like `GET /gifs` returns. */
export const gifCatalog = {
  categories: [
    { id: "hype", label: "Hype" },
    { id: "money", label: "Money" },
  ],
  gifs: [
    {
      id: "g1",
      url: "https://media.tenor.com/5lPd211l4iUAAAAM/cowboys-touchdown.gif",
      alt: "Touchdown celebration",
      category: "hype",
      tags: ["touchdown"],
    },
    {
      id: "g7",
      url: "https://media.tenor.com/example/money.gif",
      alt: "Making it rain",
      category: "money",
      tags: ["money"],
    },
  ],
};

/** Mode availability like `GET /modes` returns today: U2Pick and the two score modes. */
export const modeAvailability = new Map<string, boolean>([
  ["u2pick", true],
  ["total_disaster", true],
  ["give_and_take", true],
  ["either_or", true],
  ["prop_hunt", true],
  ["king_of_the_hill", true],
  ["scorcerer", true],
  ["jackblack", true],
  ["holdem", true],
]);

/** A whole table as `GET /tables` lists it for `viewerId`: a summary with a member preview, never every member. */
export function summaryOf(
  whole: {
    id: string;
    name: string;
    emoji: string;
    memberIds: string[];
    hostIds: string[];
    disabledPermissions: Record<string, ("bets" | "text" | "react" | "gifs" | "polls")[]>;
  },
  viewerId: string,
  extra: { lastBetAt?: string; joinedAt?: string } = {},
) {
  return {
    id: whole.id,
    name: whole.name,
    emoji: whole.emoji,
    host: whole.hostIds.includes(viewerId),
    memberCount: whole.memberIds.length,
    memberPreview: whole.memberIds.slice(0, 4),
    myDisabledPermissions: whole.disabledPermissions[viewerId] ?? [],
    joinedAt: extra.joinedAt ?? "2026-11-01T00:00:00Z",
    ...(extra.lastBetAt ? { lastBetAt: extra.lastBetAt } : {}),
  };
}

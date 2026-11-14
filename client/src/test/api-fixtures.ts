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
  remainderRule: "host",
  hostsOnlyBets: false,
  u2pickEnabled: true,
  quickPicksEnabled: false,
  quickPickSharing: false,
  subscribedTo: [],
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
};
/** A 200 JSON response. */
export const json = (value: unknown) => new Response(JSON.stringify(value), { status: 200 });

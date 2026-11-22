import { z } from "zod";
import { BackendError, idSchema, read, request } from "@/lib/api-client";

const uuid = idSchema;
const betSchema = z
  .object({
    id: uuid,
    tableId: uuid,
    proposerId: uuid,
    modeId: z.enum([
      "either_or",
      "king_of_the_hill",
      "choose_their_fate",
      "scorcerer",
      "total_disaster",
      "give_and_take",
      "prop_hunt",
      "u2pick",
      "jackblack",
    ]),
    config: z.record(z.string(), z.union([z.string(), z.number(), z.array(z.string())])),
    question: z.string(),
    choices: z.array(z.string()),
    wagerCents: z.number().int().safe().nonnegative(),
    forFun: z.boolean(),
    status: z.enum(["ACTIVE", "PENDING", "SETTLED", "WASHED"]),
    locksAt: z.string().datetime({ offset: true }),
    createdAt: z.string().datetime({ offset: true }),
    lockedAt: z.string().datetime({ offset: true }).optional(),
    closedAt: z.string().datetime({ offset: true }).optional(),
    settledAt: z.string().datetime({ offset: true }).optional(),
    pointsSettled: z.boolean(),
    pickCount: z.number().int().safe().nonnegative(),
    myPick: z.string().nullable(),
    myParticipation: z.enum(["picked", "no_guess", "not_joined"]),
    /** The server's verdict on what the viewer may do; the client never re-derives these rules. */
    viewerCanCall: z.boolean(),
    viewerCanOverride: z.boolean(),
    settledBy: z.enum(["anyone", "non_participants", "hosts", "me"]).optional(),
    gameId: z.string().optional(),
    includesOvertime: z.boolean().optional(),
    overriddenBy: uuid.optional(),
    overriddenAt: z.string().datetime({ offset: true }).optional(),
    picks: z.array(z.object({ userId: uuid, choice: z.string() })).optional(),
    noGuessUserIds: z.array(uuid).optional(),
    calledBy: uuid.optional(),
    winningChoice: z.string().optional(),
    washReason: z.string().optional(),
    deltasCents: z.record(z.string(), z.number().int().safe()).optional(),
  })
  .superRefine((bet, ctx) => {
    const invalidParticipation =
      bet.myParticipation === "picked" ? !bet.myPick || bet.pickCount < 1 : bet.myPick !== null;
    const privateFieldsPresent =
      bet.picks !== undefined ||
      bet.noGuessUserIds !== undefined ||
      bet.calledBy !== undefined ||
      bet.winningChoice !== undefined ||
      bet.washReason !== undefined ||
      bet.deltasCents !== undefined;
    if (
      invalidParticipation ||
      (bet.status === "ACTIVE" && privateFieldsPresent) ||
      (bet.status !== "ACTIVE" && (bet.picks === undefined || bet.noGuessUserIds === undefined))
    ) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, message: "Invalid bet projection" });
    }
  });

export type Bet = z.infer<typeof betSchema>;

export async function listTableBets(tableId: string, signal?: AbortSignal): Promise<Bet[]> {
  if (!uuid.safeParse(tableId).success) throw new BackendError("Table not found.", 404);
  const bets = await read(
    await request(`/tables/${encodeURIComponent(tableId)}/bets`, signal ? { signal } : undefined),
    z.array(betSchema),
  );
  if (bets.some((bet) => bet.tableId !== tableId))
    throw new BackendError("The backend returned an unexpected response.");
  return bets;
}

const betPageSchema = z.object({
  items: z.array(betSchema),
  nextCursor: z.string().min(1).nullable(),
});
export type BetPage = z.infer<typeof betPageSchema>;

/** Your bets across your current tables (or one), newest first. */
export async function listMyBets(
  filter: { status?: Bet["status"]; tableId?: string; cursor?: string; limit?: number },
  signal?: AbortSignal,
): Promise<BetPage> {
  const params = new URLSearchParams();
  if (filter.limit) params.set("limit", String(filter.limit));
  if (filter.status) params.set("status", filter.status);
  if (filter.tableId) params.set("tableId", filter.tableId);
  if (filter.cursor) params.set("cursor", filter.cursor);
  const query = params.size ? `?${params}` : "";
  const page = await read(
    await request(`/me/bets${query}`, signal ? { signal } : undefined),
    betPageSchema,
  );
  if (filter.status && page.items.some((bet) => bet.status !== filter.status))
    throw new BackendError("The backend returned an unexpected response.");
  return page;
}

export async function readProposedBet(
  response: Response,
  tableId: string,
  userId: string,
  modeId: Bet["modeId"],
): Promise<Bet> {
  const bet = await read(response, betSchema);
  if (bet.tableId !== tableId || bet.proposerId !== userId || bet.modeId !== modeId)
    throw new BackendError("The backend returned an unexpected response.");
  return bet;
}

/** One bet, for tickets opened outside the table's live set (chat cards, profiles). */
export async function getBet(tableId: string, betId: string, signal?: AbortSignal): Promise<Bet> {
  if (!uuid.safeParse(tableId).success || !uuid.safeParse(betId).success)
    throw new BackendError("Bet not found.", 404);
  const bet = await read(
    await request(`/bets/${encodeURIComponent(betId)}`, signal ? { signal } : undefined),
    betSchema,
  );
  if (bet.id !== betId || bet.tableId !== tableId)
    throw new BackendError("The backend returned an unexpected response.");
  return bet;
}

const memberPath = (tableId: string, userId: string) => {
  if (!uuid.safeParse(tableId).success || !uuid.safeParse(userId).success)
    throw new BackendError("Member not found.", 404);
  return `/tables/${encodeURIComponent(tableId)}/members/${encodeURIComponent(userId)}`;
};

/** Bets at a table that involve a member, newest first, a page at a time. */
export async function listMemberBets(
  tableId: string,
  userId: string,
  cursor: string | null,
  signal?: AbortSignal,
): Promise<BetPage> {
  const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : "";
  const page = await read(
    await request(`${memberPath(tableId, userId)}/bets${query}`, signal ? { signal } : undefined),
    betPageSchema,
  );
  if (page.items.some((bet) => bet.tableId !== tableId))
    throw new BackendError("The backend returned an unexpected response.");
  return page;
}

const recordSchema = z.object({
  played: z.number().int().nonnegative(),
  won: z.number().int().nonnegative(),
  lost: z.number().int().nonnegative(),
  netCents: z.number().int().safe(),
});
type MemberRecord = z.infer<typeof recordSchema>;

/** A member's record at a table, computed by the server from resolved, non-4fun bets they picked in. */
export async function getMemberRecord(
  tableId: string,
  userId: string,
  signal?: AbortSignal,
): Promise<MemberRecord> {
  return read(
    await request(`${memberPath(tableId, userId)}/record`, signal ? { signal } : undefined),
    recordSchema,
  );
}

const modesSchema = z.array(z.object({ id: betSchema.shape.modeId, available: z.boolean() }));

/** Which modes the server accepts proposals for now. */
export async function listModes(signal?: AbortSignal): Promise<Map<Bet["modeId"], boolean>> {
  const modes = await read(await request("/modes", signal ? { signal } : undefined), modesSchema);
  return new Map(modes.map((mode) => [mode.id, mode.available]));
}

const liveTeamSchema = z.object({ abbreviation: z.string(), score: z.number().int().nullable() });
const liveStatsSchema = z.object({
  gameId: z.string(),
  league: z.enum(["NBA", "NFL"]),
  status: z.string(),
  period: z.number().int().nullable(),
  clock: z.string().nullable(),
  away: liveTeamSchema,
  home: liveTeamSchema,
  rows: z.array(
    z.object({
      label: z.string(),
      detail: z.string().nullable(),
      /** When the bet was proposed; null for bets made before these snapshots. */
      proposed: z.number().int().nullable(),
      /** When picks locked; null before lock. */
      locked: z.number().int().nullable(),
      current: z.number().int().nullable(),
      /** The line or target, when the mode has one. */
      target: z.number().nullable(),
      targetLabel: z.string().nullable(),
    }),
  ),
});

/** What a sports bet follows (a score, a margin, a player's stat), when proposed, at lock and now. */
export type BetLiveStats = z.infer<typeof liveStatsSchema>;

export async function getBetLiveStats(betId: string, signal?: AbortSignal): Promise<BetLiveStats> {
  if (!uuid.safeParse(betId).success) throw new BackendError("Bet not found.", 404);
  return read(
    await request(`/bets/${encodeURIComponent(betId)}/live`, signal ? { signal } : undefined),
    liveStatsSchema,
  );
}

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
    remainderRule: z.enum(["host", "proposer", "earliest", "random"]).optional(),
    remainderHostId: uuid.optional(),
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
      bet.remainderRule !== undefined ||
      bet.remainderHostId !== undefined ||
      bet.calledBy !== undefined ||
      bet.winningChoice !== undefined ||
      bet.washReason !== undefined ||
      bet.deltasCents !== undefined;
    if (
      invalidParticipation ||
      (bet.status === "ACTIVE" && privateFieldsPresent) ||
      (bet.status !== "ACTIVE" &&
        (bet.picks === undefined ||
          bet.noGuessUserIds === undefined ||
          bet.remainderRule === undefined))
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
  filter: { status?: Bet["status"]; tableId?: string; cursor?: string },
  signal?: AbortSignal,
): Promise<BetPage> {
  const params = new URLSearchParams();
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

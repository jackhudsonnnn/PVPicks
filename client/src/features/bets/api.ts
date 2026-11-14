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
  counts: z.object({
    ACTIVE: z.number().int().safe().nonnegative(),
    PENDING: z.number().int().safe().nonnegative(),
    SETTLED: z.number().int().safe().nonnegative(),
    WASHED: z.number().int().safe().nonnegative(),
  }),
});
export type BetPage = z.infer<typeof betPageSchema>;

/** Your bets across your current tables (or one), newest first, with per-status counts for the Tickets tabs. */
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

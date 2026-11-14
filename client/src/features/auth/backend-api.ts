import { z } from "zod";

export const userSchema = z.object({
  id: z.guid(),
  username: z.string(),
  hue: z.number().int().min(1).max(5),
});

const currentUserSchema = userSchema.extend({ role: z.enum(["PLAYER", "ADMIN"]) });

const profileSchema = z.union([
  userSchema,
  z.object({
    id: z.guid(),
    displayName: z.literal("Deleted user"),
    deleted: z.literal(true),
  }),
]);

export const tableSchema = z.object({
  id: z.guid(),
  name: z.string(),
  emoji: z.string(),
  memberIds: z.array(z.guid()),
  hostIds: z.array(z.guid()).min(1),
  remainderRule: z.enum(["host", "proposer", "earliest", "random"]),
  hostsOnlyBets: z.boolean(),
  u2pickEnabled: z.boolean(),
  quickPicksEnabled: z.boolean(),
  quickPickSharing: z.boolean(),
  quickPickCode: z.string().optional(),
  subscribedTo: z.array(z.guid()),
  disabledPermissions: z.record(z.string(), z.array(z.enum(["bets", "text", "react", "gifs"]))),
});

const uuid = z.guid();
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

export type BackendUser = z.infer<typeof userSchema>;
/** The signed-in account; admins get the admin view because they cannot use player APIs. */
export type CurrentUser = z.infer<typeof currentUserSchema>;
export type BackendProfile = z.infer<typeof profileSchema>;
export type BackendTable = z.infer<typeof tableSchema>;
export type BackendBet = z.infer<typeof betSchema>;

export class BackendError extends Error {
  constructor(
    message: string,
    readonly status?: number,
    readonly code?: string,
    /** The server's explanation of a 409, for example "A table must retain an active host." */
    readonly detail?: string,
  ) {
    super(message);
    this.name = "BackendError";
  }
}

export async function request(path: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(`/api${path}`, {
    ...init,
    credentials: "same-origin",
    cache: "no-store",
  });
  if (!response.ok) {
    let code: string | undefined;
    let detail: string | undefined;
    if (response.status === 409) {
      try {
        const body: unknown = await response.json();
        const parsed = z
          .object({ code: z.string(), message: z.string().optional() })
          .safeParse(body);
        if (parsed.success) {
          code = parsed.data.code;
          detail = parsed.data.message;
        }
      } catch (error) {
        if (!(error instanceof SyntaxError)) throw error;
      }
    }
    throw new BackendError("Backend request failed.", response.status, code, detail);
  }
  return response;
}

export async function read<T>(response: Response, schema: z.ZodType<T>): Promise<T> {
  let value: unknown;
  try {
    value = await response.json();
  } catch (error) {
    if (error instanceof SyntaxError) throw new BackendError("The backend returned invalid JSON.");
    throw error;
  }
  const result = schema.safeParse(value);
  if (!result.success) throw new BackendError("The backend returned an unexpected response.");
  return result.data;
}

export async function csrf(): Promise<string> {
  const response = await request("/auth/csrf");
  return (await read(response, z.object({ token: z.string().min(1) }))).token;
}

export async function mutationRequest(
  path: string,
  method: "POST" | "PUT" | "PATCH" | "DELETE",
  key: string,
  body?: object,
): Promise<Response> {
  if (!key || key.length > 255) throw new BackendError("Invalid retry key.");
  const token = await csrf();
  return request(path, {
    method,
    headers: {
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      "X-CSRF-TOKEN": token,
      "Idempotency-Key": key,
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}

export async function currentUser(signal?: AbortSignal): Promise<CurrentUser | null> {
  try {
    return await read(await request("/me", signal ? { signal } : undefined), currentUserSchema);
  } catch (error) {
    if (error instanceof BackendError && error.status === 401) return null;
    throw error;
  }
}

export async function signIn(username: string, password: string): Promise<CurrentUser> {
  const token = await csrf();
  await request("/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": token },
    body: JSON.stringify({ username, password }),
  });
  const user = await currentUser();
  if (!user)
    throw new BackendError("The session could not be verified. Please try signing in again.");
  return user;
}

export async function signOut(): Promise<void> {
  const token = await csrf();
  await request("/auth/logout", { method: "POST", headers: { "X-CSRF-TOKEN": token } });
}

export async function listTables(signal?: AbortSignal): Promise<BackendTable[]> {
  return read(await request("/tables", signal ? { signal } : undefined), z.array(tableSchema));
}

export async function getTable(id: string, signal?: AbortSignal): Promise<BackendTable> {
  if (!uuid.safeParse(id).success) throw new BackendError("Table not found.", 404);
  return read(
    await request(`/tables/${encodeURIComponent(id)}`, signal ? { signal } : undefined),
    tableSchema,
  );
}

export async function listTableBets(tableId: string, signal?: AbortSignal): Promise<BackendBet[]> {
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
  filter: { status?: BackendBet["status"]; tableId?: string; cursor?: string },
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

export async function getBet(
  tableId: string,
  betId: string,
  signal?: AbortSignal,
): Promise<BackendBet> {
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

export async function readProposedBet(
  response: Response,
  tableId: string,
  userId: string,
): Promise<BackendBet> {
  const bet = await read(response, betSchema);
  if (bet.tableId !== tableId || bet.proposerId !== userId || bet.modeId !== "u2pick")
    throw new BackendError("The backend returned an unexpected response.");
  return bet;
}

export async function getProfile(id: string, signal?: AbortSignal): Promise<BackendProfile> {
  if (!uuid.safeParse(id).success) throw new BackendError("Profile not found.", 404);
  const profile = await read(
    await request(`/users/${encodeURIComponent(id)}`, signal ? { signal } : undefined),
    profileSchema,
  );
  if (profile.id !== id) throw new BackendError("The backend returned an unexpected response.");
  return profile;
}

export function backendErrorMessage(error: unknown): string {
  if (error instanceof BackendError) {
    if (error.status === 403)
      return "This account cannot access that action, or the request was rejected.";
    if (error.status === 404) return "This item is unavailable or you no longer have access.";
    if (error.status === 409 && error.detail) return error.detail;
    if (error.status !== undefined) return `The backend returned an error (${error.status}).`;
    return error.message;
  }
  if (error instanceof TypeError)
    return "Could not reach the backend. Check that the dev server is running.";
  return "An unexpected error occurred. Please try again.";
}

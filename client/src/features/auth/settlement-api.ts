import { parse, parseNumberAndBigInt } from "lossless-json";
import { z } from "zod";
import { BackendError, mutationRequest, request } from "./backend-api";

const uuid = z.guid();
const integer = z.union([z.number().int().safe(), z.bigint()]).transform((value) => BigInt(value));
export const exactCents = integer.refine((value) => value >= 1n && value <= 9223372036854775807n);
const netCents = integer.refine(
  (value) => value >= -9223372036854775808n && value <= 9223372036854775807n,
);
const timestamp = z.string().datetime({ offset: true });

const lineSchema = z.object({
  id: uuid,
  settlementId: uuid,
  tableId: uuid,
  payerId: uuid,
  payeeId: uuid,
  amountCents: exactCents,
  status: z.enum(["unpaid", "sent", "paid", "disputed", "forgive_asked", "forgiven"]),
  revision: z.number().int().min(0).max(2147483647),
  forgiveRequestedAt: timestamp.nullable().optional(),
  forgiveDeclinedAt: timestamp.nullable().optional(),
  createdAt: timestamp,
  updatedAt: timestamp,
});
const groupSchema = z.object({
  otherUserId: uuid,
  netCents,
  revision: z.string().min(1),
  lines: z.array(lineSchema),
  pendingReviewRevision: uuid.nullable(),
});
const reviewSchema = z.object({ otherUserId: uuid, pendingReviewRevision: uuid });

export type SettlementLine = z.infer<typeof lineSchema>;
export type SettlementGroup = z.infer<typeof groupSchema>;
export type PendingReview = z.infer<typeof reviewSchema>;
export type LineAction =
  "sent" | "confirm" | "dispute" | "forgive" | "ask-forgive" | "decline-forgive";
export type GroupAction = "sent" | "paid" | "unpaid" | "forgiven" | "ask" | "decline";

/** Parses a JSON response without losing precision in 64-bit integers, then validates it. */
export async function readExact<T>(response: Response, schema: z.ZodType<T, unknown>): Promise<T> {
  const text = await response.text();
  let value: unknown;
  try {
    value = parse(text, null, {
      parseNumber: (text) => {
        const number = parseNumberAndBigInt(text);
        return typeof number === "bigint" &&
          number >= BigInt(Number.MIN_SAFE_INTEGER) &&
          number <= BigInt(Number.MAX_SAFE_INTEGER)
          ? Number(number)
          : number;
      },
    });
  } catch (error) {
    if (error instanceof SyntaxError) throw new BackendError("The backend returned invalid JSON.");
    throw error;
  }
  const result = schema.safeParse(value);
  if (!result.success) throw new BackendError("The backend returned an unexpected response.");
  return result.data;
}

function validId(id: string) {
  if (!uuid.safeParse(id).success) throw new BackendError("Invalid identity.");
}

function directLine(line: SettlementLine, actor: string, other: string): boolean {
  return (
    (line.payerId === actor && line.payeeId === other) ||
    (line.payeeId === actor && line.payerId === other)
  );
}

export async function listPendingReviews(
  actor: string,
  signal?: AbortSignal,
): Promise<PendingReview[]> {
  validId(actor);
  const reviews = await readExact(
    await request("/people/settlement-reviews", signal ? { signal } : undefined),
    z.array(reviewSchema),
  );
  if (
    reviews.some((review) => review.otherUserId === actor) ||
    new Set(reviews.map((review) => review.otherUserId)).size !== reviews.length
  )
    throw new BackendError("The backend returned an unexpected response.");
  return reviews;
}

export async function listSettlementCounterparties(
  actor: string,
  signal?: AbortSignal,
): Promise<string[]> {
  validId(actor);
  const ids = await readExact(
    await request("/people/settlement-counterparties", signal ? { signal } : undefined),
    z.array(uuid),
  );
  if (ids.includes(actor) || new Set(ids).size !== ids.length)
    throw new BackendError("The backend returned an unexpected response.");
  return ids;
}

export async function listSettlementGroups(
  actor: string,
  signal?: AbortSignal,
): Promise<SettlementGroup[]> {
  validId(actor);
  const groups = await readExact(
    await request("/people/settle-groups?includeHistory=true", signal ? { signal } : undefined),
    z.array(groupSchema),
  );
  if (
    groups.some(
      (group) =>
        group.otherUserId === actor ||
        group.lines.some((line) => !directLine(line, actor, group.otherUserId)),
    )
  )
    throw new BackendError("The backend returned an unexpected response.");
  return groups;
}

export async function receiptHistory(
  actor: string,
  other: string,
  signal?: AbortSignal,
): Promise<SettlementLine[]> {
  validId(actor);
  validId(other);
  const lines = await readExact(
    await request(
      `/users/${encodeURIComponent(other)}/settlements`,
      signal ? { signal } : undefined,
    ),
    z.array(lineSchema),
  );
  if (actor === other || lines.some((line) => !directLine(line, actor, other)))
    throw new BackendError("The backend returned an unexpected response.");
  return lines;
}

async function post(path: string, body: object, key: string): Promise<Response> {
  return mutationRequest(path, "POST", key, body);
}

export async function acknowledgeReview(
  other: string,
  revision: string,
  key: string,
): Promise<void> {
  validId(other);
  validId(revision);
  await post(
    `/users/${encodeURIComponent(other)}/settlement-review/acknowledge`,
    { revision },
    key,
  );
}

export async function transitionLine(
  actor: string,
  other: string,
  lineId: string,
  action: LineAction,
  revision: number,
  key: string,
): Promise<void> {
  validId(actor);
  validId(other);
  validId(lineId);
  if (!z.number().int().min(0).max(2147483647).safeParse(revision).success)
    throw new BackendError("Invalid receipt revision.");
  const line = await readExact(
    await post(`/settlement-lines/${encodeURIComponent(lineId)}/${action}`, { revision }, key),
    lineSchema,
  );
  if (line.id !== lineId || !directLine(line, actor, other))
    throw new BackendError("The backend returned an unexpected response.");
}

export async function transitionGroup(
  other: string,
  action: GroupAction,
  revision: string,
  key: string,
): Promise<void> {
  validId(other);
  if (!revision) throw new BackendError("Invalid group revision.");
  await post(
    `/people/${encodeURIComponent(other)}/settle`,
    { step: action, expectedRevision: revision },
    key,
  );
}

/** Formats signed cents as points, exactly (two decimals, grouped). */
export function formatPoints(cents: bigint) {
  const abs = cents < 0n ? -cents : cents;
  return `${cents < 0n ? "-" : ""}${(abs / 100n).toLocaleString()}.${(abs % 100n).toString().padStart(2, "0")}`;
}

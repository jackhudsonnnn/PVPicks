import { formatCents } from "@/lib/money";
import { parse, parseNumberAndBigInt } from "lossless-json";
import { z } from "zod";
import { BackendError, mutationRequest, request, idSchema } from "@/lib/api-client";

const uuid = idSchema;
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
  /** Unpaid until a settle closes it. */
  status: z.enum(["unpaid", "paid"]),
  revision: z.number().int().min(0).max(2147483647),
  /** The settle that closed it; null while unpaid. */
  settleId: uuid.nullable(),
  createdAt: timestamp,
  updatedAt: timestamp,
});

const settleSchema = z.object({
  id: uuid,
  /** sent: "Settle" by the person down (the other can reject until `rejectBy`); received: "Mark as settled", final. */
  kind: z.enum(["sent", "received"]),
  senderId: uuid,
  otherId: uuid,
  /** Who pays the selection's net; null when it netted to zero. */
  payerId: uuid.nullable(),
  payeeId: uuid.nullable(),
  amountCents: integer.refine((value) => value >= 0n),
  createdAt: timestamp,
  rejectBy: timestamp.nullable(),
  rejectedAt: timestamp.nullable(),
  /** Whether you can reject it now. */
  canReject: z.boolean(),
  lines: z.array(
    z.object({
      id: uuid,
      tableId: uuid,
      payerId: uuid,
      payeeId: uuid,
      amountCents: exactCents,
      createdAt: timestamp,
    }),
  ),
});

const groupSchema = z.object({
  otherUserId: uuid,
  /** Of the open receipts: positive when you're owed. */
  netCents,
  /** The open receipts. */
  lines: z.array(lineSchema),
  pendingReviewRevision: uuid.nullable(),
  /** Sent settles between you still inside their reject window, newest first. */
  pendingSettles: z.array(settleSchema),
});

const settlePageSchema = z.object({
  items: z.array(settleSchema),
  nextCursor: z.string().nullable(),
});

export type SettlementLine = z.infer<typeof lineSchema>;
export type SettlementGroup = z.infer<typeof groupSchema>;
export type Settle = z.infer<typeof settleSchema>;
export type SettleKind = Settle["kind"];
export type SettlePage = z.infer<typeof settlePageSchema>;

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

function directSettle(settle: Settle, actor: string, other: string): boolean {
  return (
    (settle.senderId === actor && settle.otherId === other) ||
    (settle.otherId === actor && settle.senderId === other)
  );
}

export async function listSettlementGroups(
  actor: string,
  signal?: AbortSignal,
): Promise<SettlementGroup[]> {
  validId(actor);
  const groups = await readExact(
    await request("/people/settle-groups", signal ? { signal } : undefined),
    z.array(groupSchema),
  );
  if (
    groups.some(
      (group) =>
        group.otherUserId === actor ||
        group.lines.some((line) => !directLine(line, actor, group.otherUserId)) ||
        group.pendingSettles.some((settle) => !directSettle(settle, actor, group.otherUserId)),
    )
  )
    throw new BackendError("The backend returned an unexpected response.");
  return groups;
}

/** Receipts with one person: every open one, then closed ones a page (20) at a time. */
export interface ReceiptPage {
  open: SettlementLine[];
  closed: SettlementLine[];
  nextCursor: string | null;
}

/**
 * Your receipts with one person, optionally at one table: all open ones (newest first) and the first 20 closed ones;
 * pass `cursor` for the next closed ones (their page has no open ones).
 */
export async function receiptHistory(
  actor: string,
  other: string,
  options: { tableId?: string; cursor?: string | null } = {},
  signal?: AbortSignal,
): Promise<ReceiptPage> {
  validId(actor);
  validId(other);
  if (options.tableId !== undefined) validId(options.tableId);
  const query = new URLSearchParams();
  if (options.tableId !== undefined) query.set("tableId", options.tableId);
  if (options.cursor) query.set("cursor", options.cursor);
  const suffix = query.toString() ? `?${query.toString()}` : "";
  const page = await readExact(
    await request(
      `/users/${encodeURIComponent(other)}/settlements${suffix}`,
      signal ? { signal } : undefined,
    ),
    z.object({
      open: z.array(lineSchema),
      closed: z.array(lineSchema).max(20),
      nextCursor: z.string().min(1).nullable(),
    }),
  );
  const lines = [...page.open, ...page.closed];
  if (
    actor === other ||
    lines.some((line) => !directLine(line, actor, other)) ||
    page.open.some((line) => line.status !== "unpaid") ||
    page.closed.some((line) => line.status === "unpaid") ||
    (options.tableId !== undefined && lines.some((line) => line.tableId !== options.tableId))
  )
    throw new BackendError("The backend returned an unexpected response.");
  return page;
}

/** Every settle with one person, newest first, a page at a time. */
export async function listSettles(
  actor: string,
  other: string,
  cursor: string | null,
  signal?: AbortSignal,
): Promise<SettlePage> {
  validId(actor);
  validId(other);
  const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : "";
  const page = await readExact(
    await request(
      `/people/${encodeURIComponent(other)}/settles${query}`,
      signal ? { signal } : undefined,
    ),
    settlePageSchema,
  );
  if (page.items.some((settle) => !directSettle(settle, actor, other)))
    throw new BackendError("The backend returned an unexpected response.");
  return page;
}

async function post(path: string, body: object | undefined, key: string): Promise<Response> {
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

/** Settles the chosen open receipts with one person. */
export async function createSettle(
  other: string,
  kind: SettleKind,
  lineIds: string[],
  key: string,
): Promise<Settle> {
  validId(other);
  lineIds.forEach(validId);
  return readExact(
    await post(`/people/${encodeURIComponent(other)}/settles`, { kind, lineIds }, key),
    settleSchema,
  );
}

/** The receiver rejects a sent settle within its window; its receipts reopen. */
export async function rejectSettle(settleId: string, key: string): Promise<void> {
  validId(settleId);
  await post(`/settles/${encodeURIComponent(settleId)}/reject`, undefined, key);
}

/** Formats signed cents as points, exactly (two decimals, grouped). */
export const formatPoints = (cents: bigint) => formatCents(cents);

// TODO(backend-logic): receipt status transitions + revision guard belong in the settlement service.
import type { Message } from "@/features/table/types";
import { paymentsOf, type Payment } from "@/features/table/settlements";

/** Immutable settlement identities: repeated settlements at a table are separate receipts. */
export function settleReceipts(messages: Message[], me: string, other: string): Payment[] {
  const rows = messages.flatMap((m) => m.kind === "settlement" ? paymentsOf(m) : [])
    .filter((p) => (p.from === me && p.to === other) || (p.from === other && p.to === me));
  // Open receipts first, newest first within each section; source order supports legacy clock labels.
  return rows.map((p, index) => ({ p, index })).sort((a, b) =>
    Number(isClosed(a.p)) - Number(isClosed(b.p)) || b.index - a.index,
  ).map(({ p }) => p);
}

export const isClosed = (p: Payment) => p.status === "paid" || p.status === "forgiven";

/** A receipt remains real debt while sent; only confirmation or forgiveness clears it. */
export function receiptNet(rows: Payment[], me: string) {
  return rows.reduce((cents, p) => cents + (isClosed(p) ? 0 : Math.round(p.amount * 100) * (p.to === me ? 1 : -1)), 0) / 100;
}
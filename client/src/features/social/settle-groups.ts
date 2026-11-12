import { toCents } from "@/lib/money";
import { outstandingFor, paymentsOf, type Payment } from "@/features/table/settlements";
import type { Message } from "@/features/table/types";

export interface SettleGroup {
  otherUserId: string;
  /** Positive means the viewer receives money. */
  net: number;
  payments: Payment[];
  tables: { tableId: string; net: number; first: Payment }[];
  payment: Payment;
  /** Ledger identity and values, so stale dialogs cannot settle a changed amount. */
  revision: string;
}

/** Net all open lines by person in integer cents, keeping a read-only table breakdown. */
export function settleGroups(messages: Message[], me: string, includeHistory = false): SettleGroup[] {
  const people = new Map<string, Payment[]>();
  const source = includeHistory ? messages.flatMap((m) => m.kind === "settlement" ? paymentsOf(m) : []).filter((p) => p.from === me || p.to === me) : outstandingFor(messages, me);
  for (const p of source) {
    const other = p.from === me ? p.to : p.from;
    const rows = people.get(other) ?? [];
    rows.push(p);
    people.set(other, rows);
  }
  return [...people].map(([otherUserId, history]) => {
    const payments = history.filter((p) => p.status !== "paid" && p.status !== "forgiven");
    const byTable = new Map<string, { tableId: string; cents: number; first: Payment }>();
    let cents = 0;
    for (const p of payments) {
      const amount = Math.round(p.amount * 100) * (p.to === me ? 1 : -1);
      cents += amount;
      const row = byTable.get(p.tableId) ?? { tableId: p.tableId, cents: 0, first: p };
      row.cents += amount;
      byTable.set(p.tableId, row);
    }
    const first = payments[0] ?? history[0];
    if (!first) throw new Error("Empty settle-up group");
    const netPayer = cents < 0 ? me : otherUserId;
    // A sent offset in the opposite direction is not a claim that the new net was sent.
    const directional = payments.filter((p) => p.from === netPayer);
    const asks = directional.flatMap((p) => p.ask ? [p.ask] : []);
    const ask = asks.find((a) => a.status === "open") ?? asks.sort((a, b) => b.at - a.at)[0];
    const net = toCents(cents / 100);
    return {
      otherUserId, net, payments,
      revision: JSON.stringify(payments.map((p) => [p.messageId, p.lineUserId, p.from, p.to, p.amount]).sort()),
      tables: [...byTable.values()].map((t) => ({ tableId: t.tableId, net: t.cents / 100, first: t.first })),
      payment: {
        ...first, from: net < 0 ? me : otherUserId, to: net < 0 ? otherUserId : me,
        amount: Math.abs(net), status: directional.some((p) => p.status === "sent") ? "sent" : "unpaid", ask,
      },
    };
  });
}
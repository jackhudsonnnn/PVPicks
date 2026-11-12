import { settleGroups } from "./settle-groups";
import type { Message } from "@/features/table/types";
import { setForgiveAsk, setPaymentStatus } from "@/features/table/settlements";

export interface SettleChange {
  fromId: string;
  toId: string;
  /** Signed balances from fromId's perspective. */
  before: number;
  after: number;
  revision: string;
}

export const settlePairKey = (a: string, b: string) => JSON.stringify([a, b].sort());

/** Reviewing one notice never acknowledges a later balance-change notice. */
export const needsSettleReview = (change: SettleChange | undefined, reviewed: SettleChange | undefined) =>
  change !== undefined && change !== reviewed;

/** Cancel claims, not transfers: only a changed open net invalidates pending intent. */
export function reconcileSettleChanges(before: Message[], after: Message[]) {
  let messages = after;
  const changes: Record<string, SettleChange> = {};
  const people = new Set(before.flatMap((m) => m.kind === "settlement" ? [m.hostId] : []));
  const seen = new Set<string>();
  for (const me of people) {
    const next = new Map(settleGroups(after, me).map((g) => [g.otherUserId, g]));
    for (const old of settleGroups(before, me)) {
      const key = settlePairKey(me, old.otherUserId);
      if (seen.has(key)) continue;
      seen.add(key);
      if (!old.payments.some((p) => p.status === "sent" || p.ask?.status === "open")) continue;
      const group = next.get(old.otherUserId);
      // A completed full payment/forgiveness closes the group; it isn't a cancellation.
      if (!group) continue;
      const net = group?.net ?? 0;
      if (net === old.net) continue;
      const rows = group?.payments ?? [];
      const ids = new Set(rows.map((p) => JSON.stringify([p.messageId, p.lineUserId])));
      messages = messages.map((m) => {
        if (m.kind !== "settlement") return m;
        let updated = m;
        for (const l of m.lines) {
          if (!ids.has(JSON.stringify([m.id, l.userId]))) continue;
          if (updated.payments?.[l.userId] === "sent") updated = setPaymentStatus(updated, l.userId, "unpaid");
          if (updated.forgiveAsks?.[l.userId]?.status === "open") updated = setForgiveAsk(updated, l.userId, undefined);
        }
        return updated;
      });
      changes[key] = { fromId: me, toId: old.otherUserId, before: old.net, after: net, revision: group?.revision ?? "empty" };
    }
  }
  return { messages, changes };
}
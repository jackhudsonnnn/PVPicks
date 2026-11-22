import type { SettleKind, SettlementGroup, SettlementLine } from "./api";

/**
 * A settle-up card's color (domain rules, "Settle-ups"):
 * - `attention` (yellow): a balance change to review, or a settle to you that you can still reject.
 * - `owe` (red, you're down) / `owed` (green, you're up): what's open, when nothing needs you right now.
 * - `even` (neutral): nothing open nets either way.
 */
export type CardTone = "attention" | "owe" | "owed" | "even";

const TONE_ORDER: Record<CardTone, number> = { attention: 0, owe: 1, owed: 2, even: 3 };

export function cardTone(group: SettlementGroup): CardTone {
  if (group.pendingReviewRevision || group.pendingSettles.some((settle) => settle.canReject))
    return "attention";
  return group.netCents < 0n ? "owe" : group.netCents > 0n ? "owed" : "even";
}

/** Cards by color: yellow, red, green, neutral; the server's order (newest activity first) within each. */
export function sortGroups(groups: readonly SettlementGroup[]): SettlementGroup[] {
  return groups
    .map((group, index) => ({ group, index }))
    .sort(
      (a, b) => TONE_ORDER[cardTone(a.group)] - TONE_ORDER[cardTone(b.group)] || a.index - b.index,
    )
    .map(({ group }) => group);
}

/** How many tables and open receipts a card covers. */
export function groupSummary(group: SettlementGroup) {
  return {
    tables: new Set(group.lines.map((line) => line.tableId)).size,
    open: group.lines.length,
  };
}

/** A selection's net from your side: positive when they owe you. */
export function selectionNet(lines: readonly SettlementLine[], viewerId: string): bigint {
  return lines.reduce(
    (net, line) => (line.payeeId === viewerId ? net + line.amountCents : net - line.amountCents),
    0n,
  );
}

/**
 * What you can do with a selection: settle it (`sent`) when you're down on its net, or mark it as settled
 * (`received`) when you're up or it's even. The server checks the same rules.
 */
export function selectionActions(lines: readonly SettlementLine[], viewerId: string): SettleKind[] {
  if (lines.length === 0) return [];
  return [selectionNet(lines, viewerId) < 0n ? "sent" : "received"];
}

/** Hours left to reject, rounded up, for "Reject within 31h". */
export function hoursLeft(rejectBy: string, nowMs: number): number {
  return Math.max(0, Math.ceil((Date.parse(rejectBy) - nowMs) / 3_600_000));
}

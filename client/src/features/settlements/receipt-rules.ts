import type { GroupAction, LineAction, SettlementLine } from "./api";

/**
 * How receipt actions read on screen. Which actions a viewer may take comes from the server (`actions` on each
 * receipt and group); this only labels them and marks the ones that close receipts, which ask to confirm first.
 */
export const LINE_ACTIONS: Record<LineAction, { label: string; confirm?: boolean }> = {
  sent: { label: "I sent it" },
  "ask-forgive": { label: "Ask to forgive" },
  confirm: { label: "I received it", confirm: true },
  forgive: { label: "Forgive", confirm: true },
  "decline-forgive": { label: "Decline forgiveness" },
  dispute: { label: "Didn't receive it" },
};

export const GROUP_ACTIONS: Record<GroupAction, { label: string; confirm?: boolean }> = {
  sent: { label: "Mark group sent" },
  ask: { label: "Ask to forgive group" },
  paid: { label: "Confirm group received", confirm: true },
  forgiven: { label: "Forgive group", confirm: true },
  unpaid: { label: "Group not received" },
  decline: { label: "Decline group forgiveness" },
};

/** Paid and forgiven receipts are done; the rest still need someone to act. */
export function isClosed(line: SettlementLine): boolean {
  return line.status === "paid" || line.status === "forgiven";
}

/** What a person's card summarizes: how many tables and open receipts, and whether a payment awaits confirmation. */
export function groupSummary(lines: SettlementLine[]) {
  const open = lines.filter((line) => !isClosed(line));
  return {
    tables: new Set(open.map((line) => line.tableId)).size,
    open: open.length,
    awaitingReceipt: open.some((line) => line.status === "sent"),
  };
}

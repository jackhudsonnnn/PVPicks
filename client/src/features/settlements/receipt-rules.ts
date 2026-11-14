import type { GroupAction, LineAction, SettlementGroup, SettlementLine } from "./api";

/** Forgiveness can't be asked again for a day after it's declined. */
const DECLINE_COOLDOWN_MS = 86_400_000;

export interface ActionOption<A> {
  action: A;
  label: string;
  /** Closes receipts, so it asks to confirm first. */
  confirm?: boolean;
}

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

/**
 * Actions on every open receipt with one person at once, from the viewer's side of the net. The server applies a
 * group action atomically and rechecks the revision.
 */
export function groupOptions(
  group: SettlementGroup,
  viewerId: string,
  now = Date.now(),
): ActionOption<GroupAction>[] {
  const open = group.lines.filter((line) => !isClosed(line));
  const payer = group.netCents < 0n ? viewerId : group.otherUserId;
  const directional = open.filter((line) => line.payerId === payer);
  const sent = directional.some((line) => line.status === "sent");
  const asked = directional.some(
    (line) => line.status === "forgive_asked" || line.forgiveRequestedAt,
  );
  const declined = directional.some(
    (line) =>
      line.forgiveDeclinedAt &&
      now - new Date(line.forgiveDeclinedAt).getTime() < DECLINE_COOLDOWN_MS,
  );
  const options: ActionOption<GroupAction>[] = [];
  if (open.length && group.netCents === 0n)
    options.push({ action: "paid", label: "Confirm zero net", confirm: true });
  else if (open.length && payer === viewerId) {
    if (!sent) options.push({ action: "sent", label: "Mark group sent" });
    if (!sent && !asked && !declined)
      options.push({ action: "ask", label: "Ask to forgive group" });
  } else if (open.length) {
    options.push({ action: "paid", label: "Confirm group received", confirm: true });
    if (!sent) options.push({ action: "forgiven", label: "Forgive group", confirm: true });
    if (sent) options.push({ action: "unpaid", label: "Group not received" });
    if (!sent && asked) options.push({ action: "decline", label: "Decline group forgiveness" });
  }
  return options;
}

/** Actions on one receipt for the viewer: the payer reports sending or asks to forgive; the payee confirms or forgives. */
export function lineOptions(
  line: SettlementLine,
  viewerId: string,
  now = Date.now(),
): ActionOption<LineAction>[] {
  const unpaid = ["unpaid", "disputed", "forgive_asked"].includes(line.status);
  const asked = line.status === "forgive_asked" || !!line.forgiveRequestedAt;
  const cooldown =
    !!line.forgiveDeclinedAt &&
    now - new Date(line.forgiveDeclinedAt).getTime() < DECLINE_COOLDOWN_MS;
  const options: ActionOption<LineAction>[] = [];
  if (line.payerId === viewerId && unpaid) {
    options.push({ action: "sent", label: "I sent it" });
    if (!asked && !cooldown) options.push({ action: "ask-forgive", label: "Ask to forgive" });
  } else if (line.payeeId === viewerId) {
    if (unpaid || line.status === "sent")
      options.push({ action: "confirm", label: "I received it", confirm: true });
    if (unpaid) options.push({ action: "forgive", label: "Forgive", confirm: true });
    if (unpaid && asked) options.push({ action: "decline-forgive", label: "Decline forgiveness" });
    if (line.status === "sent") options.push({ action: "dispute", label: "Didn't receive it" });
  }
  return options;
}

/** Sending and asking for forgiveness wait until a balance-change warning is reviewed. */
export function needsReview(action: GroupAction | LineAction): boolean {
  return action === "sent" || action === "ask" || action === "ask-forgive";
}

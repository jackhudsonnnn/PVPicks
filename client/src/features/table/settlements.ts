import { toCents } from "@/lib/money";
import type { Message, PaymentStatus } from "./types";
import type { Bet } from "@/features/bets/types";

export type SettlementMessage = Extract<Message, { kind: "settlement" }>;

/** A request from the payer to have a settle-up forgiven. */
export interface ForgiveAsk {
  status: "open" | "declined";
  /** Epoch ms of the request (or the decline). */
  at: number;
}

/** After a decline, the payer can ask again once this much time has passed. */
export const ASK_AGAIN_MS = 24 * 60 * 60 * 1000;

export interface Payment {
  messageId: string;
  tableId: string;
  lineUserId: string;
  from: string;
  to: string;
  amount: number;
  status: PaymentStatus;
  ask?: ForgiveAsk | undefined;
  at: string;
}

/** Each settlement line is one payment between the host and a member. */
export function paymentsOf(m: SettlementMessage): Payment[] {
  return m.lines.map((l) => ({
    messageId: m.id,
    tableId: m.tableId,
    lineUserId: l.userId,
    from: l.amount < 0 ? l.userId : m.hostId,
    to: l.amount < 0 ? m.hostId : l.userId,
    amount: Math.abs(l.amount),
    status: m.payments?.[l.userId] ?? "unpaid",
    ask: m.forgiveAsks?.[l.userId],
    at: m.at,
  }));
}

/** Only the payer can mark a payment sent, and only while it's unpaid. */
export const canMarkSent = (p: Payment, userId: string) =>
  p.from === userId && p.status === "unpaid";

/** Only the person being paid can confirm they received it. */
export const canConfirm = (p: Payment, userId: string) =>
  p.to === userId && (p.status === "unpaid" || p.status === "sent");

/** The person being paid can say a "sent" payment never arrived; it goes back to unpaid. */
export const canDispute = (p: Payment, userId: string) => p.to === userId && p.status === "sent";

/**
 * Only the person owed can forgive, with or without a request, and only before the payer
 * marks it sent (so "I paid you" and "please forgive me" never mix). Forgiving clears it all.
 */
export const canForgive = (p: Payment, userId: string) => p.to === userId && p.status === "unpaid";

/** The payer can ask once; after a decline, again only after ASK_AGAIN_MS. */
export function canAskForgive(p: Payment, userId: string, now: number) {
  if (p.from !== userId || p.status !== "unpaid") return false;
  if (!p.ask) return true;
  return p.ask.status === "declined" && now - p.ask.at >= ASK_AGAIN_MS;
}

/** The person owed can decline an open request (forgiving answers it too). */
export const canDecline = (p: Payment, userId: string) =>
  p.to === userId && p.status === "unpaid" && p.ask?.status === "open";

export function setForgiveAsk(
  m: SettlementMessage,
  lineUserId: string,
  ask: ForgiveAsk | undefined,
): SettlementMessage {
  const next = { ...m.forgiveAsks };
  if (ask) next[lineUserId] = ask;
  else delete next[lineUserId];
  return { ...m, forgiveAsks: next };
}

/** Net of your open settle-ups: + you're owed, - you owe. This is the app's points number. */
export function netSettleUps(messages: Message[], userId: string): number {
  let total = 0;
  for (const p of outstandingFor(messages, userId)) total += p.to === userId ? p.amount : -p.amount;
  return toCents(total);
}

/** Payments involving `userId` that aren't confirmed yet, oldest first. */
export function outstandingFor(messages: Message[], userId: string): Payment[] {
  return messages
    .filter((m): m is SettlementMessage => m.kind === "settlement")
    .flatMap(paymentsOf)
    .filter(
      (p) =>
        p.status !== "paid" && p.status !== "forgiven" && (p.from === userId || p.to === userId),
    );
}

export function setPaymentStatus(
  m: SettlementMessage,
  lineUserId: string,
  status: PaymentStatus,
): SettlementMessage {
  return { ...m, payments: { ...m.payments, [lineUserId]: status } };
}

/**
 * Everything `userId` is still owed (+) or owes (-) that nobody has confirmed:
 * resolved results not yet in a settlement, plus settlement payments that
 * aren't marked paid. Confirmed payments drop out.
 */
export function unconfirmedTotal(userId: string, bets: Bet[], messages: Message[]): number {
  let total = 0;
  for (const b of bets) {
    if (b.status === "resolved" && !b.settled) total += b.deltas?.[userId] ?? 0;
  }
  for (const p of outstandingFor(messages, userId)) {
    total += p.to === userId ? p.amount : -p.amount;
  }
  return toCents(total);
}

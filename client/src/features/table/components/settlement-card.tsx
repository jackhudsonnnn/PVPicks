import { useState } from "react";
import { Link } from "@tanstack/react-router";
import { ArrowRight, Check, HandCoins, HeartHandshake, Send, X } from "lucide-react";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { useAppStore, useMe, useNames } from "@/features/store";
import {
  canAskForgive,
  canConfirm,
  canDecline,
  canDispute,
  canForgive,
  canMarkSent,
  type Payment,
} from "../settlements";
import { cn } from "@/lib/utils";
import { formatPts } from "@/features/bets/ledger";
import type { Message } from "@/features/table/types";
import { SettleLines } from "./settle-lines";
import { UserAvatar } from "@/features/social/components/user-avatar";

type Settlement = Extract<Message, { kind: "settlement" }>;

/** System card posted when a host settles points: who sends what to whom. */
export function SettlementCard({ m }: { m: Settlement }) {
  const me = useMe();
  const { name } = useNames();
  const host = name(m.hostId);
  const hostIsMe = m.hostId === me;
  const myLine = m.lines.find((l) => l.userId === me);

  let headline: string;
  if (hostIsMe) {
    headline =
      m.hostNet > 0
        ? `You collect ${formatPts(m.hostNet)} pts overall`
        : m.hostNet < 0
          ? `You pay out ${formatPts(-m.hostNet)} pts overall`
          : "You break even";
  } else if (myLine) {
    headline =
      myLine.amount > 0
        ? `${host} sends you ${formatPts(myLine.amount)} pts`
        : `You send ${host} ${formatPts(-myLine.amount)} pts`;
  } else {
    headline = "You're square, nothing to send";
  }

  return (
    <div className="rounded-xl border border-primary/50 bg-primary/10 p-3">
      <div className="flex items-center gap-2.5">
        <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary text-primary-foreground">
          <HandCoins className="size-4" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="font-mono text-[10px] font-bold uppercase tracking-widest text-primary">
            Points settled · {m.at}
          </p>
          <p className="break-words text-sm font-semibold">{headline}</p>
        </div>
      </div>
      <p className="mt-2 text-xs text-muted-foreground">
        {hostIsMe ? "You" : host} (host) settled the table. Everyone's settle balance is back to 0.
        At settlement, these amounts were owed:
      </p>
      <SettleLines lines={m.lines} hostId={m.hostId} className="mt-2" />
      <Button asChild variant="link" size="sm" className="mt-2 h-auto justify-start p-0 text-xs">
        <Link to="/friends">Settle up in Friends <ArrowRight className="size-3" /></Link>
      </Button>
      <p className="mt-2 border-t border-primary/30 pt-2 font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
        {host} net{" "}
        <span className={cn("font-bold", m.hostNet >= 0 ? "text-win" : "text-loss")}>
          {formatPts(m.hostNet, true)}
        </span>{" "}
        , {hostIsMe ? "your" : "their"} own result at this table
      </p>
    </div>
  );
}

/** Short status for a settle-up, including the forgive flow. */
function paymentStatusLabel(p: Payment) {
  if (p.status === "paid") return "✓ Paid";
  if (p.status === "forgiven") return "✓ Forgiven";
  if (p.status === "sent") return "Awaiting confirm";
  if (p.ask?.status === "open") return "Forgive requested";
  return "Not paid yet";
}

const statusTone = (p: Payment) =>
  p.status === "paid" || p.status === "forgiven"
    ? "text-win"
    : p.status === "sent" || p.ask?.status === "open"
      ? "text-status-pending"
      : "text-muted-foreground";

/**
 * Buttons for your side of a settle-up. Person owed: I got paid / Forgive (or Forgive /
 * Decline when asked; Didn't get it once marked sent). Payer: I sent it / Ask to forgive.
 * Rules live in settlements.ts.
 */
export function PaymentActions({ p, compact, iconOnly = false, otherUserId, expectedRevision, reviewRequired = false }: { p: Payment; compact?: boolean; iconOnly?: boolean; otherUserId?: string; expectedRevision?: string; reviewRequired?: boolean }) {
  const me = useMe();
  const { name } = useNames();
  const { actions } = useAppStore();
  const [confirmForgive, setConfirmForgive] = useState(false);
  const btn = iconOnly ? "size-9 shrink-0 p-0" : compact ? "h-7 px-2 text-xs" : "h-8 flex-1 px-2 text-xs";
  const other = name(p.to === me ? p.from : p.to);
  const id = [p.messageId, p.lineUserId] as const;
  const act = (step: "sent" | "paid" | "unpaid" | "forgiven" | "ask" | "decline") => {
    if (reviewRequired && (step === "sent" || step === "ask")) return;
    if (otherUserId) return actions.settlePerson(otherUserId, step, expectedRevision);
    const commands = { sent: actions.markSent, paid: actions.confirmPaid, unpaid: actions.disputePaid,
      forgiven: actions.forgive, ask: actions.askForgive, decline: actions.declineForgive };
    commands[step](...id);
  };
  const asked = p.ask?.status === "open";
  const buttons: React.ReactNode[] = [];

  if (p.to === me) {
    if (canConfirm(p, me) && !asked)
      buttons.push(
        <Button key="paid" size="sm" className={btn} aria-label="I got it" title="I got it" onClick={() => act("paid")}>
          <Check /> {!iconOnly && "I got paid"}
        </Button>,
      );
    if (canForgive(p, me))
      buttons.push(
        <Button
          key="forgive"
          size="sm"
          variant={asked ? "default" : "secondary"}
          className={btn}
          aria-label="Forgive" title="Forgive"
          onClick={() => setConfirmForgive(true)}
        >
          <HeartHandshake /> {!iconOnly && "Forgive"}
        </Button>,
      );
    if (canDecline(p, me))
      buttons.push(
        <Button
          key="decline"
          size="sm"
          variant="outline"
          className={btn}
          aria-label="Decline forgiveness" title="Decline forgiveness"
          onClick={() => act("decline")}
        >
          <X /> {!iconOnly && "Decline"}
        </Button>,
      );
    if (canDispute(p, me))
      buttons.push(
        <Button
          key="dispute"
          size="sm"
          variant="outline"
          className={btn}
          aria-label="Didn't get it" title="Didn't get it"
          onClick={() => act("unpaid")}
        >
          <X /> {!iconOnly && "Didn't get it"}
        </Button>,
      );
  } else if (p.from === me) {
    if (canMarkSent(p, me))
      buttons.push(
        <Button
          key="sent"
          disabled={reviewRequired}
          title={reviewRequired ? "Review the balance-change warning first" : "I sent it"}
          size="sm"
          variant={iconOnly ? "default" : "secondary"}
          className={btn}
          aria-label="I sent it"
          onClick={() => act("sent")}
        >
          <Send /> {!iconOnly && "I sent it"}
        </Button>,
      );
    if (canAskForgive(p, me, Date.now()))
      buttons.push(
        <Button
          key="ask"
          disabled={reviewRequired}
          title={reviewRequired ? "Review the balance-change warning first" : "Ask to forgive"}
          size="sm"
          variant="outline"
          className={btn}
          aria-label="Ask to forgive"
          onClick={() => act("ask")}
        >
          <HeartHandshake /> {!iconOnly && "Ask to forgive"}
        </Button>,
      );
  }

  const note =
    p.to === me && asked
      ? `${other} asked you to forgive it`
      : p.from === me && asked
        ? `Waiting on ${other} to forgive`
        : p.from === me && p.status === "unpaid" && p.ask?.status === "declined"
          ? `${other} declined. Ask again in a day`
          : null;

  if (!buttons.length && !note) return null;
  return (
    <div className="space-y-1">
      {note && <p className="text-[11px] font-medium text-muted-foreground">{note}</p>}
      {buttons.length > 0 && <div className="flex flex-wrap gap-1.5">{buttons}</div>}
      <AlertDialog open={confirmForgive} onOpenChange={setConfirmForgive}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle className="font-display text-3xl font-black uppercase">
              Forgive ${formatPts(p.amount)}?
            </AlertDialogTitle>
            <AlertDialogDescription>
              {other} won't owe you anything for this settle-up. It shows as Forgiven in your private
              settle-up history. You can't undo this.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep it</AlertDialogCancel>
            <AlertDialogAction onClick={() => act("forgiven")}>Forgive</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

/** One payment with its status and the action (if any) you can take on it. */
export function PaymentRow({
  p,
  showTable,
  asDiv,
}: {
  p: Payment;
  showTable?: string | undefined;
  /** Render a div when a wrapper (e.g. an animated list item) is already the `<li>`. */
  asDiv?: boolean;
}) {
  const me = useMe();
  const { name } = useNames();
  const involved = p.from === me || p.to === me;
  const open = p.status !== "paid" && p.status !== "forgiven";
  const Row = asDiv ? "div" : "li";
  return (
    <Row
      className={cn(
        "space-y-1 rounded-lg px-2 py-1.5 text-xs",
        p.from === me && open
          ? "bg-loss/20 font-semibold"
          : involved
            ? "bg-primary/20 font-semibold"
            : "bg-surface/60",
      )}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="flex min-w-0 flex-1 flex-wrap items-center gap-x-2 gap-y-1">
          <span className="flex min-w-0 items-center gap-2">
            <UserAvatar userId={p.from} size="xs" className="shrink-0 ring-0" />
            <span className="min-w-0 break-words">{name(p.from)}</span>
          </span>
          <ArrowRight className="size-3 shrink-0 text-muted-foreground" />
          <span className="flex min-w-0 items-center gap-2">
            <UserAvatar userId={p.to} size="xs" className="shrink-0 ring-0" />
            <span className="min-w-0 break-words">{name(p.to)}</span>
          </span>
        </div>
        <span className="scoreboard shrink-0 text-sm">
          {p.from === me ? "-" : p.to === me ? "+" : ""}${formatPts(p.amount)}
        </span>
      </div>
      <p className={cn("pl-7 font-mono text-[10px] uppercase tracking-wider", statusTone(p))}>
        {paymentStatusLabel(p)}
        {showTable && ` · ${showTable}`}
      </p>
      <div className="pl-7">
        <PaymentActions p={p} compact />
      </div>
    </Row>
  );
}

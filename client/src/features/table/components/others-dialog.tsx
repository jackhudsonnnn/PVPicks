import { cn } from "@/lib/utils";

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { useMe, useGetUser } from "@/features/store";

import { getMode } from "@/features/bets/modes";
import { type Bet } from "@/features/bets/types";
import { seatStatus } from "../seating";
import { SEAT_TONE } from "./seat-tone";

import { UserAvatar } from "@/features/social/components/user-avatar";

import { ScrollContainer } from "@/components/scroll-container";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";

/**
 * Popup for the "N others" seat: everyone not seated, with their status on the focused bet.
 * Picks stay hidden while the bet is Active (seatStatus shows "In") and show once it's Pending.
 */
export function OthersDialog({
  ids,
  bet,
  open,
  onOpenChange,
  onSelectUser,
}: {
  ids: string[];
  bet: Bet | undefined;
  open: boolean;
  onOpenChange: (o: boolean) => void;
  onSelectUser: (id: string) => void;
}) {
  const me = useMe();
  const getUser = useGetUser();
  const inBet = ids.filter((id) => bet?.participants.some((p) => p.userId === id));
  const notIn = ids.filter((id) => !inBet.includes(id));
  const row = (id: string) => {
    const st = seatStatus(bet, id, me);
    const u = getUser(id);
    return (
      <AnimatedItem as="li" key={id}>
        <button
          onClick={() => onSelectUser(id)}
          className="flex w-full items-center gap-3 rounded-lg border bg-surface px-3 py-2.5 text-left transition-colors hover:border-primary/60"
        >
          <UserAvatar userId={id} size="sm" className="ring-surface" />
          <span className="min-w-0 flex-1">
            <span className="block break-words leading-tight text-sm font-semibold">{u.username}</span>
          </span>
          {st ? (
            <span
              className={cn(
                "max-w-[45%] truncate rounded-full px-2 py-0.5 font-mono text-[10px] font-bold uppercase tracking-wide",
                SEAT_TONE[st.tone],
              )}
            >
              {st.label}
            </span>
          ) : (
            <span className="font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
              Not in
            </span>
          )}
        </button>
      </AnimatedItem>
    );
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex max-h-[85dvh] flex-col gap-4">
        <DialogHeader>
          <DialogTitle className="font-display text-3xl font-black uppercase">
            {ids.length} other{ids.length === 1 ? "" : "s"}
          </DialogTitle>
          <DialogDescription>
            {!bet
              ? "No open bet right now."
              : bet.status === "active"
                ? `${getMode(bet.modeId).name}: picks stay hidden until the timer runs out.`
                : `${getMode(bet.modeId).name}: picks are locked in.`}
          </DialogDescription>
        </DialogHeader>
        <ScrollContainer isVisible={false} className="-mx-2 flex-1 space-y-4 px-2">
          {inBet.length > 0 && (
            <section className="space-y-1.5">
              <p className="font-mono text-[10px] font-bold uppercase tracking-widest text-primary">
                In this bet · {inBet.length}
              </p>
              <ul className="grid gap-1.5">
                <AnimatedList>{inBet.map(row)}</AnimatedList>
              </ul>
            </section>
          )}
          {notIn.length > 0 && (
            <section className="space-y-1.5">
              <p className="font-mono text-[10px] font-bold uppercase tracking-widest text-muted-foreground">
                Not in this bet · {notIn.length}
              </p>
              <ul className="grid gap-1.5">
                <AnimatedList>{notIn.map(row)}</AnimatedList>
              </ul>
            </section>
          )}
        </ScrollContainer>
      </DialogContent>
    </Dialog>
  );
}

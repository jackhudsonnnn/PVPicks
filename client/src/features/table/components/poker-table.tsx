import { netSettleUps } from "../settlements";
import { motion, useReducedMotionConfig } from "motion/react";
import { cn } from "@/lib/utils";
import { useAppStore, useMe, useGetUser } from "@/features/store";
import { formatPts } from "@/features/bets/ledger";
import type { Bet } from "@/features/bets/types";
import type { Table } from "@/features/table/types";
import { UserAvatar } from "@/features/social/components/user-avatar";
import { arrangeSeats, seatPosition } from "../seating";
import { BetStack } from "./bet-stack";

export function PokerTable({
  table,
  openBets,
  focused,
  onFocus,
  onOpenBet,
  onSelectUser,
  onOpenOthers,
  onPropose,
}: {
  table: Table;
  openBets: Bet[];
  focused: Bet | undefined;
  onFocus: (id: string) => void;
  onOpenBet: (id: string) => void;
  onSelectUser: (id: string) => void;
  onOpenOthers: () => void;
  /** Omitted when you can't start bets here. */
  onPropose?: (() => void) | undefined;
}) {
  const me = useMe();
  const getUser = useGetUser();
  const { state } = useAppStore();
  const { seated, rail } = arrangeSeats(table.memberIds, me, focused);
  // Positions snap to distinct slots; only arrivals fade in. Sliding positions
  // crosses occupied seats and can strand a moving avatar when the list changes.
  const reduced = !!useReducedMotionConfig();
  const seatTransition = { duration: reduced ? 0 : 0.18 };
  // Keep mounted nodes in table order even when your bottom seat has a different index.
  const seatSlots = seated.map((id, index) => ({ id, index }));
  seatSlots.sort((a, b) => table.memberIds.indexOf(a.id) - table.memberIds.indexOf(b.id));

  const seatCount = seated.length + (rail.length > 0 ? 1 : 0);
  const othersPos = seatPosition(seated.length, Math.max(seatCount, 2));

  return (
    <div className="mx-auto flex h-full w-full max-w-3xl items-center justify-center px-8 pb-12 pt-10 [container-type:size] sm:px-14 sm:pb-16 sm:pt-14">
      <div className="flex w-full items-center justify-center">
        <div className="felt-table relative aspect-[5/6] w-[min(100cqw,calc(100cqh*5/6))] sm:aspect-[7/5] sm:w-[min(100cqw,calc(100cqh*7/5))]">
          <div className="felt-line absolute inset-[7%]" data-tomato-table />
          <span className="pointer-events-none absolute left-1/2 top-[16%] -translate-x-1/2 font-display text-xs font-black uppercase tracking-[0.3em] text-foreground/15 sm:text-sm">
            P2Picks
          </span>

          <div className="absolute inset-0 flex items-center justify-center">
            <BetStack
              bets={openBets}
              focusedId={focused?.id}
              onFocus={onFocus}
              onOpen={onOpenBet}
              onPropose={onPropose}
            />
          </div>

          <>
            {seatSlots.map(({ id, index }) => {
              const pos = seatPosition(index, Math.max(seatCount, 2));
              const name = id === me ? "You" : getUser(id).username.split(" ")[0];
              return (
                <motion.button
                  key={id}
                  data-tomato-user={id}
                  onClick={() => onSelectUser(id)}
                  className="group absolute z-10 flex w-16 flex-col items-center sm:w-20"
                  style={{ x: "-50%", y: "-50%", left: `${pos.left}%`, top: `${pos.top}%` }}
                  initial={reduced ? false : { opacity: 0, scale: 0.85 }}
                  animate={{ opacity: 1, scale: 1 }}
                  transition={seatTransition}
                  aria-label={`${name}'s profile`}
                >
                  <span className="relative">
                    <UserAvatar
                      userId={id}
                      size="md"
                      className={cn(
                        "size-8 text-xs ring-felt-rim transition-transform group-hover:scale-105 sm:size-10 sm:text-sm",
                        id === me && "ring-primary",
                      )}
                    />
                    {focused?.proposerId === id && (
                      <span
                        className="absolute -right-1 -top-1 flex size-4 items-center justify-center rounded-full bg-foreground font-display text-[9px] font-black text-background"
                        title="Proposer"
                      >
                        P
                      </span>
                    )}
                  </span>
                  <span className="relative mt-0.5 w-full rounded-md border bg-felt-rim px-1 py-0.5 text-center shadow-lg">
                    <span className="block truncate text-[10px] font-semibold leading-tight sm:text-[11px]">
                      {name}
                    </span>
                    {(() => {
                      // Net open settle-ups, same number as the top-right pill.
                      const n = netSettleUps(state.messages, id);
                      return (
                        <span
                          className={cn(
                            "block font-mono text-[9px] leading-tight sm:text-[10px]",
                            n > 0 ? "text-win" : n < 0 ? "text-loss" : "text-muted-foreground",
                          )}
                        >
                          {formatPts(n, true)}
                        </span>
                      );
                    })()}
                  </span>
                </motion.button>
              );
            })}
            {rail.length > 0 && (
              <motion.button
                key="others"
                data-tomato-others={JSON.stringify(rail)}
                onClick={onOpenOthers}
                className="group absolute z-10 flex w-16 flex-col items-center sm:w-20"
                style={{ x: "-50%", y: "-50%", left: `${othersPos.left}%`, top: `${othersPos.top}%` }}
                initial={reduced ? false : { opacity: 0, scale: 0.85 }}
                animate={{ opacity: 1, scale: 1 }}
                transition={seatTransition}
                aria-label={`${rail.length} other${rail.length === 1 ? "" : "s"} at this table`}
              >
                <span className="flex size-8 items-center justify-center rounded-full border-2 border-dashed border-foreground/40 bg-felt-rim font-display text-xs font-black transition-transform group-hover:scale-105 sm:size-10 sm:text-sm">
                  +{rail.length}
                </span>
                <span className="relative mt-0.5 w-full rounded-md border bg-felt-rim px-1 py-0.5 text-center shadow-lg">
                  <span className="block truncate text-[10px] font-semibold leading-tight sm:text-[11px]">
                    {rail.length} other{rail.length === 1 ? "" : "s"}
                  </span>
                  <span className="block font-mono text-[9px] leading-tight text-muted-foreground sm:text-[10px]">
                    Tap to view
                  </span>
                </span>
              </motion.button>
            )}
          </>
        </div>
      </div>
    </div>
  );
}

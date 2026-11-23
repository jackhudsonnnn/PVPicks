import { useQuery, useQueryClient } from "@tanstack/react-query";
import { formatCents } from "@/lib/money";
import { userKey } from "@/lib/query-keys";
import { useRealtimeFeed } from "@/lib/realtime";
import { tableBetsFeed } from "@/lib/realtime-connection";
import type { Bet } from "./api";
import type { User } from "@/features/people";
import { getHoldemGame, type HoldemGame } from "./holdem";
import type { SeatChip } from "./jackblack-game";

/**
 * The game as the viewer sees it, shared by the ticket, the felt and Details (one cache entry). Refreshes with the
 * table's bet feed, and every few seconds while it's live as a fallback.
 */
export function useHoldemGame(bet: Bet, user: User, live: boolean, enabled = true) {
  const key = userKey(user.id, "holdem", bet.id);
  const queryClient = useQueryClient();
  useRealtimeFeed(live && enabled ? tableBetsFeed(bet.tableId) : null, () => {
    void queryClient.invalidateQueries({ queryKey: key });
  });
  return useQuery({
    queryKey: key,
    queryFn: ({ signal }) => getHoldemGame(bet.id, signal),
    enabled,
    refetchInterval: live ? 4000 : false,
    retry: false,
  });
}

/** A player's running result: their stack minus what they bought in for. */
export function runningTotal(game: HoldemGame, userId: string): number | null {
  if (game.totals?.[userId] !== undefined) return game.totals[userId];
  const stack = game.stacks?.[userId];
  const buyIns = game.buyIns?.[userId];
  if (stack === undefined || buyIns === undefined || game.buyInCents === undefined) return null;
  // Chips still in the pot this hand count as spent until it's paid.
  const inPot = game.seats?.find((s) => s.userId === userId)?.contributed ?? 0;
  return stack + inPot - buyIns * game.buyInCents;
}

function result(cents: number): SeatChip {
  if (cents === 0) return { label: "Even", tone: "pass" };
  return cents > 0
    ? { label: `Up ${formatCents(cents, true)}`, tone: "win" }
    : { label: `Down ${formatCents(-cents)}`, tone: "loss" };
}

/**
 * Each player's chip on the felt for a Hold'em game: "In" while the join window is open; in a hand "Thinking" for
 * whoever's turn it is, "Folded", "All in", their bet this street, or their stack; between hands how the hand went
 * for them; at the end their result. Null for anyone not in it.
 */
export function useHoldemSeatChips(bet: Bet | undefined, user: User) {
  const live = !!bet && (bet.status === "ACTIVE" || bet.status === "PENDING");
  const data = useHoldemGame(
    bet ?? ({ id: "", tableId: "" } as Bet),
    user,
    live,
    bet?.modeId === "holdem",
  ).data;
  return (userId: string): SeatChip | null => {
    if (!bet || bet.modeId !== "holdem" || !data) return null;
    if (data.phase === "waiting")
      return data.players?.includes(userId) ? { label: "In", tone: "pick" } : null;
    if (!data.order?.includes(userId)) return null;
    if (data.phase === "done") return result(data.totals?.[userId] ?? 0);
    if (data.phase === "results") {
      const delta = data.history?.at(-1)?.deltas[userId];
      return delta === undefined ? { label: "Out", tone: "washed" } : result(delta);
    }
    const seat = data.seats?.find((s) => s.userId === userId);
    if (!seat) return { label: "Out", tone: "washed" };
    if (data.toActId === userId) return { label: "Thinking", tone: "pick" };
    if (seat.folded) return { label: "Folded", tone: "pass" };
    if (seat.allIn) return { label: "All in", tone: "pick" };
    if (seat.committed > 0) return { label: formatCents(seat.committed), tone: "pick" };
    return { label: formatCents(data.stacks?.[userId] ?? 0), tone: "pass" };
  };
}

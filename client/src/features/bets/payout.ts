// TODO(backend-logic): port 1:1 to the server (integer cents) with these tests; client copy is for previews only.
import { toCents } from "@/lib/money";
import { PASS, type Participant } from "./types";

export type Settlement =
  | { status: "resolved"; deltas: Record<string, number> }
  | { status: "washed"; reason: string; deltas: Record<string, number> };

/** Participants who actually put points in the pot (anyone not on Pass). */
export function inThePot(participants: Participant[]) {
  return participants.filter((p) => p.selection !== PASS);
}

/** Who gets the leftover cent(s) when the losers' pot doesn't split evenly. */
export type RemainderMode = "host" | "proposer" | "earliest" | "random";

export const REMAINDER_MODES: { id: RemainderMode; label: string; description: string }[] = [
  { id: "host", label: "Host", description: "Leftover cents go to the table host." },
  {
    id: "proposer",
    label: "Proposer",
    description: "Leftover cents go to whoever proposed the bet.",
  },
  {
    id: "earliest",
    label: "Earliest pick",
    description: "One cent each to the winners who picked first.",
  },
  { id: "random", label: "Random", description: "One cent each to randomly drawn winners." },
];

export interface RemainderOptions {
  mode: RemainderMode;
  hostId: string;
  proposerId: string;
  /** Makes "random" repeatable for the same bet. */
  seed: string;
}

/**
 * Pooled payout: the Losers' Pot (sum of losing wagers) is split evenly
 * among the winners. No house, no vig. Shares are floored to the cent and the
 * leftover cents are handed out by `remainder`, so deltas always sum to exactly 0.
 * Participants are in pick order (earliest first).
 */
export function settleBet(
  participants: Participant[],
  wager: number,
  choices: string[],
  winningChoice: string | null,
  remainder?: RemainderOptions,
): Settlement {
  const players = inThePot(participants);
  const washed = (reason: string): Settlement => ({ status: "washed", reason, deltas: {} });

  if (winningChoice === null || !choices.includes(winningChoice)) {
    return washed("Outcome fell outside the available choices");
  }
  if (players.length < 2) return washed("Not enough players in the pot");
  const picks = new Set(players.map((p) => p.selection));
  if (picks.size === 1) return washed("Everyone picked the same side");

  const winners = players.filter((p) => p.selection === winningChoice);
  if (winners.length === 0) return washed("Nobody picked the winning choice");

  const losers = players.filter((p) => p.selection !== winningChoice);
  const potCents = Math.round(losers.length * wager * 100);
  const shareCents = Math.floor(potCents / winners.length);
  let leftover = potCents - shareCents * winners.length;

  const cents: Record<string, number> = {};
  for (const w of winners) cents[w.userId] = shareCents;
  for (const l of losers) cents[l.userId] = -Math.round(wager * 100);

  if (leftover > 0) {
    const opts = remainder ?? { mode: "earliest", hostId: "", proposerId: "", seed: "" };
    const to = opts.mode === "host" ? opts.hostId : opts.mode === "proposer" ? opts.proposerId : "";
    if (to) {
      // The host/proposer gets the cents even if they sat this one out; totals still sum to 0.
      cents[to] = (cents[to] ?? 0) + leftover;
    } else {
      // Earliest, random, or a missing host/proposer: one cent each down the winners list.
      const order = opts.mode === "random" ? seededShuffle(winners, opts.seed) : winners;
      for (const w of order) {
        if (leftover === 0) break;
        cents[w.userId]! += 1;
        leftover -= 1;
      }
    }
  }

  const deltas: Record<string, number> = {};
  for (const [id, c] of Object.entries(cents)) deltas[id] = c / 100;
  return { status: "resolved", deltas };
}

function seededShuffle<T>(items: T[], seed: string): T[] {
  let h = 2166136261;
  for (let i = 0; i < seed.length; i++) h = Math.imul(h ^ seed.charCodeAt(i), 16777619);
  const rand = () => {
    h = Math.imul(h ^ (h >>> 15), 2246822507);
    h = Math.imul(h ^ (h >>> 13), 3266489909);
    return ((h ^= h >>> 16) >>> 0) / 4294967296;
  };
  const out = [...items];
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    [out[i], out[j]] = [out[j]!, out[i]!];
  }
  return out;
}

/** What a single winner on `choice` would take home given current picks. */
export function projectedWin(participants: Participant[], wager: number, choice: string) {
  const players = inThePot(participants);
  const winners = players.filter((p) => p.selection === choice).length;
  const losers = players.length - winners;
  if (winners === 0 || losers === 0) return 0;
  return toCents((losers * wager) / winners);
}

export interface SettlementLine {
  userId: string;
  /** Positive: the host sends this user that much. Negative: the user sends the host that much. */
  amount: number;
}

/**
 * Host "Settle points": everyone's unsettled net at the table is paid through the host.
 * Winners get paid by the host, losers pay the host. Because bets are zero-sum, the
 * host's net from all transfers equals the host's own result at the table.
 */
export function settlementLines(
  bets: {
    tableId: string;
    status: string;
    settled?: boolean | undefined;
    deltas?: Record<string, number> | undefined;
  }[],
  tableId: string,
  hostId: string,
) {
  const nets = new Map<string, number>();
  for (const b of bets) {
    if (b.tableId !== tableId || b.status !== "resolved" || b.settled) continue;
    for (const [id, d] of Object.entries(b.deltas ?? {})) nets.set(id, (nets.get(id) ?? 0) + d);
  }
  const lines: SettlementLine[] = [...nets.entries()]
    .filter(([id]) => id !== hostId)
    .map(([userId, n]) => ({ userId, amount: toCents(n) }))
    .filter((l) => l.amount !== 0)
    .sort((a, b) => a.amount - b.amount);
  // What the host collects minus what they pay out.
  const hostNet = toCents(-lines.reduce((sum, l) => sum + l.amount, 0));
  return { lines, hostNet };
}

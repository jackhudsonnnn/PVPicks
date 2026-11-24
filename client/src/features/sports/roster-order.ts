import type { GamePlayer } from "./api";

/**
 * Position order for the player pickers: NFL skill positions and kickers, then NBA guards to centers, then the NFL
 * line and defense. One list serves both leagues: the codes they share (G, C) are NFL linemen only on NFL rosters,
 * which list them after the skill positions anyway. Unknown codes come after these, alphabetically; no position last.
 */
const POSITION_ORDER = [
  "QB",
  "RB",
  "HB",
  "FB",
  "WR",
  "TE",
  "K",
  "PK",
  "P",
  "PG",
  "SG",
  "G",
  "G-F",
  "SF",
  "F-G",
  "F",
  "PF",
  "F-C",
  "C-F",
  "C",
  "OL",
  "OT",
  "T",
  "OG",
  "DL",
  "DE",
  "DT",
  "NT",
  "LB",
  "OLB",
  "ILB",
  "MLB",
  "DB",
  "CB",
  "S",
  "FS",
  "SS",
  "LS",
];

function rank(position: string | null): number {
  if (!position) return POSITION_ORDER.length + 1;
  const at = POSITION_ORDER.indexOf(position.toUpperCase());
  return at === -1 ? POSITION_ORDER.length : at;
}

/** Players grouped by position (see the order above), then by team and name. */
export function byPosition(players: readonly GamePlayer[]): GamePlayer[] {
  return [...players].sort(
    (a, b) =>
      rank(a.position) - rank(b.position) ||
      (a.position ?? "").localeCompare(b.position ?? "") ||
      (a.team ?? "").localeCompare(b.team ?? "") ||
      a.name.localeCompare(b.name),
  );
}

const RB = ["RB", "HB", "FB"];

/**
 * The positions that rack up each NFL stat, most relevant first (owner decision, 2026-11-24). NBA stats have no entry:
 * every position plays every stat, so they're ordered by value alone.
 */
const STAT_POSITIONS: Record<string, string[][]> = {
  passing_yards: [["QB"], RB, ["WR"], ["TE"]],
  rushing_yards: [RB, ["QB"], ["WR"], ["TE"]],
  receiving_yards: [["WR"], ["TE"], RB],
  receptions: [["WR"], ["TE"], RB],
  touchdowns: [RB, ["WR"], ["TE"], ["QB"]],
};

/**
 * Players for a stat's picker: `main` is the stat's positions in order of relevance, each by its current value
 * (highest first), so mid-game the likeliest picks lead (both quarterbacks for passing yards); `others` is everyone
 * else by value, shown under a divider. Ties keep the roster order (position, team, name).
 */
export function forStat(players: readonly GamePlayer[], stat: string) {
  const value = (p: GamePlayer) => p.stats[stat] ?? 0;
  const groups = STAT_POSITIONS[stat];
  const byValue = (list: GamePlayer[]) =>
    list
      .map((p, i) => ({ p, i }))
      .sort((a, b) => value(b.p) - value(a.p) || a.i - b.i)
      .map(({ p }) => p);
  const roster = byPosition(players);
  if (!groups) return { main: byValue(roster), others: [] as GamePlayer[] };
  const group = (p: GamePlayer) =>
    groups.findIndex((codes) => codes.includes((p.position ?? "").toUpperCase()));
  const main = groups.flatMap((_, g) => byValue(roster.filter((p) => group(p) === g)));
  const others = byValue(roster.filter((p) => group(p) === -1));
  return { main, others };
}

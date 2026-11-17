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

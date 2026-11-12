import type { Bet, ModeId } from "./types";

/** Modes whose result can change in overtime, so the proposer picks whether it counts. */
const OVERTIME_MODES: readonly ModeId[] = [
  "either_or",
  "king_of_the_hill",
  "scorcerer",
  "total_disaster",
  "give_and_take",
  "prop_hunt",
];

export const OVERTIME_DEFAULT = true;

export const hasOvertimeOption = (modeId: ModeId) => OVERTIME_MODES.includes(modeId);

/** Whether overtime counts for this bet; undefined for modes without the option. */
export function overtimeCounts(bet: Pick<Bet, "modeId" | "overtime">): boolean | undefined {
  if (!hasOvertimeOption(bet.modeId)) return undefined;
  return bet.overtime ?? OVERTIME_DEFAULT;
}

/** Value saved on a new bet: only stored when the mode has the option. */
export function overtimeFor(modeId: ModeId, on: boolean): boolean | undefined {
  return hasOvertimeOption(modeId) ? on : undefined;
}

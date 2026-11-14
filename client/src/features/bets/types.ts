export type BetStatus = "active" | "pending" | "washed" | "resolved";

/** The selection the server stores for someone sitting a bet out. */
export const PASS = "__no_guess__";

export type ModeId =
  | "either_or"
  | "king_of_the_hill"
  | "choose_their_fate"
  | "scorcerer"
  | "total_disaster"
  | "give_and_take"
  | "prop_hunt"
  | "u2pick";

/** Who may call the outcome of a U2Pick (custom) bet. The server enforces it. */
export type SettlerRule = "anyone" | "non_participants" | "hosts" | "me";

export type ModeId =
  | "either_or"
  | "king_of_the_hill"
  | "choose_their_fate"
  | "scorcerer"
  | "total_disaster"
  | "give_and_take"
  | "prop_hunt"
  | "u2pick"
  | "blackjack";

/** Who may call the outcome of a U2Pick (custom) bet. The server enforces it. */
export type SettlerRule = "anyone" | "non_participants" | "hosts" | "me";

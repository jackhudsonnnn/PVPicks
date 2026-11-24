import type { ModeId } from "./types";

/**
 * What each mode's info page says (the proposal sheet's (i) and the Guide's mode cards share it): how the mode
 * works, the rules worth knowing, and 2–3 worked examples with their own settings and results. Static text only;
 * the setup form keeps just its controls and the numbers that change with them (worst case, buy-in).
 *
 * Pick-mode payouts follow the domain rules: everyone who picks stakes the wager, and the winners split the losers'
 * stakes evenly.
 */
interface ModeExample {
  title: string;
  /** The example's settings, shown as chips ("Wager 2.00", "Line 47.5"). */
  setup: string[];
  /** What happened. */
  story: string;
  /** How it ended for everyone. */
  result: string;
  /** A wash or call-off rather than a payout (shown in a neutral colour). */
  wash?: boolean;
}

export interface ModeInfo {
  /** Paragraphs after the mode's one-line description. */
  how: string[];
  /** Short rules worth knowing before proposing. */
  notes: string[];
  examples: ModeExample[];
  /** Shown above everything while the mode can't be proposed yet. */
  comingSoon?: string;
}

const PICK_NOTES = [
  "Sending a bet doesn't place your pick: you pick on the ticket like everyone else.",
  "Winners split the losers' stakes evenly. If every pick is on one side, or nobody picks, it washes and no points move.",
  "Everyone can see who's in from the start; which side they picked stays hidden until lock. Clearing your pick leaves you in as No guess until lock, when you're out.",
];

const SPORTS_NOTES = [
  ...PICK_NOTES,
  "Picks lock when the window ends, or at the game's start if that comes first.",
  "Results come from the live feed about 20 minutes after Final, so stat corrections land first. A host can override any result until points are settled.",
  "Canceled or postponed games wash.",
];

const CARD_NOTES = [
  "You're in your own game as soon as you open the table; others join from the ticket during the join window, and anyone (you included) can leave before the deal.",
  "Every shuffle is fixed and fingerprinted before the deal and revealed for checking once the game is over (ticket Details).",
  "A wager of 0 is a 4fun game: same play, no points.",
];

export const MODE_INFO: Record<ModeId, ModeInfo> = {
  either_or: {
    how: [
      "Choose a game, a stat and two players. Everyone picks the player they think finishes with more of that stat; the whole game's totals count, whenever the bet was made.",
    ],
    notes: [
      ...SPORTS_NOTES,
      "A tie washes, and so does a player in the bet not playing.",
      "With Overtime counts off, it's decided at the end of regulation.",
    ],
    examples: [
      {
        title: "A clear winner",
        setup: ["Passing yards", "Mahomes vs Allen", "Wager 2.00"],
        story:
          "Ann and Ben pick Mahomes; Cal picks Allen. Mahomes finishes with 312, Allen with 280.",
        result: "Ann and Ben split Cal's 2.00: +1.00 each. Cal −2.00.",
      },
      {
        title: "Dead heat",
        setup: ["Points", "Tatum vs Brown", "Wager 1.00"],
        story: "Both finish on 27 points.",
        result: "A tie washes: nobody's points move.",
        wash: true,
      },
      {
        title: "Overtime off",
        setup: ["Rushing yards", "Henry vs Barkley", "Overtime off", "Wager 1.50"],
        story:
          "Henry leads 98–95 at the end of regulation; Barkley adds 12 in overtime. Dee picked Henry, Eli picked Barkley.",
        result: "Only regulation counts, so Henry wins: Dee +1.50, Eli −1.50.",
      },
    ],
  },
  king_of_the_hill: {
    how: [
      "Choose a game, a stat, two players and a target above both of their current numbers. The choices are each player and Neither: whoever reaches the target first wins, and Neither wins if the game ends first.",
    ],
    notes: [
      ...SPORTS_NOTES,
      "If either player reaches the target before picks lock, it washes.",
      "If both first reach it in the same feed update, it washes.",
    ],
    examples: [
      {
        title: "A late surge",
        setup: ["Receiving yards", "Target 100", "Kelce vs Hill", "Wager 2.00"],
        story:
          "Ann picks Kelce, Ben picks Hill, Cal picks Neither. Hill reaches 100 early in the fourth; Kelce ends on 88.",
        result: "Ben takes Ann's and Cal's stakes: +4.00. Ann and Cal −2.00 each.",
      },
      {
        title: "Nobody gets there",
        setup: ["Points", "Target 40", "Curry vs Booker", "Wager 1.00"],
        story: "Curry finishes on 34, Booker on 31. Dee picked Neither; Eli and Fay picked Curry.",
        result: "Neither wins: Dee +2.00, Eli and Fay −1.00 each.",
      },
      {
        title: "Too late to call",
        setup: ["Rushing yards", "Target 60", "Pick window 5 min"],
        story: "One of the players breaks a 45-yard run and passes 60 before picks lock.",
        result: "It washes at lock: nobody's points move.",
        wash: true,
      },
    ],
  },
  choose_their_fate: {
    comingSoon:
      "Coming soon: this mode is designed but can't be proposed yet. The examples show how it's planned to work.",
    how: [
      "Pick a game while it's live and predict how the current drive (NFL) or possession (NBA) ends: a touchdown, a field goal, a punt, a turnover, and so on.",
    ],
    notes: [...SPORTS_NOTES],
    examples: [
      {
        title: "Red-zone call",
        setup: ["NFL", "1st & goal at the 8", "Wager 1.00"],
        story: "Ann and Ben pick Touchdown, Cal picks Field goal. The drive ends in a field goal.",
        result: "Cal takes both stakes: +2.00. Ann and Ben −1.00 each.",
      },
      {
        title: "Empty trip",
        setup: ["NBA", "Possession after a timeout", "Wager 0.50"],
        story: "Dee picks a 3, Eli picks No score. The possession ends on a shot-clock violation.",
        result: "No score wins: Eli +0.50, Dee −0.50.",
      },
    ],
  },
  scorcerer: {
    how: [
      "Pick a game. The choices are each team's next score and how, away team first: a touchdown or field goal (NFL), or a 3 or a 2 (NBA), plus No more scores. The first score after picks lock decides it.",
    ],
    notes: [
      ...SPORTS_NOTES,
      "Safeties and other 2-point plays (NFL) and free throws (NBA) don't count.",
      "If two different scores land between feed updates, or the stats can't tell which play scored, it washes.",
    ],
    examples: [
      {
        title: "Field goal drive",
        setup: ["NFL · BUF @ NE", "Wager 2.00"],
        story:
          "Ann picks NE touchdown, Ben picks NE field goal, Cal picks BUF touchdown. NE kicks a field goal next.",
        result: "Ben takes both stakes: +4.00. Ann and Cal −2.00 each.",
      },
      {
        title: "Free throws don't count",
        setup: ["NBA · BOS @ MIA", "Wager 1.00"],
        story: "Dee picks MIA 2, Eli picks BOS 3. MIA makes two free throws, then BOS hits a 3.",
        result: "The free throws are skipped, so BOS 3 is the next score: Eli +1.00, Dee −1.00.",
      },
      {
        title: "Too close to call",
        setup: ["NBA · LAL @ DEN", "Wager 1.00"],
        story:
          "A LAL 3 and a DEN 2 land in the same feed update, so there's no telling which came first.",
        result: "It washes: nobody's points move.",
        wash: true,
      },
    ],
  },
  total_disaster: {
    how: [
      "Pick a game and set a line for the combined final score. Everyone picks Over or Under. Lines end in .5, so it can't push.",
    ],
    notes: [
      ...SPORTS_NOTES,
      "On a live game the line has to be above the current combined score, and if the score passes it before picks lock, it washes.",
      "With Overtime counts off, it's the score at the end of regulation.",
    ],
    examples: [
      {
        title: "Shootout",
        setup: ["Line 47.5", "Wager 1.00"],
        story: "Ann and Ben take Over, Cal takes Under. Final: 31–24, 55 combined.",
        result: "Over: Ann and Ben split Cal's 1.00, +0.50 each. Cal −1.00.",
      },
      {
        title: "Overtime decides it",
        setup: ["Line 44.5", "Overtime on", "Wager 2.00"],
        story: "21–21 after regulation (42), then a touchdown in overtime: 27–21, 48.",
        result:
          "Over wins with overtime counting. With Overtime off it would have been Under at 42.",
      },
      {
        title: "Passed before lock",
        setup: ["Live · 30 combined", "Line 34.5", "Pick window 5 min"],
        story: "Two quick touchdowns take it to 44 before picks lock.",
        result: "It washes at lock: nobody's points move.",
        wash: true,
      },
    ],
  },
  give_and_take: {
    how: [
      "Pick a game and set a spread on the home team: −3.5 means the home team has to win by 4 or more to cover; +3.5 means they cover unless they lose by 4 or more. Everyone picks a side. Spreads end in .5, so there's no push.",
    ],
    notes: [...SPORTS_NOTES, "With Overtime counts off, it's the margin at the end of regulation."],
    examples: [
      {
        title: "Favourite covers",
        setup: ["NE −3.5", "Wager 2.00"],
        story: "Ann takes NE, Ben and Cal take BUF. NE wins 27–20, by 7.",
        result: "NE covers: Ann takes both stakes, +4.00. Ben and Cal −2.00 each.",
      },
      {
        title: "Wins but doesn't cover",
        setup: ["MIA −6.5", "Wager 1.00"],
        story: "Dee takes MIA, Eli takes BOS. MIA wins 110–105, by 5.",
        result: "BOS covers: Eli +1.00, Dee −1.00.",
      },
      {
        title: "Underdog points",
        setup: ["DEN +2.5", "Wager 1.50"],
        story: "Fay takes DEN, Gus takes the visitors. DEN loses 98–100, by 2.",
        result: "DEN covers with the 2.5 points: Fay +1.50, Gus −1.50.",
      },
    ],
  },
  prop_hunt: {
    how: [
      "Choose a game, a stat, one player and a line. Everyone picks Over or Under on that player's total for the game. Lines end in .5, so it can't push.",
    ],
    notes: [
      ...SPORTS_NOTES,
      "If the player passes the line before picks lock, it washes; so does the player not playing.",
      "With Overtime counts off, it's the total at the end of regulation.",
    ],
    examples: [
      {
        title: "Over the line",
        setup: ["Kelce receiving yards", "Line 74.5", "Wager 1.00"],
        story: "Ann takes Over, Ben and Cal take Under. Kelce finishes with 81.",
        result: "Over: Ann takes both stakes, +2.00. Ben and Cal −1.00 each.",
      },
      {
        title: "Quiet night",
        setup: ["Jokić assists", "Line 9.5", "Wager 2.00"],
        story: "Dee and Eli take Over, Fay takes Under. Jokić ends on 8.",
        result: "Under: Fay takes both stakes, +4.00. Dee and Eli −2.00 each.",
      },
      {
        title: "Scratched",
        setup: ["Passing yards", "Line 249.5"],
        story: "The quarterback is ruled out before kickoff and never plays.",
        result: "It washes: nobody's points move.",
        wash: true,
      },
    ],
  },
  u2pick: {
    how: [
      "No game needed: write the question and two or more options, then choose who calls the result: anyone, only people not in the bet, hosts, or just you.",
    ],
    notes: [
      ...PICK_NOTES,
      "If nobody picked the winning option (possible with three or more), it washes.",
      "If nobody calls the result within 7 days of lock, it washes.",
    ],
    examples: [
      {
        title: "Who's late",
        setup: ["Will Sam be on time?", "Yes / No", "Anyone calls it", "Wager 1.00"],
        story: "Ann and Ben pick No, Cal picks Yes. Sam walks in 20 minutes late and Cal calls No.",
        result: "No: Ann and Ben split Cal's 1.00, +0.50 each. Cal −1.00.",
      },
      {
        title: "Three-way with no winner",
        setup: ["Who wins the quiz?", "Dee / Eli / Fay", "Hosts call it", "Wager 2.00"],
        story: "Everyone picks Dee or Eli. Fay wins.",
        result: "Nobody picked Fay, so it washes.",
        wash: true,
      },
    ],
  },
  jackblack: {
    how: [
      'Each player deals once, and every hand plays against that round\'s dealer, not a pot. "Run it back" at the end starts another game.',
      "Everyone still playing chooses hit, stand, double or split at the same time, so nobody sees anyone's cards before deciding. If the decision timer runs out, no choice means stand.",
      "Each round gets a fresh shoe of 1 to 8 decks, its size drawn at random and kept hidden until the game is over.",
    ],
    notes: [
      ...CARD_NOTES,
      "Hands pay 1:1, a natural pays 3:2 or 6:5 (rounded down), a push pays nothing. Soft 17 stands; a dealer natural ends the round.",
      "Your worst case is held against your points limit while the game is on. Anyone who can't cover it at the deal is dropped.",
      "Fewer players than the minimum when joining closes and the game is called off.",
    ],
    examples: [
      {
        title: "Three players",
        setup: ["3 players (3 rounds)", "Ante 1.00"],
        story:
          "Round 1, Ann deals: Ben stands on 20 against her 19, Cal busts. Ann pays Ben 1.00 and takes Cal's 1.00, then Ben and Cal each deal a round.",
        result:
          "Each player's results add up over the three rounds; the totals always sum to zero.",
      },
      {
        title: "A natural at 3:2",
        setup: ["Ante 2.00", "Blackjack pays 3:2"],
        story: "Dee is dealt A♠ K♥ while Eli deals and has no natural.",
        result: "Dee's natural pays 3.00 right away; Eli −3.00 for that hand.",
      },
      {
        title: "Not enough joined",
        setup: ["Players 3–5", "Join window 1 min"],
        story: "Only Fay and Gus are in when the join window closes.",
        result: "Called off: nobody's points move.",
        wash: true,
      },
    ],
  },
  holdem: {
    how: [
      'One hand per player, the button moving one seat a hand; the small blind is half the big blind. Stacks carry from hand to hand, and a player who busts can rebuy once in the game, right then (15 seconds to decide). "Run it back" at the end starts another game.',
      "Everyone still in at a showdown shows their cards.",
    ],
    notes: [
      ...CARD_NOTES,
      "When the turn timer runs out you check if you can, otherwise fold.",
      "Both buy-ins (200 big blinds) are held against your points limit while the game is on.",
      "Fewer players than the minimum when joining closes and the game is called off.",
    ],
    examples: [
      {
        title: "Four players",
        setup: ["4 players", "Big blind 1.00", "Buy-in 100.00", "4 hands"],
        story:
          "Ann wins two big pots and finishes with 160.00; Ben ends on 90.00, Cal on 80.00, Dee on 70.00.",
        result: "Final stack minus buy-in: Ann +60.00, Ben −10.00, Cal −20.00, Dee −30.00.",
      },
      {
        title: "Bust and rebuy",
        setup: ["Big blind 0.50", "Buy-in 50.00"],
        story:
          "Eli goes all in on hand 2 and loses, rebuys for another 50.00, and finishes the game with 70.00.",
        result: "Eli bought in for 100.00 in all, so his result is −30.00.",
      },
      {
        title: "Split pot",
        setup: ["Big blind 1.00", "Pot 41.00"],
        story: "Fay and Gus both make the same straight at the showdown.",
        result: "The pot splits evenly, 20.50 each (any odd cent goes to one of them at random).",
      },
    ],
  },
};

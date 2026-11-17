import {
  BadgeDollarSign,
  Scale,
  ShieldCheck,
  Users,
  Handshake,
  Radio,
  Zap,
  Ban,
  Flag,
  Trash2,
  MessagesSquare,
  Send,
  Hourglass,
} from "lucide-react";

export type ModeFilter = "all" | "u2pick" | "nba" | "nfl";

export const FILTERS = [
  { id: "all", label: "All" },
  { id: "u2pick", label: "U2Pick" },
  { id: "nba", label: "NBA" },
  { id: "nfl", label: "NFL" },
] as const;

export const SECTIONS = [
  { id: "lifecycle", label: "Bet lifecycle" },
  { id: "modes", label: "Game modes" },
  { id: "strategy", label: "Strategy" },
  { id: "home-game", label: "Like a home game" },
  { id: "settling-up", label: "Settling up" },
  { id: "quick-picks", label: "Quick Picks" },
  { id: "safety", label: "Safety and reports" },
  { id: "feedback", label: "Feedback" },
  { id: "money", label: "How we make money" },
];

export const MONEY_POINTS = [
  {
    icon: BadgeDollarSign,
    title: "Free to play",
    text: "No sign-up fee, no subscription, no vig and no cut of any pot. Every point that the table wagers stays within the group. Points are a scorecard, not a bill: nobody is ever required to pay up in real life.",
  },
  {
    icon: Scale,
    title: "Less legal weight",
    text: "Apps that hold deposits, move money or take a share of wagers are usually run as sportsbooks or payment services, with licenses, state-by-state approval and identity checks. P2Picks only keeps score, so it stays out of that business.",
  },
  {
    icon: ShieldCheck,
    title: "Better for you",
    text: "Nothing to deposit, nothing stuck in a balance and no house edge working against you. You settle up with friends however your group already does.",
  },
];

export const SOCIAL_POINTS = [
  {
    icon: Users,
    title: "Friends only",
    text: "Tables are invite-only groups of people who already know each other: friends, coworkers, roommates. It's a home game, not a public sportsbook.",
  },
  {
    icon: Scale,
    title: "Nobody has an edge",
    text: "Every player gets the same odds and the same rules. Hosts run the table but never get a better payout, and neither do we.",
  },
  {
    icon: Handshake,
    title: "Honor system",
    text: "Settling up is between you and your friends. Points don't create a real debt, and nobody can force you to pay. Pay what you agreed to because you're a good friend.",
  },
];

export const SETTLE_POINTS = [
  {
    icon: Handshake,
    title: "Between you",
    text: "Disputes about paying up are handled privately by the people involved. P2Picks doesn't hold money, so we can't move it, refund it or make anyone pay.",
  },
  {
    icon: MessagesSquare,
    title: "Ask the host",
    text: "The table's host can see every ticket and the settlement card in chat. Most mix-ups are a misread result or a payment nobody confirmed.",
  },
  {
    icon: ShieldCheck,
    title: "Settle often",
    text: "After a host settles points, open someone's Ledger under Friends, Settle-ups and pick the receipts to settle. They net out, so a few receipts can be one payment. Your points number is the net of what's still open.",
  },
  {
    icon: Send,
    title: "Send, receive, forgive",
    text: "If you owe on what you picked, pay them your usual way, then Send. It counts as settled right away, and they have 48 hours to reject it if the money never arrived. If you're owed, Mark received when you're paid, or Forgive it. Up to 3 sends a day between two people.",
  },
  {
    icon: Hourglass,
    title: "Friction on purpose",
    text: "P2Picks never touches the money, so paying up always happens outside the app: a transfer you make yourself, to a friend who can see it. That pause is deliberate. It keeps losses real and visible, gives you a moment before chasing them, and keeps your group in the loop. A send also keeps counting toward your debt cap until its 48 hours pass, so claiming a payment can't free up room to keep betting. If you're betting more than you meant to, set a debt cap in Preferences and talk to someone you trust.",
  },
];

export const QUICK_PICK_POINTS = [
  {
    icon: Radio,
    title: "Share",
    text: "A host turns on Quick Picks in the Host tab and shares the table's bets. That makes a 6-character code to give other hosts. They don't send anything back.",
  },
  {
    icon: Zap,
    title: "Subscribe",
    text: "Another host types the code in. From then on, every new bet started there posts at their table right away, as an exact copy: same question, wager, timer and options.",
  },
  {
    icon: Scale,
    title: "Results",
    text: "The table that started the bet calls the result, and every copy follows. If a call looks wrong, a host at your table can wash your copy. That only changes your table.",
  },
];

export const SAFETY_POINTS = [
  {
    icon: Flag,
    title: "Report",
    text: "Tap someone's profile or the ⋯ on a message to report harassment, spam or offensive content. Reports are private: the person isn't told who sent it.",
  },
  {
    icon: Ban,
    title: "Block",
    text: "Blocking is instant and private. Their chats are hidden for you, you stop following each other, and neither of you can add the other to a table.",
  },
  {
    icon: Trash2,
    title: "Delete anytime",
    text: "You can delete your account in Preferences, Account. So can your friends. Past tickets stay as “Deleted user” so totals add up, but the app can't collect what they owe.",
  },
];

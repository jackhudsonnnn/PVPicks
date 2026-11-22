import {
  Trophy,
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
  CheckCheck,
  Hourglass,
} from "lucide-react";

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
    icon: Trophy,
    title: "Free to play",
    text: "No sign-up fee and no subscription. Points are a scorecard for bragging rights: they can't be bought, sold, cashed out or traded for anything.",
  },
  {
    icon: Ban,
    title: "No cash value",
    text: "Points aren't money and don't stand for money. Don't use P2Picks to track real-money bets. Settle-ups only clear points.",
  },
  {
    icon: ShieldCheck,
    title: "Set your limit",
    text: "A points limit in Preferences caps how far down you can go. Take a break when it stops being fun.",
  },
];

export const SOCIAL_POINTS = [
  {
    icon: Users,
    title: "Friends only",
    text: "Tables are invite-only groups of people who already know each other: friends, coworkers, roommates. It's game night with friends, not a public contest.",
  },
  {
    icon: Scale,
    title: "Nobody has an edge",
    text: "Every player gets the same rules. Hosts run the table but never get a better payout, and neither do we.",
  },
  {
    icon: Handshake,
    title: "Good sports",
    text: "Settling up only clears points between you and a friend. Nobody owes anything outside the game.",
  },
];

export const SETTLE_POINTS = [
  {
    icon: Handshake,
    title: "Between you",
    text: "Disagreements about a result are sorted out by the people involved and the table's host.",
  },
  {
    icon: MessagesSquare,
    title: "Ask the host",
    text: "The table's host can see every ticket and the settlement card in chat. Most mix-ups are a misread result.",
  },
  {
    icon: ShieldCheck,
    title: "Settle often",
    text: "After a host settles points, open someone's Ledger under Friends, Settle-ups and pick the receipts to settle. They net out, so a few receipts settle at once. Your points number is the net of what's still open.",
  },
  {
    icon: CheckCheck,
    title: "Settle or mark settled",
    text: "If you're down on what you picked, tap Settle. It counts right away, and they have 48 hours to reject it if they don't agree. If you're up, tap Mark as settled to close it. Up to 3 settles a day between two people.",
  },
  {
    icon: Hourglass,
    title: "Built-in brakes",
    text: "A settle keeps counting toward your points limit until its 48 hours pass, so settling can't free up room to keep playing right away. If you're playing more than you meant to, set a points limit in Preferences and talk to someone you trust.",
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
    text: "You can delete your account in Preferences, Account. So can your friends. Past tickets stay as “Deleted user” so totals add up.",
  },
];

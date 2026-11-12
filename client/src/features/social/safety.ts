// TODO(backend-logic): blocks/reports enforced and stored server-side; client only hides UI.
/**
 * Report and block rules. Blocking is private (the other person isn't told) and immediate:
 * their chats are hidden for you, follows between you are removed, and neither of you can
 * add the other to a table. Reports go to the P2Picks team's review queue.
 */

/** Who each user has blocked. */
export type Blocks = Record<string, string[]>;

const blockedBy = (b: Blocks, userId: string) => b[userId] ?? [];

/** True if either person blocked the other. */
export const isBlocked = (b: Blocks, a: string, c: string) =>
  blockedBy(b, a).includes(c) || blockedBy(b, c).includes(a);

export function setBlock(b: Blocks, from: string, to: string, on: boolean): Blocks {
  if (from === to) return b;
  const cur = blockedBy(b, from);
  if (on === cur.includes(to)) return b;
  return { ...b, [from]: on ? [...cur, to] : cur.filter((x) => x !== to) };
}

export type ReportReason = "harassment" | "settlement" | "spam" | "offensive" | "other";

export const REPORT_REASONS: { id: ReportReason; label: string; description: string }[] = [
  { id: "harassment", label: "Harassment", description: "Bullying, threats or targeting someone." },
  {
    id: "settlement",
    label: "Won't pay",
    description: "Refusing to settle up, or pressuring someone to.",
  },
  { id: "spam", label: "Spam", description: "Ads, scams, links or flooding the chat." },
  { id: "offensive", label: "Offensive", description: "Hateful, sexual or violent content." },
  { id: "other", label: "Other", description: "Something else. Tell us what happened." },
];

export type ReportStatus = "open" | "reviewing" | "actioned" | "dismissed";

export const REPORT_STATUS_LABEL: Record<ReportStatus, string> = {
  open: "Received",
  reviewing: "In review",
  actioned: "Action taken",
  dismissed: "No action",
};

export interface Report {
  id: string;
  reporterId: string;
  /** Which demo account filed it (so each account sees only its own reports). */
  reporterAccount?: string | undefined;
  targetId: string;
  reason: ReportReason;
  note: string;
  tableId?: string | undefined;
  messageId?: string | undefined;
  /** Copy of the reported message, kept so the review doesn't depend on the chat. */
  messageText?: string | undefined;
  createdAt: number;
  status: ReportStatus;
}

export const NOTE_MAX = 500;

/** Why a report can't be sent yet, or null when it's ready. */
export function reportProblem(input: {
  reporterId: string;
  targetId: string;
  reason: ReportReason | null;
  note: string;
}): string | null {
  if (input.reporterId === input.targetId) return "You can't report yourself.";
  if (!input.reason) return "Pick a reason.";
  if (input.note.length > NOTE_MAX) return `Keep it under ${NOTE_MAX} characters.`;
  if (input.reason === "other" && !input.note.trim()) return "Tell us what happened.";
  return null;
}

/** Order for the review queue: unhandled first, then newest. */
export function queueOrder(reports: Report[]): Report[] {
  const rank: Record<ReportStatus, number> = { open: 0, reviewing: 1, actioned: 2, dismissed: 2 };
  return [...reports].sort((a, b) => rank[a.status] - rank[b.status] || b.createdAt - a.createdAt);
}

import type { ReportReason } from "./social-api";

export const NOTE_MAX = 500;

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

/** Why a report can't be sent yet, or null. The server rechecks. */
export function reportProblem(reason: ReportReason, note: string): string | null {
  if ([...note.trim()].length > NOTE_MAX) return `Keep it to ${NOTE_MAX} characters.`;
  if (reason === "other" && !note.trim()) return "Tell us what happened.";
  return null;
}

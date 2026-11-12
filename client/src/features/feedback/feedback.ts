/**
 * Player feedback sent from the Guide. Perk ideas are welcome, but only for extras outside
 * betting: nothing that gives anyone (us included) an edge on a ticket.
 */
export type FeedbackKind = "mode" | "perk" | "gif" | "bug" | "other";

export const FEEDBACK_KINDS: {
  id: FeedbackKind;
  label: string;
  placeholder: string;
  hint: string;
}[] = [
  {
    id: "mode",
    label: "Game mode",
    placeholder: "Describe the bet: the question, the choices, and how it resolves.",
    hint: "New ways to bet with your table. NFL, NBA or U2Pick ideas all count.",
  },
  {
    id: "perk",
    label: "Perk idea",
    placeholder: "What extra would you support P2Picks for?",
    hint: "Supporter extras only live outside betting, like themes or badges. Never an edge on a ticket.",
  },
  {
    id: "gif",
    label: "GIF",
    placeholder: "Paste a link or describe the GIF and when you'd send it.",
    hint: "Reactions your table needs in the GIF picker.",
  },
  {
    id: "bug",
    label: "Bug",
    placeholder: "What did you do, what happened, and what did you expect?",
    hint: "Something broken or confusing. Steps to make it happen again help most.",
  },
  {
    id: "other",
    label: "Other",
    placeholder: "Anything else on your mind.",
    hint: "Anything else you want the team to hear.",
  },
];

const FEEDBACK_MIN = 10;
export const FEEDBACK_MAX = 1000;

export interface Feedback {
  id: string;
  kind: FeedbackKind;
  text: string;
  /** Which demo account sent it. */
  account?: string | undefined;
  /** Optional link (e.g. a GIF URL). */
  link?: string | undefined;
  createdAt: number;
}

/** Why feedback can't be sent yet, or null when it's ready. */
export function feedbackProblem(text: string, link = ""): string | null {
  const t = text.trim();
  if (t.length < FEEDBACK_MIN) return `Write at least ${FEEDBACK_MIN} characters.`;
  if (t.length > FEEDBACK_MAX) return `Keep it under ${FEEDBACK_MAX} characters.`;
  if (link.trim() && !/^https?:\/\/\S+$/i.test(link.trim()))
    return "Links start with http:// or https://";
  return null;
}

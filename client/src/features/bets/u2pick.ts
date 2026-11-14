import { PASS, type SettlerRule } from "./types";

/** Rules for U2Pick, the custom mode where the proposer writes the question and options. */

export const MAX_OPTIONS = 6;
export const MAX_QUESTION = 120;
export const MAX_OPTION = 40;

export const SETTLER_RULES: { id: SettlerRule; label: string; description: string }[] = [
  { id: "anyone", label: "Anyone", description: "Anyone at the table can call it." },
  {
    id: "non_participants",
    label: "Non-participants",
    description: "Only people at the table who aren't in the pot. If everyone's in, hosts call it.",
  },
  { id: "hosts", label: "Hosts", description: "Any host of this table calls it." },
  { id: "me", label: "Me", description: "Only the person who proposed it calls it." },
];

export const settlerRule = (id: SettlerRule) => SETTLER_RULES.find((r) => r.id === id)!;

/** Trimmed options with blanks and case-insensitive duplicates removed. */
export function cleanOptions(options: string[]) {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const raw of options) {
    const o = raw.trim();
    const key = o.toLowerCase();
    if (!o || seen.has(key)) continue;
    seen.add(key);
    out.push(o);
  }
  return out;
}

/** Why a custom bet can't be sent yet, or null when it's ready. */
export function customBetProblem(question: string, options: string[]): string | null {
  if (!question.trim()) return "Write what you're betting on.";
  if ([...question.trim()].length > MAX_QUESTION)
    return `Keep the question to ${MAX_QUESTION} characters or fewer.`;
  const raw = options.map((o) => o.trim()).filter(Boolean);
  const clean = cleanOptions(options);
  if (clean.length < 2) return "Add at least two options.";
  if (clean.length !== raw.length) return "Each option needs to be different.";
  if (clean.length > MAX_OPTIONS) return `Use ${MAX_OPTIONS} options or fewer.`;
  if (clean.some((o) => [...o].length > MAX_OPTION))
    return `Keep options to ${MAX_OPTION} characters or fewer.`;
  if (clean.some((o) => o.toLowerCase() === PASS || o.toLowerCase() === "no guess"))
    return `"No guess" means sitting out, so it can't be an option.`;
  return null;
}

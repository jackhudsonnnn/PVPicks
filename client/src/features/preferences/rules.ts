/** Whole points as cents, or why the text isn't a valid cap (1 to 21,474,836 whole points). */
export function parseCap(text: string): { cents: number } | { problem: string } {
  const trimmed = text.trim();
  if (!/^\d+$/.test(trimmed)) return { problem: "Enter a whole number of points." };
  const points = Number(trimmed);
  if (points < 1 || points > 21474836) return { problem: "Use 1 to 21,474,836 points." };
  return { cents: points * 100 };
}

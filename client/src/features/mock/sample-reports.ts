import type { Report } from "@/features/social/safety";

const HOUR = 3_600_000;

/** Sample moderation queue so the Admin view has something to review. */
export function sampleReports(now: number): Report[] {
  return [
    {
      id: "rs1",
      reporterId: "u_marcus",
      targetId: "u_omar",
      reason: "settlement",
      note: "Lost 6 tickets in a row and now says the points don't count.",
      tableId: "t_sunday",
      createdAt: now - 2 * HOUR,
      status: "open",
    },
    {
      id: "rs2",
      reporterId: "u_lena",
      targetId: "u_kofi",
      reason: "spam",
      note: "Keeps posting the same link to a sportsbook promo.",
      messageText: "free $200 bet at the link in my bio!!",
      createdAt: now - 5 * HOUR,
      status: "reviewing",
    },
    {
      id: "rs3",
      reporterId: "u_rina",
      targetId: "u_jordan",
      reason: "harassment",
      note: "",
      messageText: "you're so bad at this, just leave the table",
      createdAt: now - 26 * HOUR,
      status: "actioned",
    },
    {
      id: "rs4",
      reporterId: "u_tess",
      targetId: "u_luis",
      reason: "other",
      note: "Think he made a second account to pad a pot.",
      createdAt: now - 50 * HOUR,
      status: "dismissed",
    },
  ];
}

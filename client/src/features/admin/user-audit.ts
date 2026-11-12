import { toCents } from "@/lib/money";
import { PASS, type Bet } from "@/features/bets/types";
import type { Report } from "@/features/social/safety";
import type { SeedState } from "@/features/mock/profiles";
import { outcomes } from "./analytics";

export interface UserAudit {
  userId: string;
  tables: { id: string; name: string; emoji: string; host: boolean }[];
  tickets: Bet[];
  record: ReturnType<typeof outcomes>;
  /** Points won minus lost on resolved, non-4fun tickets. */
  net: number;
  proposed: number;
  picksMade: number;
  chatLines: number;
  reportsAgainst: Report[];
  reportsFiled: Report[];
}

/** Everything the admin needs to review one person inside one sample world. */
export function auditUser(userId: string, world: SeedState, reports: Report[]): UserAudit {
  const tables = world.tables
    .filter((t) => t.memberIds.includes(userId))
    .map((t) => ({ id: t.id, name: t.name, emoji: t.emoji, host: t.hostIds.includes(userId) }));
  const tickets = world.bets.filter((b) => b.participants.some((p) => p.userId === userId));
  const net = toCents(
    world.bets

      .filter((b) => b.status === "resolved" && !b.forFun)
      .reduce((s, b) => s + (b.deltas?.[userId] ?? 0), 0),
  );
  return {
    userId,
    tables,
    tickets,
    record: outcomes(
      userId,
      tickets.filter((b) => !b.forFun),
    ),
    net,
    proposed: world.bets.filter((b) => b.proposerId === userId).length,
    picksMade: tickets.filter((b) =>
      b.participants.some((p) => p.userId === userId && p.selection !== PASS),
    ).length,
    chatLines: world.messages.filter((m) => "authorId" in m && m.authorId === userId).length,
    reportsAgainst: reports.filter((r) => r.targetId === userId),
    reportsFiled: reports.filter((r) => r.reporterId === userId),
  };
}

// ---- Admin actions (simulated until a server exists) ----

export interface AdminFlags {
  frozen?: boolean;
  flagged?: boolean;
  strikes?: number;
  notes?: string[];
}
export type AdminAction =
  | { type: "freeze"; on: boolean }
  | { type: "flag"; on: boolean }
  | { type: "strike" }
  | { type: "clearStrikes" }
  | { type: "note"; text: string };

export const MAX_STRIKES = 3;

/** Applies one admin action to a user's flags. A third strike freezes the account. */
export function applyAdminAction(f: AdminFlags, a: AdminAction): AdminFlags {
  switch (a.type) {
    case "freeze":
      return { ...f, frozen: a.on };
    case "flag":
      return { ...f, flagged: a.on };
    case "strike": {
      const strikes = Math.min(MAX_STRIKES, (f.strikes ?? 0) + 1);
      return { ...f, strikes, frozen: strikes >= MAX_STRIKES || !!f.frozen };
    }
    case "clearStrikes":
      return { ...f, strikes: 0 };
    case "note": {
      const text = a.text.trim();
      return text ? { ...f, notes: [text, ...(f.notes ?? [])] } : f;
    }
  }
}

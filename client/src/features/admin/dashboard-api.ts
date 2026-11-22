import { z } from "zod";
import { csrf, idSchema, read, request } from "@/lib/api-client";
import { page, withCursor, type AdminPage } from "./api";

const at = z.string().datetime({ offset: true });
const date = z.string().regex(/^\d{4}-\d{2}-\d{2}$/);
const options = (signal?: AbortSignal) => (signal ? { signal } : undefined);

// ---- User audit -------------------------------------------------------------------------------

const reportCounts = z.object({ open: z.number(), resolved: z.number(), dismissed: z.number() });
const winLoss = z.object({ won: z.number(), lost: z.number() });
const auditReport = z.object({
  id: idSchema,
  reason: z.enum(["harassment", "settlement", "spam", "offensive", "other"]),
  note: z.string(),
  status: z.enum(["OPEN", "RESOLVED", "DISMISSED"]),
  createdAt: at,
  otherId: idSchema,
  otherName: z.string().nullable(),
  otherHue: z.number().int().min(1).max(5).nullable(),
  tableName: z.string().nullable(),
  messageSnapshot: z.string().nullable(),
});

const auditSchema = z.object({
  profile: z.object({
    id: idSchema,
    username: z.string().nullable(),
    hue: z.number().nullable(),
    status: z.enum(["ACTIVE", "FROZEN", "DELETED"]),
    createdAt: at,
    deletedAt: at.nullable(),
    lastSignInAt: at.nullable(),
    activeSessions: z.number(),
    codeRotatedAt: at.nullable(),
  }),
  moderation: z.object({
    strikes: z.number(),
    frozen: z.boolean(),
    against: reportCounts,
    filed: reportCounts,
    recentAgainst: z.array(auditReport),
    recentFiled: z.array(auditReport),
  }),
  people: z.object({
    following: z.number(),
    followers: z.number(),
    friends: z.number(),
    blocking: z.number(),
    blockedBy: z.number(),
  }),
  tables: z.array(
    z.object({
      id: idSchema,
      name: z.string(),
      emoji: z.string(),
      role: z.enum(["HOST", "MEMBER"]),
      joinedAt: at,
      archived: z.boolean(),
      members: z.number(),
      messages: z.number(),
      polls: z.number(),
      betsProposed: z.number(),
      picks: z.number(),
      netCents: z.number(),
    }),
  ),
  archivedTables: z.number(),
  betting: z.object({
    proposed: z.number(),
    picks: z.number(),
    pending: z.number(),
    decided: winLoss,
    washed: z.number(),
    fourFun: winLoss,
    winRate: z.number().nullable(),
    netCents: z.number(),
    biggestWinCents: z.number().nullable(),
    biggestLossCents: z.number().nullable(),
    byMode: z.array(
      z.object({
        mode: z.string(),
        won: z.number(),
        lost: z.number(),
        washed: z.number(),
        open: z.number(),
      }),
    ),
  }),
  money: z.object({
    settles: z.number(),
    paidCents: z.number(),
    collectedCents: z.number(),
    sendsRejectedByOthers: z.number(),
    rejectedTheirSends: z.number(),
    pendingRejectWindow: z.number(),
    debtCapCents: z.number().nullable(),
  }),
  activity: z.array(
    z.object({
      date,
      messages: z.number(),
      picks: z.number(),
      bets: z.number(),
      settles: z.number(),
    }),
  ),
});
export type UserAudit = z.infer<typeof auditSchema>;
export type AuditReport = z.infer<typeof auditReport>;

const activityItem = z.object({
  kind: z.enum(["message", "poll", "bet"]),
  id: idSchema,
  tableId: idSchema,
  tableName: z.string(),
  text: z.string(),
  options: z.array(z.string()).nullable(),
  mode: z.string().nullable(),
  status: z.string().nullable(),
  wagerCents: z.number().nullable(),
  createdAt: at,
});
export type ActivityItem = z.infer<typeof activityItem>;
export type ActivityKind = ActivityItem["kind"];

/** A player's audit. Opening it is recorded in the admin audit log. */
export async function getUserAudit(userId: string, signal?: AbortSignal): Promise<UserAudit> {
  return read(
    await request(`/admin/users/${encodeURIComponent(userId)}/audit`, options(signal)),
    auditSchema,
  );
}

/** What a player wrote, newest first, 30 at a time. A search is recorded in the admin audit log. */
export async function listUserActivity(
  userId: string,
  filter: { kind: ActivityKind | null; query: string },
  cursor: string | null,
  signal?: AbortSignal,
): Promise<AdminPage<ActivityItem>> {
  const params = new URLSearchParams();
  if (filter.kind) params.set("kind", filter.kind);
  if (filter.query.trim()) params.set("q", filter.query.trim());
  const query = params.toString();
  return read(
    await request(
      withCursor(
        `/admin/users/${encodeURIComponent(userId)}/activity${query ? `?${query}` : ""}`,
        cursor,
      ),
      options(signal),
    ),
    z.object({ items: z.array(activityItem).max(30), nextCursor: z.string().min(1).nullable() }),
  );
}

const logEntry = z.object({
  id: idSchema,
  adminId: idSchema,
  adminName: z.string().nullable(),
  adminHue: z.number().int().min(1).max(5).nullable(),
  action: z.enum(["view_user", "search_activity"]),
  subjectId: idSchema,
  subjectName: z.string().nullable(),
  subjectHue: z.number().int().min(1).max(5).nullable(),
  query: z.string().nullable(),
  at,
});
export type AdminLogEntry = z.infer<typeof logEntry>;

/** The admin audit log, newest first; with `subject`, only entries about that player. */
export async function listAuditLog(
  cursor: string | null,
  signal?: AbortSignal,
  subject?: string,
): Promise<AdminPage<AdminLogEntry>> {
  const path = subject
    ? `/admin/audit-log?subject=${encodeURIComponent(subject)}`
    : "/admin/audit-log";
  return read(await request(withCursor(path, cursor), options(signal)), page(logEntry));
}

// ---- Feedback ---------------------------------------------------------------------------------

export const FEEDBACK_KINDS = ["mode", "perk", "gif", "bug", "other"] as const;
export type FeedbackKind = (typeof FEEDBACK_KINDS)[number];

const feedbackItem = z.object({
  id: idSchema,
  userId: idSchema,
  username: z.string().nullable(),
  hue: z.number().int().min(1).max(5).nullable(),
  kind: z.enum(FEEDBACK_KINDS),
  text: z.string(),
  link: z.string().nullable(),
  status: z.enum(["NEW", "DONE"]),
  adminNote: z.string().nullable(),
  handledBy: z.string().nullable(),
  handledAt: at.nullable(),
  createdAt: at,
});
export type FeedbackItem = z.infer<typeof feedbackItem>;
const feedbackPage = z.object({
  items: z.array(feedbackItem).max(50),
  nextCursor: z.string().min(1).nullable(),
  counts: z.object({ open: z.number(), done: z.number() }),
});
export type FeedbackPage = z.infer<typeof feedbackPage>;

export async function listFeedback(
  filter: { status: "new" | "done" | "all"; kind: FeedbackKind | null },
  cursor: string | null,
  signal?: AbortSignal,
): Promise<FeedbackPage> {
  const params = new URLSearchParams({ status: filter.status });
  if (filter.kind) params.set("kind", filter.kind);
  return read(
    await request(withCursor(`/admin/feedback?${params}`, cursor), options(signal)),
    feedbackPage,
  );
}

/** Marks feedback Done or New with a private note. Idempotent, so a retry is harmless. */
export async function updateFeedback(id: string, status: "NEW" | "DONE", note: string) {
  await request(`/admin/feedback/${encodeURIComponent(id)}`, {
    method: "PUT",
    headers: { "X-CSRF-TOKEN": await csrf(), "Content-Type": "application/json" },
    body: JSON.stringify({ status, note: note.trim() || null }),
  });
}

// ---- Performance ------------------------------------------------------------------------------

const stats = z.object({
  requests: z.number(),
  serverErrors: z.number(),
  clientErrors: z.number(),
  rateLimited: z.number(),
  avgMs: z.number().nullable(),
  p50: z.number().nullable(),
  p95: z.number().nullable(),
  p99: z.number().nullable(),
  maxMs: z.number(),
});
export type PerfStats = z.infer<typeof stats>;
export const PERF_WINDOWS = ["1h", "6h", "24h", "7d"] as const;
export type PerfWindow = (typeof PERF_WINDOWS)[number];
export type PerfSource = "server" | "client";

const perfReport = z.object({
  window: z.enum(PERF_WINDOWS),
  source: z.enum(["server", "client"]),
  from: at,
  to: at,
  stepSeconds: z.number(),
  method: z.string().nullable(),
  route: z.string().nullable(),
  totals: stats,
  series: z.array(z.object({ at, stats })),
  routes: z.array(z.object({ method: z.string(), route: z.string(), stats })),
});
export type PerfReport = z.infer<typeof perfReport>;

export async function getPerf(
  filter: {
    window: PerfWindow;
    source: PerfSource;
    route: { method: string; route: string } | null;
  },
  signal?: AbortSignal,
): Promise<PerfReport> {
  const params = new URLSearchParams({ window: filter.window, source: filter.source });
  if (filter.route) {
    params.set("method", filter.route.method);
    params.set("route", filter.route.route);
  }
  return read(await request(`/admin/perf?${params}`, options(signal)), perfReport);
}

const perfLive = z.object({
  checkedAt: at,
  startedAt: at,
  uptimeSeconds: z.number(),
  host: z.string().nullable(),
  version: z.string().nullable(),
  database: z.object({
    active: z.number().nullable(),
    idle: z.number().nullable(),
    waiting: z.number().nullable(),
    max: z.number().nullable(),
  }),
  jvm: z.object({
    heapUsedBytes: z.number(),
    heapMaxBytes: z.number(),
    threads: z.number(),
    processCpu: z.number().nullable(),
    processors: z.number(),
    javaVersion: z.string(),
  }),
  outbox: z.object({ pending: z.number(), oldestPendingSeconds: z.number().nullable() }),
  feeds: z.array(
    z.object({
      league: z.string(),
      lastSuccessAt: at.nullable(),
      lastFailureAt: at.nullable(),
      lastFailure: z.string().nullable(),
    }),
  ),
});
export type PerfLive = z.infer<typeof perfLive>;

export async function getPerfLive(signal?: AbortSignal): Promise<PerfLive> {
  return read(await request("/admin/perf/live", options(signal)), perfLive);
}

const dependency = z.enum(["UP", "DOWN", "OUT_OF_SERVICE", "UNKNOWN"]);
const health = z.object({
  checkedAt: at,
  postgres: dependency,
  redis: dependency,
  kafka: z.literal("NOT_CHECKED"),
});
export type AdminHealth = z.infer<typeof health>;

export async function getAdminHealth(signal?: AbortSignal): Promise<AdminHealth> {
  return read(await request("/admin/health", options(signal)), health);
}

// ---- Sports -----------------------------------------------------------------------------------

export const LEAGUES = ["NBA", "NFL"] as const;
export type League = (typeof LEAGUES)[number];
const gameStatus = z.enum(["SCHEDULED", "LIVE", "FINAL", "POSTPONED"]);

const sportsLeague = z.object({
  league: z.enum(LEAGUES),
  games: z.number(),
  live: z.number(),
  firstDay: date.nullable(),
  lastDay: date.nullable(),
  lastSuccessAt: at.nullable(),
  lastFailureAt: at.nullable(),
  lastFailure: z.string().nullable(),
});
export type SportsLeague = z.infer<typeof sportsLeague>;
const sportsDay = z.object({
  date,
  games: z.number(),
  live: z.number(),
  finals: z.number(),
});
export type SportsDay = z.infer<typeof sportsDay>;
const team = z.object({
  abbreviation: z.string(),
  name: z.string(),
  city: z.string(),
  score: z.number().nullable(),
  periods: z.array(z.number()),
});
const sportsGame = z.object({
  id: z.string(),
  league: z.enum(LEAGUES),
  providerId: z.string(),
  status: gameStatus,
  startsAt: at,
  period: z.number().nullable(),
  clock: z.string().nullable(),
  away: team,
  home: team,
  observedAt: at,
  snapshots: z.number(),
  bets: z.number(),
});
export type SportsGame = z.infer<typeof sportsGame>;
const json: z.ZodType<Record<string, unknown>> = z.record(z.string(), z.unknown());
const sportsPlayer = z.object({
  id: z.string(),
  name: z.string(),
  position: z.string().nullable(),
  team: z.string().nullable(),
  played: z.boolean(),
  stats: json,
  observedAt: at,
  snapshots: z.number(),
});
export type SportsPlayer = z.infer<typeof sportsPlayer>;
const gameDetail = z.object({
  game: sportsGame,
  firstObservedAt: at.nullable(),
  players: z.array(sportsPlayer),
  bets: z.array(z.object({ mode: z.string(), status: z.string(), count: z.number() })),
});
export type SportsGameDetail = z.infer<typeof gameDetail>;
const gameSnapshot = z.object({
  observedAt: at,
  status: gameStatus,
  providerState: z.string().nullable(),
  period: z.number().nullable(),
  clock: z.string().nullable(),
  homeScore: z.number().nullable(),
  awayScore: z.number().nullable(),
  homePeriods: z.array(z.number()),
  awayPeriods: z.array(z.number()),
});
export type GameSnapshot = z.infer<typeof gameSnapshot>;
const statSnapshot = z.object({ observedAt: at, played: z.boolean(), stats: json });
export type StatSnapshot = z.infer<typeof statSnapshot>;

export async function listSportsLeagues(signal?: AbortSignal) {
  return read(await request("/admin/sports/leagues", options(signal)), z.array(sportsLeague));
}

export async function listSportsDays(league: League, signal?: AbortSignal) {
  return read(
    await request(`/admin/sports/days?league=${league}`, options(signal)),
    z.array(sportsDay),
  );
}

export async function listSportsGames(league: League, day: string, signal?: AbortSignal) {
  return read(
    await request(
      `/admin/sports/games?league=${league}&date=${encodeURIComponent(day)}`,
      options(signal),
    ),
    z.array(sportsGame),
  );
}

export async function getSportsGame(gameId: string, signal?: AbortSignal) {
  return read(
    await request(`/admin/sports/games/${encodeURIComponent(gameId)}`, options(signal)),
    gameDetail,
  );
}

export async function listGameSnapshots(gameId: string, signal?: AbortSignal) {
  return read(
    await request(`/admin/sports/games/${encodeURIComponent(gameId)}/snapshots`, options(signal)),
    z.array(gameSnapshot),
  );
}

export async function listPlayerSnapshots(gameId: string, playerId: string, signal?: AbortSignal) {
  return read(
    await request(
      `/admin/sports/games/${encodeURIComponent(gameId)}/players/${encodeURIComponent(playerId)}/snapshots`,
      options(signal),
    ),
    z.array(statSnapshot),
  );
}

// ---- Sign-in lockout --------------------------------------------------------------------------

const codeCounter = z.object({
  wrongCodes: z.number().int().min(0),
  locked: z.boolean(),
  resetsAt: at.nullable(),
});
const signInLockouts = z.object({
  playerLimit: z.number().int().min(1),
  addressLimit: z.number().int().min(1),
  address: codeCounter,
  players: z.array(
    codeCounter.extend({
      id: idSchema,
      username: z.string().nullable(),
      hue: z.number().int().min(1).max(5).nullable(),
    }),
  ),
});
export type SignInLockouts = z.infer<typeof signInLockouts>;
export type PlayerLockout = SignInLockouts["players"][number];

/** Players with wrong sign-in codes this window (locked first), and the shared address backstop. */
export async function getSignInLockouts(signal?: AbortSignal): Promise<SignInLockouts> {
  return read(await request("/admin/sign-in-lockout", options(signal)), signInLockouts);
}

/** Unlocks one player (`playerId`), or every counter when omitted. */
export async function clearSignInLockout(playerId?: string) {
  await request(
    playerId
      ? `/admin/sign-in-lockout/players/${encodeURIComponent(playerId)}`
      : "/admin/sign-in-lockout",
    { method: "DELETE", headers: { "X-CSRF-TOKEN": await csrf() } },
  );
}

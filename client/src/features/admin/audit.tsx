import { useState, type ReactNode } from "react";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { ArrowLeft, Check, ChevronDown, Copy, Eye, RefreshCw, Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { LoadMore } from "@/components/load-more";
import { LoadingList } from "@/components/loading";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { backendErrorMessage } from "@/lib/api-client";
import { userKey } from "@/lib/query-keys";
import type { User } from "@/features/people";
import { cn } from "@/lib/utils";
import { Chart, SplitBar, Stat } from "./charts";
import {
  getUserAudit,
  listAuditLog,
  listUserActivity,
  type ActivityItem,
  type ActivityKind,
  type AuditReport,
  type UserAudit,
} from "./dashboard-api";
import { ago, count, modeName, percent, points, signedPoints, stamp } from "./format";
import { useDebounced } from "@/lib/use-debounced";
import { NameAvatar, NameTag } from "./avatar";
import { Chip } from "./players";

const REASONS: Record<AuditReport["reason"], string> = {
  harassment: "Harassment",
  settlement: "Settlement dispute",
  spam: "Spam",
  offensive: "Offensive",
  other: "Other",
};

/**
 * One player's audit: who they are, how they play, where their points went, how they've been reported, and a
 * search over everything they wrote. Read-only; strikes and reports are handled under Reports. Opening it is
 * recorded in the admin audit log, and the header says so.
 */
export function UserAuditView({
  user,
  playerId,
  onBack,
  onOpenReports,
  onOpenPlayer,
}: {
  user: User;
  playerId: string;
  onBack: () => void;
  onOpenReports: () => void;
  onOpenPlayer: (id: string) => void;
}) {
  const audit = useQuery({
    queryKey: userKey(user.id, "admin-audit", playerId),
    queryFn: ({ signal }) => getUserAudit(playerId, signal),
    retry: false,
    refetchOnWindowFocus: false,
  });

  if (audit.isPending)
    return <LoadingList label="Loading the audit" rows={6} rowClassName="h-24" />;
  if (audit.isError)
    return (
      <div className="space-y-3 rounded-lg border bg-surface p-4">
        <p role="alert">{backendErrorMessage(audit.error)}</p>
        <Button variant="outline" size="sm" onClick={onBack}>
          <ArrowLeft /> Back to players
        </Button>
      </div>
    );
  const data = audit.data;
  return (
    <article className="space-y-6" aria-labelledby="audit-heading">
      <AuditHeader
        audit={data}
        onBack={onBack}
        refreshing={audit.isFetching}
        onRefresh={() => void audit.refetch()}
      />
      <Headline audit={data} />
      <div className="grid gap-6 xl:grid-cols-2">
        <Panel title="Activity · last 30 days">
          <ActivityCharts audit={data} />
        </Panel>
        <Panel title="Record by mode">
          <ModeTable audit={data} />
        </Panel>
      </div>
      <div className="grid gap-6 xl:grid-cols-3">
        <Panel title="Settle-ups">
          <MoneyList audit={data} />
        </Panel>
        <Panel
          title="Moderation"
          action={
            <Button size="sm" variant="ghost" onClick={onOpenReports}>
              Open Reports
            </Button>
          }
        >
          <ModerationSummary audit={data} onOpenPlayer={onOpenPlayer} />
        </Panel>
        <Panel title="People">
          <PeopleList audit={data} />
        </Panel>
      </div>
      <Panel title={`Tables · ${data.tables.length} current, ${data.archivedTables} deleted`}>
        <TablesTable audit={data} />
      </Panel>
      <AuditedBy user={user} playerId={playerId} />
      <Panel title="What they wrote">
        <WritingSearch user={user} playerId={playerId} />
      </Panel>
    </article>
  );
}

function Panel({
  title,
  action,
  children,
}: {
  title: string;
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section className="min-w-0 space-y-3 rounded-xl border bg-surface/60 p-4">
      <div className="flex items-center justify-between gap-2">
        <h3 className="font-mono text-xs font-bold uppercase tracking-[0.2em] text-muted-foreground">
          {title}
        </h3>
        {action}
      </div>
      {children}
    </section>
  );
}

function CopyId({ id }: { id: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      type="button"
      className="inline-flex items-center gap-1 rounded font-mono text-[11px] text-muted-foreground hover:text-foreground"
      title="Copy the player's id"
      onClick={() =>
        void navigator.clipboard?.writeText(id).then(() => {
          setCopied(true);
          setTimeout(() => setCopied(false), 1500);
        })
      }
    >
      {copied ? <Check className="size-3" /> : <Copy className="size-3" />}
      {id}
    </button>
  );
}

function AuditHeader({
  audit,
  onBack,
  refreshing,
  onRefresh,
}: {
  audit: UserAudit;
  onBack: () => void;
  refreshing: boolean;
  onRefresh: () => void;
}) {
  const { profile, moderation } = audit;
  return (
    <header className="space-y-3">
      <Button variant="ghost" size="sm" className="-ml-2" onClick={onBack}>
        <ArrowLeft /> Close audit
      </Button>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 items-center gap-4">
          <NameAvatar name={profile.username} hue={profile.hue} size="lg" />
          <div className="min-w-0 space-y-1">
            <div className="flex flex-wrap items-center gap-2">
              <h2
                id="audit-heading"
                className="font-display text-3xl font-bold uppercase leading-none"
              >
                {profile.username ?? "Deleted player"}
              </h2>
              {profile.status === "FROZEN" && <Chip tone="bad">Frozen</Chip>}
              {profile.status === "DELETED" && <Chip>Deleted</Chip>}
              {moderation.strikes > 0 && <Chip tone="warn">{moderation.strikes} of 3 strikes</Chip>}
              {!profile.lastSignInAt && profile.status !== "DELETED" && (
                <Chip>Never signed in</Chip>
              )}
            </div>
            <CopyId id={profile.id} />
            <p className="text-xs text-muted-foreground">
              Joined {stamp(profile.createdAt)} · last sign-in {ago(profile.lastSignInAt)} ·{" "}
              {profile.activeSessions} active session{profile.activeSessions === 1 ? "" : "s"}
            </p>
          </div>
        </div>
        <div className="flex flex-col items-end gap-1">
          <Button variant="outline" size="sm" disabled={refreshing} onClick={onRefresh}>
            <RefreshCw className={cn(refreshing && "animate-spin")} /> Refresh
          </Button>
          <p className="flex items-center gap-1 text-[11px] text-muted-foreground">
            <Eye className="size-3" /> This view is logged · as of {stamp(Date.now())}
          </p>
        </div>
      </div>
    </header>
  );
}

function Headline({ audit }: { audit: UserAudit }) {
  const { betting, money, moderation, tables } = audit;
  const decided = betting.decided.won + betting.decided.lost;
  return (
    <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
      <Stat
        label="Win rate"
        value={percent(betting.winRate)}
        hint={`${betting.decided.won}–${betting.decided.lost} on ${decided} decided`}
      />
      <Stat
        label="Net points"
        value={signedPoints(betting.netCents)}
        tone={betting.netCents > 0 ? "good" : betting.netCents < 0 ? "bad" : undefined}
        hint={`${count(betting.picks)} picks · ${count(betting.proposed)} proposed`}
      />
      <Stat
        label="Settled"
        value={points(money.paidCents + money.collectedCents)}
        hint={`paid ${points(money.paidCents)} · got ${points(money.collectedCents)}`}
      />
      <Stat
        label="Rejected sends"
        value={money.sendsRejectedByOthers + money.rejectedTheirSends}
        tone={money.sendsRejectedByOthers + money.rejectedTheirSends > 0 ? "warn" : undefined}
        hint={`${money.sendsRejectedByOthers} theirs · ${money.rejectedTheirSends} by them`}
      />
      <Stat
        label="Reports against"
        value={moderation.against.open + moderation.against.resolved + moderation.against.dismissed}
        tone={moderation.against.open > 0 ? "warn" : undefined}
        hint={`${moderation.against.open} open · ${moderation.against.resolved} upheld`}
      />
      <Stat
        label="Tables"
        value={tables.length}
        hint={`${tables.filter((t) => t.role === "HOST").length} hosting`}
      />
    </dl>
  );
}

const ACTIVITY = [
  ["messages", "Messages"],
  ["picks", "Picks"],
  ["bets", "Bets proposed"],
  ["settles", "Settles"],
] as const;

/** Small multiples, one per kind, so each keeps its own scale without a second axis. */
function ActivityCharts({ audit }: { audit: UserAudit }) {
  const labels = audit.activity.map((day) => day.date.slice(5));
  return (
    <div className="grid gap-4 sm:grid-cols-2">
      {ACTIVITY.map(([key, label]) => {
        const total = audit.activity.reduce((sum, day) => sum + day[key], 0);
        return (
          <div key={key} className="min-w-0">
            <p className="flex items-baseline justify-between text-xs">
              <span className="font-semibold">{label}</span>
              <span className="font-mono text-muted-foreground">{count(total)} total</span>
            </p>
            <Chart
              kind="stack"
              height={110}
              ticks={4}
              title={`${label} per day over the last 30 days, ${total} total`}
              labels={labels}
              series={[
                {
                  id: key,
                  label,
                  color: "var(--viz-1)",
                  values: audit.activity.map((d) => d[key]),
                },
              ]}
            />
          </div>
        );
      })}
    </div>
  );
}

function ModeTable({ audit }: { audit: UserAudit }) {
  const { betting } = audit;
  if (betting.byMode.length === 0)
    return <p className="text-sm text-muted-foreground">No picks yet.</p>;
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
        <Legend color="var(--win)" label="Won" />
        <Legend color="var(--loss)" label="Lost" />
        <Legend color="var(--status-washed)" label="Washed" />
        <Legend color="var(--viz-1)" label="Locked, waiting" />
      </div>
      <table className="w-full text-sm">
        <thead className="text-left font-mono text-[10px] uppercase text-muted-foreground">
          <tr>
            <th className="py-1 font-normal">Mode</th>
            <th className="hidden w-1/3 py-1 font-normal sm:table-cell">Split</th>
            <th className="py-1 text-right font-normal">W–L</th>
            <th className="py-1 text-right font-normal">Win %</th>
            <th className="py-1 text-right font-normal">Washed</th>
            <th className="py-1 text-right font-normal">Locked</th>
          </tr>
        </thead>
        <tbody className="divide-y">
          {betting.byMode.map((row) => (
            <tr key={row.mode}>
              <td className="py-1.5 pr-2">{modeName(row.mode)}</td>
              <td className="hidden py-1.5 pr-2 sm:table-cell">
                <SplitBar
                  label={modeName(row.mode)}
                  parts={[
                    { label: "won", value: row.won, color: "var(--win)" },
                    { label: "lost", value: row.lost, color: "var(--loss)" },
                    { label: "washed", value: row.washed, color: "var(--status-washed)" },
                    { label: "locked", value: row.open, color: "var(--viz-1)" },
                  ]}
                />
              </td>
              <td className="whitespace-nowrap py-1.5 text-right font-mono">
                {row.won}–{row.lost}
              </td>
              <td className="py-1.5 text-right font-mono">
                {percent(row.won + row.lost === 0 ? null : row.won / (row.won + row.lost))}
              </td>
              <td className="py-1.5 text-right font-mono">{row.washed}</td>
              <td className="py-1.5 text-right font-mono">{row.open}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="text-xs text-muted-foreground">
        Win rate counts settled bets with points; washes ({betting.washed}) and 4fun (
        {betting.fourFun.won}–{betting.fourFun.lost}) are left out. Bets still taking picks aren't
        counted anywhere on this page until they lock. Biggest win{" "}
        {betting.biggestWinCents === null ? "–" : signedPoints(betting.biggestWinCents)}, biggest
        loss {betting.biggestLossCents === null ? "–" : points(betting.biggestLossCents)}.
      </p>
    </div>
  );
}

function Legend({ color, label }: { color: string; label: string }) {
  return (
    <span className="inline-flex items-center gap-1.5">
      <span aria-hidden className="size-2.5 rounded-sm" style={{ background: color }} />
      {label}
    </span>
  );
}

function Rows({ rows }: { rows: [string, ReactNode][] }) {
  return (
    <dl className="divide-y text-sm">
      {rows.map(([label, value]) => (
        <div key={label} className="flex items-baseline justify-between gap-3 py-1.5">
          <dt className="text-muted-foreground">{label}</dt>
          <dd className="font-mono tabular-nums">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

function MoneyList({ audit }: { audit: UserAudit }) {
  const { money } = audit;
  return (
    <Rows
      rows={[
        ["Settles", count(money.settles)],
        ["Settled while down", points(money.paidCents)],
        ["Settled while up", points(money.collectedCents)],
        ["Their settles rejected", money.sendsRejectedByOthers],
        ["Settles they rejected", money.rejectedTheirSends],
        ["In the 48 h reject window", money.pendingRejectWindow],
        ["Points limit", money.debtCapCents === null ? "Off" : points(money.debtCapCents)],
      ]}
    />
  );
}

function ModerationSummary({
  audit,
  onOpenPlayer,
}: {
  audit: UserAudit;
  onOpenPlayer: (id: string) => void;
}) {
  const { moderation } = audit;
  const [view, setView] = useState<"against" | "filed">("against");
  const reports = view === "against" ? moderation.recentAgainst : moderation.recentFiled;
  const counts = view === "against" ? moderation.against : moderation.filed;
  return (
    <div className="space-y-3">
      <SegmentedTabs
        size="sm"
        ariaLabel="Which reports"
        tabs={[
          { id: "against", label: "Against them" },
          { id: "filed", label: "By them" },
        ]}
        value={view}
        onChange={setView}
      />
      <p className="text-xs text-muted-foreground">
        {counts.open} open · {counts.resolved} upheld · {counts.dismissed} dismissed. Strikes:{" "}
        {moderation.strikes} of 3.
      </p>
      {reports.length === 0 ? (
        <p className="text-sm text-muted-foreground">None.</p>
      ) : (
        <ul className="space-y-2">
          {reports.map((report) => (
            <li key={report.id} className="space-y-1 rounded-md border p-2 text-sm">
              <p className="flex flex-wrap items-center gap-1.5">
                <span className="font-semibold">{REASONS[report.reason]}</span>
                <Chip tone={report.status === "OPEN" ? "warn" : "muted"}>{report.status}</Chip>
                <span className="text-xs text-muted-foreground">{ago(report.createdAt)}</span>
              </p>
              <p className="text-xs text-muted-foreground">
                {view === "against" ? "By " : "Against "}
                <NameTag
                  name={report.otherName}
                  hue={report.otherHue}
                  className="text-foreground"
                  onOpen={() => onOpenPlayer(report.otherId)}
                />
                {report.tableName && ` at ${report.tableName}`}
              </p>
              {report.messageSnapshot && (
                <blockquote className="border-l-2 pl-2 text-xs text-muted-foreground">
                  “{report.messageSnapshot}”
                </blockquote>
              )}
              {report.note && <p className="text-xs">Note: {report.note}</p>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function PeopleList({ audit }: { audit: UserAudit }) {
  const { people } = audit;
  return (
    <Rows
      rows={[
        ["Friends", people.friends],
        ["Following", people.following],
        ["Followers", people.followers],
        ["Blocking", people.blocking],
        ["Blocked by", people.blockedBy],
      ]}
    />
  );
}

function TablesTable({ audit }: { audit: UserAudit }) {
  if (audit.tables.length === 0)
    return <p className="text-sm text-muted-foreground">Not at any table.</p>;
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[640px] text-sm">
        <thead className="text-left font-mono text-[10px] uppercase text-muted-foreground">
          <tr>
            <th className="py-1 font-normal">Table</th>
            <th className="py-1 font-normal">Role</th>
            <th className="py-1 font-normal">Joined</th>
            <th className="py-1 text-right font-normal">Members</th>
            <th className="py-1 text-right font-normal">Messages</th>
            <th className="py-1 text-right font-normal">Polls</th>
            <th className="py-1 text-right font-normal">Bets</th>
            <th className="py-1 text-right font-normal">Picks</th>
            <th className="py-1 text-right font-normal">Net</th>
          </tr>
        </thead>
        <tbody className="divide-y">
          {audit.tables.map((table) => (
            <tr key={table.id}>
              <td className="py-1.5 pr-2">
                <span className="mr-1.5">{table.emoji}</span>
                {table.name}
                {table.archived && <Chip>Archived</Chip>}
              </td>
              <td className="py-1.5 pr-2 text-xs">{table.role === "HOST" ? "Host" : "Member"}</td>
              <td className="py-1.5 pr-2 text-xs text-muted-foreground">{ago(table.joinedAt)}</td>
              <td className="py-1.5 text-right font-mono">{table.members}</td>
              <td className="py-1.5 text-right font-mono">{table.messages}</td>
              <td className="py-1.5 text-right font-mono">{table.polls}</td>
              <td className="py-1.5 text-right font-mono">{table.betsProposed}</td>
              <td className="py-1.5 text-right font-mono">{table.picks}</td>
              <td
                className={cn(
                  "py-1.5 text-right font-mono",
                  table.netCents > 0 && "text-win",
                  table.netCents < 0 && "text-loss",
                )}
              >
                {signedPoints(table.netCents)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

const KIND_TABS = [
  { id: "all", label: "All" },
  { id: "message", label: "Messages" },
  { id: "poll", label: "Polls" },
  { id: "bet", label: "Bets" },
] as const;

/** Keyword search over everything the player wrote. Each search is logged on the server. */
function WritingSearch({ user, playerId }: { user: User; playerId: string }) {
  const [text, setText] = useState("");
  const [kind, setKind] = useState<"all" | ActivityKind>("all");
  const query = useDebounced(text.trim(), 400);
  const items = useInfiniteQuery({
    queryKey: userKey(user.id, "admin-activity", playerId, kind, query),
    queryFn: ({ pageParam, signal }) =>
      listUserActivity(playerId, { kind: kind === "all" ? null : kind, query }, pageParam, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
    retry: false,
    refetchOnWindowFocus: false,
  });
  const list = items.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative min-w-48 flex-1">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            aria-label="Search what they wrote"
            placeholder="Keyword in messages, polls and bets"
            className="pl-8"
            value={text}
            maxLength={100}
            onChange={(event) => setText(event.target.value)}
          />
        </div>
        <SegmentedTabs
          size="sm"
          ariaLabel="Kind"
          className="w-full sm:w-96"
          tabs={[...KIND_TABS]}
          value={kind}
          onChange={setKind}
        />
      </div>
      {items.isPending ? (
        <LoadingList label="Searching" rows={3} rowClassName="h-12" />
      ) : items.isError ? (
        <p role="alert">{backendErrorMessage(items.error)}</p>
      ) : list.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          {query ? `Nothing contains “${query}”.` : "Nothing written yet."}
        </p>
      ) : (
        <ul className="divide-y rounded-lg border">
          {list.map((item) => (
            <WritingRow key={item.id} item={item} highlight={query} />
          ))}
          {items.hasNextPage && (
            <li className="p-3">
              <LoadMore
                loading={items.isFetchingNextPage}
                onLoad={() => void items.fetchNextPage()}
              />
            </li>
          )}
        </ul>
      )}
    </div>
  );
}

function WritingRow({ item, highlight }: { item: ActivityItem; highlight: string }) {
  return (
    <li className="space-y-1 px-3 py-2">
      <p className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
        <Chip>{item.kind === "bet" ? modeName(item.mode ?? "bet") : item.kind}</Chip>
        {item.status && <Chip>{item.status}</Chip>}
        <span>at {item.tableName}</span>
        <span title={stamp(item.createdAt)}>· {ago(item.createdAt)}</span>
        {item.wagerCents !== null && <span>· wager {points(item.wagerCents)}</span>}
      </p>
      <p className="break-words text-sm">
        <Highlighted text={item.text} term={highlight} />
      </p>
      {item.options && (
        <p className="text-xs text-muted-foreground">
          {item.options.map((option, i) => (
            <span key={i}>
              {i > 0 && " · "}
              <Highlighted text={option} term={highlight} />
            </span>
          ))}
        </p>
      )}
    </li>
  );
}

function Highlighted({ text, term }: { text: string; term: string }) {
  if (!term) return <>{text}</>;
  const lower = text.toLowerCase();
  const needle = term.toLowerCase();
  const parts: ReactNode[] = [];
  let from = 0;
  for (let at = lower.indexOf(needle); at >= 0; at = lower.indexOf(needle, from)) {
    parts.push(text.slice(from, at));
    parts.push(
      <mark key={at} className="rounded bg-[var(--viz-warning)]/30 text-foreground">
        {text.slice(at, at + needle.length)}
      </mark>,
    );
    from = at + needle.length;
  }
  parts.push(text.slice(from));
  return <>{parts}</>;
}

/**
 * Who audited this player, collapsed by default (it can run long and is rarely the point); the list only loads once
 * opened.
 */
function AuditedBy({ user, playerId }: { user: User; playerId: string }) {
  const [open, setOpen] = useState(false);
  return (
    <section className="min-w-0 rounded-xl border bg-surface/60 p-4">
      <button
        type="button"
        className="flex w-full items-center justify-between gap-2 text-left"
        aria-expanded={open}
        aria-controls="audited-by"
        onClick={() => setOpen((current) => !current)}
      >
        <h3 className="font-mono text-xs font-bold uppercase tracking-[0.2em] text-muted-foreground">
          Audited by
        </h3>
        <ChevronDown
          className={cn("size-4 text-muted-foreground transition-transform", open && "rotate-180")}
        />
      </button>
      {open && (
        <div id="audited-by" className="mt-3">
          <ViewsList user={user} playerId={playerId} />
        </div>
      )}
    </section>
  );
}

/** Every admin who opened this audit or searched in it, newest first, a page at a time. */
function ViewsList({ user, playerId }: { user: User; playerId: string }) {
  const views = useInfiniteQuery({
    queryKey: userKey(user.id, "admin-audit-log", "subject", playerId),
    queryFn: ({ pageParam, signal }) => listAuditLog(pageParam, signal, playerId),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
    retry: false,
    refetchOnWindowFocus: false,
  });
  if (views.isPending)
    return <LoadingList label="Loading who audited them" rows={2} rowClassName="h-8" />;
  if (views.isError) return <p role="alert">{backendErrorMessage(views.error)}</p>;
  const list = views.data.pages.flatMap((page) => page.items);
  if (list.length === 0) return <p className="text-sm text-muted-foreground">No one yet.</p>;
  return (
    <ul className="max-h-72 divide-y overflow-y-auto pr-1 text-sm">
      {list.map((view) => (
        <li key={view.id} className="flex flex-wrap justify-between gap-2 py-1.5">
          <span>
            <NameTag name={view.adminName} hue={view.adminHue} fallback="A deleted admin" />{" "}
            {view.action === "view_user" ? "opened the audit" : `searched “${view.query ?? ""}”`}
          </span>
          <span className="text-xs text-muted-foreground">{stamp(view.at)}</span>
        </li>
      ))}
      {views.hasNextPage && (
        <li className="py-2">
          <LoadMore loading={views.isFetchingNextPage} onLoad={() => void views.fetchNextPage()} />
        </li>
      )}
    </ul>
  );
}

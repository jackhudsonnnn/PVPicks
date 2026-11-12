import { useMemo, useState } from "react";
import { Ban, Flag, MessageSquare, ShieldAlert, Snowflake, Ticket } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { ScrollContainer } from "@/components/scroll-container";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { cn } from "@/lib/utils";
import { PLAYER_ACCOUNTS, type PlayerAccountId } from "@/features/auth/demo-accounts";
import { PeopleProvider } from "@/features/social/people";
import { sampleWorlds, useAdminFlags } from "../admin-data";
import { formatPts } from "@/features/bets/ledger";
import { getMode } from "@/features/bets/modes";
import { PASS } from "@/features/bets/types";
import { useReports } from "@/features/social/reports-storage";
import { REPORT_REASONS } from "@/features/social/safety";
import { UserAvatar } from "@/features/social/components/user-avatar";
import { auditUser, MAX_STRIKES, type AdminAction } from "../user-audit";
import { Badge, Kpi, Panel } from "./admin-ui";
import { EmptyState } from "@/components/empty-state";
import { SearchInput } from "@/components/search-input";
import { PageHeader } from "@/components/page-header";

/** Search any person in a sample world and review their activity, reports and admin status. */
export function UserAuditPage() {
  const [world, setWorld] = useState<PlayerAccountId>("pro");
  const worlds = useMemo(() => sampleWorlds(), []);
  const w = worlds[world].data;
  const [q, setQ] = useState("");
  const [selected, setSelected] = useState<string>(w.users[0]!.id);
  const { reports } = useReports();
  const { all, act } = useAdminFlags();
  const users = w.users.filter(
    (u) =>
      !q ||
      u.username.toLowerCase().includes(q.toLowerCase()),
  );
  const user = w.users.find((u) => u.id === selected) ?? w.users[0]!;
  const audit = auditUser(user.id, w, reports);
  const flags = all[user.id] ?? {};
  const [note, setNote] = useState("");

  return (
    <PeopleProvider users={w.users}>
      <div className="mx-auto max-w-6xl space-y-6 px-4 py-6 md:px-8 md:py-10">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <PageHeader eyebrow="Audit" title="Users" />
          <SegmentedTabs<PlayerAccountId>
            size="sm"
            ariaLabel="Sample world"
            className="w-full sm:w-auto sm:min-w-80"
            tabs={PLAYER_ACCOUNTS.map((id) => ({ id, label: worlds[id].label }))}
            value={world}
            onChange={(id) => {
              setWorld(id);
              setSelected(worlds[id].data.users[0]!.id);
            }}
          />
        </div>

        <div className="grid gap-4 md:grid-cols-[18rem_1fr]">
          <section className="min-w-0 space-y-3 rounded-2xl border bg-surface p-4">
            <SearchInput
              value={q}
              onChange={setQ}
              label="Search users"
              placeholder="Search username"
              inputClassName="h-9 bg-transparent"
            />
            <ScrollContainer isVisible={false} className="max-h-72 md:max-h-[32rem]">
              <AnimatedList>
                {users.map((u) => {
                  const f = all[u.id];
                  const against = reports.filter((r) => r.targetId === u.id).length;
                  return (
                    <AnimatedItem key={u.id}>
                      <button
                        type="button"
                        onClick={() => setSelected(u.id)}
                        className={cn(
                          "flex w-full items-center gap-3 rounded-lg px-2 py-2 text-left text-sm transition-colors",
                          u.id === user.id ? "bg-surface-raised" : "hover:bg-surface-raised/60",
                        )}
                      >
                        <UserAvatar userId={u.id} size="sm" />
                        <span className="min-w-0 flex-1">
                          <span className="block font-semibold">
                            {u.username}
                          </span>
                        </span>
                        {f?.frozen && (
                          <Snowflake className="size-4 text-chart-2" aria-label="Frozen" />
                        )}
                        {f?.flagged && <Flag className="size-4 text-loss" aria-label="Flagged" />}
                        {against > 0 && (
                          <span className="rounded-full bg-loss/15 px-1.5 font-mono text-[10px] font-bold text-loss">
                            {against}
                          </span>
                        )}
                      </button>
                    </AnimatedItem>
                  );
                })}
              </AnimatedList>
            </ScrollContainer>
          </section>

          <div className="min-w-0 space-y-4">
            <section className="flex flex-wrap items-center gap-3 rounded-2xl border bg-surface p-4">
              <UserAvatar userId={user.id} size="lg" />
              <div className="min-w-0 flex-1">
                <h2 className="font-display text-3xl font-extrabold uppercase leading-none">
                  {user.username}
                </h2>
              </div>
              <div className="flex flex-wrap gap-1.5">
                {flags.frozen && <Badge tone="info">Frozen</Badge>}
                {flags.flagged && <Badge tone="loss">Flagged</Badge>}
                {(flags.strikes ?? 0) > 0 && (
                  <Badge tone="loss">{`${flags.strikes}/${MAX_STRIKES} strikes`}</Badge>
                )}
                {user.deleted && <Badge>Deleted</Badge>}
              </div>
            </section>

            <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
              <Kpi
                icon={Ticket}
                label="Tickets joined"
                value={String(audit.tickets.length)}
                hint={`${audit.proposed} proposed`}
              />
              <Kpi
                icon={Ticket}
                label="Record W-L-Wash"
                value={`${audit.record.win}-${audit.record.loss}-${audit.record.wash}`}
                hint={`${audit.record.pass} no guess`}
              />
              <Kpi
                icon={Ticket}
                label="Net points"
                value={formatPts(audit.net, true)}
                tone={audit.net > 0 ? "win" : audit.net < 0 ? "loss" : undefined}
              />
              <Kpi icon={MessageSquare} label="Chat lines" value={String(audit.chatLines)} />
            </div>

            <Panel
              title="Admin actions"
              hint="Simulated: saved on this device until accounts exist."
            >
              <div className="space-y-3">
                <ActionRow label="Freeze account" hint="Can't pick, propose or chat.">
                  <Switch
                    checked={!!flags.frozen}
                    onCheckedChange={(on) => act(user.id, { type: "freeze", on })}
                  />
                </ActionRow>
                <ActionRow label="Flag for review" hint="Marks the person in this list.">
                  <Switch
                    checked={!!flags.flagged}
                    onCheckedChange={(on) => act(user.id, { type: "flag", on })}
                  />
                </ActionRow>
                <ActionRow
                  label={`Strikes (${flags.strikes ?? 0}/${MAX_STRIKES})`}
                  hint="A third strike freezes the account."
                >
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => act(user.id, { type: "strike" })}
                    >
                      <Ban /> Add
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => act(user.id, { type: "clearStrikes" })}
                    >
                      Clear
                    </Button>
                  </div>
                </ActionRow>
                <form
                  className="flex gap-2"
                  onSubmit={(e) => {
                    e.preventDefault();
                    act(user.id, { type: "note", text: note });
                    setNote("");
                  }}
                >
                  <Input
                    value={note}
                    onChange={(e) => setNote(e.target.value)}
                    placeholder="Add an admin note"
                  />
                  <Button type="submit" size="sm">
                    Save
                  </Button>
                </form>
                {flags.notes?.length ? (
                  <ul className="space-y-1 text-sm text-muted-foreground">
                    {flags.notes.map((n, i) => (
                      <li key={i} className="rounded-lg bg-surface-raised px-3 py-2">
                        {n}
                      </li>
                    ))}
                  </ul>
                ) : null}
              </div>
            </Panel>

            <div className="grid gap-4 lg:grid-cols-2">
              <Panel title="Tables">
                {audit.tables.length ? (
                  <ul className="space-y-1.5 text-sm">
                    {audit.tables.map((t) => (
                      <li key={t.id} className="flex items-center gap-2">
                        <span>{t.emoji}</span>
                        <span className="flex-1">{t.name}</span>
                        {t.host && <Badge tone="win">Host</Badge>}
                      </li>
                    ))}
                  </ul>
                ) : (
                  <EmptyState>Not at any table.</EmptyState>
                )}
              </Panel>
              <Panel
                title="Reports"
                hint={`${audit.reportsAgainst.length} against, ${audit.reportsFiled.length} filed`}
              >
                {audit.reportsAgainst.length ? (
                  <ul className="space-y-1.5 text-sm">
                    {audit.reportsAgainst.map((r) => (
                      <li key={r.id} className="flex items-start gap-2">
                        <ShieldAlert className="mt-0.5 size-4 shrink-0 text-loss" />
                        <span className="flex-1">
                          <span className="font-semibold">
                            {REPORT_REASONS.find((x) => x.id === r.reason)?.label}
                          </span>
                          {r.note && <span className="text-muted-foreground">: {r.note}</span>}
                        </span>
                        <Badge>{r.status}</Badge>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <EmptyState>No reports against them.</EmptyState>
                )}
              </Panel>
            </div>

            <Panel
              title="Ticket history"
              hint="Newest first. Picks shown because admins audit after the fact."
            >
              {audit.tickets.length ? (
                <ScrollContainer isVisible={false} className="max-h-96">
                  <ul className="divide-y text-sm">
                    {audit.tickets.map((b) => {
                      const p = b.participants.find((x) => x.userId === user.id)!;
                      const d = b.deltas?.[user.id];
                      return (
                        <li key={b.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2">
                          <span className="min-w-0 flex-1 basis-48">
                            <span className="block font-semibold">{b.question}</span>
                            <span className="block text-xs text-muted-foreground">
                              {getMode(b.modeId).name}, pick: {p.selection === PASS ? "No guess" : p.selection}
                              {b.forFun ? ", 4fun" : ""}
                            </span>
                          </span>
                          <Badge>{b.status}</Badge>
                          <span
                            className={cn(
                              "scoreboard w-16 text-right",
                              d && d > 0 && "text-win",
                              d && d < 0 && "text-loss",
                            )}
                          >
                            {d === undefined || b.forFun ? "–" : formatPts(d, true)}
                          </span>
                        </li>
                      );
                    })}
                  </ul>
                </ScrollContainer>
              ) : (
                <EmptyState>No tickets.</EmptyState>
              )}
            </Panel>
          </div>
        </div>
      </div>
    </PeopleProvider>
  );
}

function ActionRow({
  label,
  hint,
  children,
}: {
  label: string;
  hint: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-2">
      <div>
        <p className="text-sm font-semibold">{label}</p>
        <p className="text-xs text-muted-foreground">{hint}</p>
      </div>
      {children}
    </div>
  );
}

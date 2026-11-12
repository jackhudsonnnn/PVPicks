import { useEffect, useState } from "react";
import { ChevronRight, Crown, HandCoins, Lock, PenLine, Trash2 } from "lucide-react";
import { useNavigate } from "@tanstack/react-router";
import { Switch } from "@/components/ui/switch";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { REMAINDER_MODES, settlementLines, type RemainderMode } from "@/features/bets/payout";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { SettlementSummary } from "./settlement-summary";
import { AddPlayersSection } from "./add-players";
import { ScrollContainer } from "@/components/scroll-container";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { SettingRow, SettingsSection } from "@/components/settings";
import { useAppStore, useMe, useGetUser } from "@/features/store";
import type { Table } from "@/features/table/types";
import { UserAvatar } from "@/features/social/components/user-avatar";
import { TABLE_SWITCHES, canDeleteTable, isHost, type TableSwitchId } from "../hosts";
import { MemberSettingsDialog } from "./member-settings-dialog";
import { TABLE_EMOJIS } from "../emojis";
import { QuickPicksSection } from "./quick-picks-section";

const SWITCH_ICON: Record<TableSwitchId, typeof Lock> = {
  hostsOnlyBets: Lock,
  allowU2Pick: PenLine,
};

/** Host-only tools: table details, members & hosts, and inviting friends. */
export function HostPanel({
  table,
  className,
}: {
  table: Table;
  className?: string;
}) {
  const me = useMe();
  const getUser = useGetUser();
  const { actions } = useAppStore();
  const [name, setName] = useState(table.name);
  const [emoji, setEmoji] = useState(table.emoji);
  const [openId, setOpenId] = useState<string | null>(null);
  useEffect(() => {
    setName(table.name);
    setEmoji(table.emoji);
  }, [table.name, table.emoji]);
  const dirty = name.trim() !== table.name || emoji !== table.emoji;

  const members = [...table.memberIds].sort(
    (a, b) => Number(isHost(table, b)) - Number(isHost(table, a)),
  );

  return (
    <ScrollContainer isVisible={false} className={cn("space-y-4 p-4", className)}>
      <SettleSection table={table} />

      <SettingsSection title="Table details" description="Only hosts can change these.">
        {TABLE_SWITCHES.map((sw) => (
          <SettingRow
            key={sw.id}
            icon={SWITCH_ICON[sw.id]}
            htmlFor={`switch-${sw.id}`}
            label={sw.label}
            description={sw.description}
            control={
              <Switch
                id={`switch-${sw.id}`}
                checked={sw.isOn(table)}
                onCheckedChange={(on) =>
                  sw.id === "hostsOnlyBets"
                    ? actions.setHostsOnlyBets(table.id, on)
                    : actions.setU2PickOff(table.id, !on)
                }
              />
            }
          />
        ))}
        <RemainderSetting table={table} />
        <div className="space-y-3 px-4 py-3.5 sm:px-5">
          <Input
            value={name}
            onChange={(e) => setName(e.target.value)}
            aria-label="Table name"
            className="h-11 bg-sidebar"
          />
          <div className="flex flex-wrap gap-1.5">
            {TABLE_EMOJIS.map((e) => (
              <button
                key={e}
                type="button"
                onClick={() => setEmoji(e)}
                aria-label={`Use ${e}`}
                className={cn(
                  "flex size-9 items-center justify-center rounded-lg border bg-sidebar text-lg transition-colors",
                  emoji === e ? "border-primary bg-primary/15" : "hover:border-primary/50",
                )}
              >
                {e}
              </button>
            ))}
          </div>
          <Button
            size="sm"
            disabled={!dirty || !name.trim()}
            onClick={() => {
              actions.updateTable(table.id, name, emoji);
            }}
          >
            Save changes
          </Button>
        </div>
      </SettingsSection>

      <SettingsSection
        title="Members & hosts"
        description={`${table.hostIds.length} host${table.hostIds.length === 1 ? "" : "s"} · ${table.memberIds.length} members. Tap someone to change their role or permissions.`}
      >
        <ScrollContainer isVisible={false} className="max-h-80">
          <ul className="divide-y">
            <AnimatedList>
              {members.map((id) => {
                const host = isHost(table, id);
                const u = getUser(id);
                const limits = table.limits?.[id]?.length ?? 0;
                return (
                  <AnimatedItem as="li" key={id}>
                    <button
                      type="button"
                      onClick={() => setOpenId(id)}
                      className="flex w-full items-center gap-3 px-4 py-2.5 text-left transition-colors hover:bg-surface-raised sm:px-5"
                    >
                      <UserAvatar userId={id} size="sm" className="ring-surface" />
                      <span className="min-w-0 flex-1">
                        <span className="flex flex-wrap items-center gap-1.5 break-words text-sm font-semibold">
                          {id === me ? "You" : u.username}
                          {host && (
                            <Crown className="size-3.5 shrink-0 text-primary" aria-label="Host" />
                          )}
                        </span>
                        <span className="block font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
                          {host ? "Host" : "Member"}
                          {limits > 0 && (
                            <span className="text-status-pending">
                              {" "}
                              · {limits} limit{limits === 1 ? "" : "s"}
                            </span>
                          )}
                        </span>
                      </span>
                      <ChevronRight className="size-4 shrink-0 text-muted-foreground" />
                    </button>
                  </AnimatedItem>
                );
              })}
            </AnimatedList>
          </ul>
        </ScrollContainer>
      </SettingsSection>
      <MemberSettingsDialog
        table={table}
        userId={openId}
        onOpenChange={(o) => !o && setOpenId(null)}
      />

      <AddPlayersSection table={table} />
      <QuickPicksSection table={table} />
      <DeleteTableSection table={table} />
    </ScrollContainer>
  );
}

/** Deleting is final; only allowed once no bet here is Active or Pending. */
function DeleteTableSection({ table }: { table: Table }) {
  const me = useMe();
  const navigate = useNavigate();
  const { state, actions } = useAppStore();
  const allowed = canDeleteTable(table, me, state.bets);
  return (
    <SettingsSection
      title="Delete table"
      description={
        allowed
          ? "Removes the table, its chat and its ticket history for everyone. This can't be undone."
          : "Finish every Active and Pending bet here before deleting the table."
      }
    >
      <div className="px-4 py-3.5 sm:px-5">
        <AlertDialog>
          <AlertDialogTrigger asChild>
            <Button size="sm" variant="destructive" disabled={!allowed}>
              <Trash2 /> Delete table
            </Button>
          </AlertDialogTrigger>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Delete {table.name}?</AlertDialogTitle>
              <AlertDialogDescription>
                Everyone loses access to this table, its chat and its tickets. This can't be undone.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>Cancel</AlertDialogCancel>
              <AlertDialogAction
                onClick={() => {
                  actions.deleteTable(table.id);
                  void navigate({ to: "/" });
                }}
              >
                Delete table
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
    </SettingsSection>
  );
}

function SettleSection({ table }: { table: Table }) {
  const me = useMe();
  const { state, actions } = useAppStore();
  const { lines, hostNet } = settlementLines(state.bets, table.id, me);
  const nothing = lines.length === 0 && hostNet === 0;
  return (
    <SettingsSection
      title="Settle points"
      description="Pay everyone out through you so each settle balance goes back to 0. Pending bets keep running. Zero-sum, so you end up with your own result."
    >
      <div className="space-y-3 px-4 py-3.5 sm:px-5">
        <SettlementSummary table={table} hostId={me} />
        <AlertDialog>
          <AlertDialogTrigger asChild>
            <Button size="sm" disabled={nothing}>
              <HandCoins /> Settle points
            </Button>
          </AlertDialogTrigger>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Settle {table.name}?</AlertDialogTitle>
              <AlertDialogDescription>
                Everyone's net at this table goes back to 0. A message in chat tells each person
                what to send you or what you owe them. This can't be undone.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>Cancel</AlertDialogCancel>
              <AlertDialogAction
                onClick={() => {
                  actions.settlePoints(table.id);
                }}
              >
                Settle points
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
    </SettingsSection>
  );
}

/** Per-bet payout rule. Fixed onto each bet when it locks, so it can't change while bets take picks. */
function RemainderSetting({ table }: { table: Table }) {
  const { state, actions } = useAppStore();
  const [blocked, setBlocked] = useState(false);
  const mode = table.remainderMode ?? "host";
  const active = state.bets.some((b) => b.tableId === table.id && b.status === "active");
  return (
    <div className="space-y-1.5 px-4 py-3.5 sm:px-5">
      <label htmlFor="remainder-mode" className="text-sm font-semibold">
        Leftover cents go to
      </label>
      <Select
        value={mode}
        onValueChange={(v) => actions.setRemainderMode(table.id, v as RemainderMode)}
        onOpenChange={(o) => {
          if (o && active) setBlocked(true);
        }}
        {...(active ? { open: false } : {})}
      >
        <SelectTrigger id="remainder-mode" className="h-10 bg-sidebar">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {REMAINDER_MODES.map((m) => (
            <SelectItem key={m.id} value={m.id}>
              {m.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <p className="text-xs text-muted-foreground">
        {REMAINDER_MODES.find((m) => m.id === mode)!.description} For a single bet's payout when its
        pot doesn't split evenly to the cent. Each bet keeps the rule it had when its picks locked.
      </p>
      <AlertDialog open={blocked} onOpenChange={setBlocked}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Can't change this right now</AlertDialogTitle>
            <AlertDialogDescription>
              A bet at this table is still taking picks. Try again once every active bet has locked.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogAction>OK</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

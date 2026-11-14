import { useEffect, useRef, useState, type ComponentType } from "react";
import {
  Check,
  ChevronRight,
  Copy,
  Crown,
  HandCoins,
  Heart,
  Images,
  Lock,
  MessageSquareText,
  PenLine,
  Plus,
  Radio,
  RefreshCw,
  SmilePlus,
  Ticket,
  Trash2,
  UserMinus,
  Zap,
} from "lucide-react";
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
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { ScrollContainer } from "@/components/scroll-container";
import { SettingRow, SettingsSection } from "@/components/settings";
import { TABLE_EMOJIS } from "@/features/table/emojis";
import { formatCents } from "@/lib/money";
import { cn } from "@/lib/utils";
import type { BackendBet, BackendTable, BackendUser } from "./backend-api";
import { ActionStatus } from "./backend-action-status";
import { PersonAvatar } from "./backend-avatar";
import type { BetActions } from "./backend-bet-controls";
import { awaitingPointsSettlement } from "./bet-results";
import { addableFollowers, tableNameProblem } from "./management-rules";
import { useRelationships } from "./social-queries";
import type { useTableActions } from "./table-actions";
import type { Permission, RemainderRule, TableBalance } from "./tables-api";
import { usePerson } from "./use-person";

type TableActions = ReturnType<typeof useTableActions>;

interface People {
  user: BackendUser;
  expire: () => void;
  peopleKey: readonly string[];
}

interface PanelProps extends People {
  table: BackendTable;
  bets: BackendBet[] | undefined;
  balances: TableBalance[] | undefined;
  tableActions: TableActions;
  betActions: BetActions;
  className?: string;
}

const PERMISSIONS: {
  id: Permission;
  label: string;
  description: string;
  icon: ComponentType<{ className?: string }>;
}[] = [
  { id: "bets", label: "Bets", description: "Start bets and make picks.", icon: Ticket },
  { id: "text", label: "Messages", description: "Send text messages.", icon: MessageSquareText },
  {
    id: "react",
    label: "Reactions",
    description: "React to messages and throw tomatoes.",
    icon: SmilePlus,
  },
  { id: "gifs", label: "GIFs", description: "Send GIFs.", icon: Images },
];

const REMAINDER: { id: RemainderRule; label: string; description: string }[] = [
  { id: "host", label: "Host", description: "Leftover cents go to a host who's in the pot." },
  { id: "proposer", label: "Proposer", description: "Leftover cents go to whoever proposed it." },
  {
    id: "earliest",
    label: "Earliest pick",
    description: "Leftover cents go to the first winner to pick.",
  },
  { id: "random", label: "Random", description: "Leftover cents go to a random winner." },
];

const SWITCHES = [
  {
    id: "hostsOnlyBets",
    label: "Only hosts start bets",
    description: "Members can still pick on any bet.",
    icon: Lock,
    isOn: (t: BackendTable) => t.hostsOnlyBets,
  },
  {
    id: "u2pickEnabled",
    label: "Allow U2Pick bets",
    description: "Custom bets with your own question and options.",
    icon: PenLine,
    isOn: (t: BackendTable) => t.u2pickEnabled,
  },
] as const;

const busy = (actions: { busy: boolean; pending: unknown; storageError: unknown }) =>
  actions.busy || !!actions.pending || !!actions.storageError;

/** Whether the table has bets that block deleting it or removing members. */
function hasOpenBets(bets: BackendBet[] | undefined) {
  return (bets ?? []).some(
    (bet) =>
      bet.status === "ACTIVE" ||
      bet.status === "PENDING" ||
      (bet.status === "SETTLED" && !bet.pointsSettled),
  );
}

/** Host-only tools: settle points, table details, members and hosts, adding players, Quick Picks, delete. */
export function HostPanel(props: PanelProps) {
  const { table, tableActions, className } = props;
  const [openId, setOpenId] = useState<string | null>(null);
  const members = [...table.memberIds].sort(
    (a, b) => Number(table.hostIds.includes(b)) - Number(table.hostIds.includes(a)),
  );
  return (
    <ScrollContainer isVisible={false} className={cn("space-y-4 p-4", className)}>
      <ActionStatus
        actions={tableActions}
        pendingText="A table change may not have been saved."
        checkText="Check the table's members and settings first."
      />
      <SettleSection {...props} />
      <TableDetailsSection {...props} />
      <SettingsSection
        title="Members & hosts"
        description={`${table.hostIds.length} host${table.hostIds.length === 1 ? "" : "s"} · ${table.memberIds.length} members. Tap someone to change their role or permissions.`}
      >
        <ScrollContainer isVisible={false} className="max-h-80">
          <ul className="divide-y">
            <AnimatedList>
              {members.map((id) => (
                <AnimatedItem as="li" key={id}>
                  <MemberButton id={id} onOpen={() => setOpenId(id)} {...props} />
                </AnimatedItem>
              ))}
            </AnimatedList>
          </ul>
        </ScrollContainer>
      </SettingsSection>
      <MemberSettingsDialog
        {...props}
        userId={openId}
        onOpenChange={(o) => !o && setOpenId(null)}
      />
      <AddPlayersSection {...props} />
      <QuickPicksSection {...props} />
      <DeleteTableSection {...props} />
    </ScrollContainer>
  );
}

function MemberButton({
  id,
  onOpen,
  table,
  ...people
}: PanelProps & { id: string; onOpen: () => void }) {
  const person = usePerson(id, people.user, people.expire, people.peopleKey);
  const host = table.hostIds.includes(id);
  const limits = table.disabledPermissions[id]?.length ?? 0;
  return (
    <button
      type="button"
      onClick={onOpen}
      className="flex w-full items-center gap-3 px-4 py-2.5 text-left transition-colors hover:bg-surface-raised sm:px-5"
    >
      <PersonAvatar
        id={id}
        size="sm"
        className="ring-surface"
        user={people.user}
        expire={people.expire}
        queryKey={people.peopleKey}
      />
      <span className="min-w-0 flex-1">
        <span className="flex flex-wrap items-center gap-1.5 break-words text-sm font-semibold">
          {person?.name ?? "…"}
          {host && <Crown className="size-3.5 shrink-0 text-primary" aria-label="Host" />}
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
  );
}

/** Each member's points that the next settlement moves; everyone pays or is paid through the host. */
function SettlementPreview({ table, balances, ...people }: PanelProps) {
  const rows = (balances ?? []).filter((b) => b.settleCents !== 0n);
  if (!balances) return <p className="text-sm text-muted-foreground">Loading balances…</p>;
  if (rows.length === 0) return <p className="text-sm text-muted-foreground">Everyone is at 0.</p>;
  return (
    <ul className="space-y-1.5">
      {rows.map((row) => (
        <li key={row.userId} className="flex items-center gap-2.5 text-sm">
          <PersonAvatar
            id={row.userId}
            size="xs"
            user={people.user}
            expire={people.expire}
            queryKey={people.peopleKey}
          />
          <span className="min-w-0 flex-1 break-words">
            <PersonLabel id={row.userId} {...people} />{" "}
            <span className="text-muted-foreground">
              {row.settleCents > 0n
                ? row.userId === people.user.id
                  ? "get paid"
                  : "gets paid"
                : row.userId === people.user.id
                  ? "pay"
                  : "pays"}
            </span>
          </span>
          <span
            className={cn("scoreboard text-base", row.settleCents > 0n ? "text-win" : "text-loss")}
          >
            {formatCents(row.settleCents > 0n ? row.settleCents : -row.settleCents)}
          </span>
        </li>
      ))}
      <li className="pt-1 text-xs text-muted-foreground">
        {table.hostIds.length === 1 ? "Through the host." : "Through the host who settles."}
      </li>
    </ul>
  );
}

function PersonLabel({ id, user, expire, peopleKey }: People & { id: string }) {
  return <>{usePerson(id, user, expire, peopleKey)?.name ?? "…"}</>;
}

function SettleSection(props: PanelProps) {
  const { table, bets, betActions } = props;
  const waiting = awaitingPointsSettlement(bets ?? []);
  return (
    <SettingsSection
      title="Settle points"
      description="Pay everyone out through you so each settle balance goes back to 0. Pending bets keep running. Zero-sum, so you end up with your own result."
    >
      <div className="space-y-3 px-4 py-3.5 sm:px-5">
        <SettlementPreview {...props} />
        <p className="text-xs text-muted-foreground">
          {waiting === 0
            ? "No resolved bets are waiting."
            : `${waiting} resolved ${waiting === 1 ? "bet is" : "bets are"} waiting for points settlement.`}
        </p>
        <AlertDialog>
          <AlertDialogTrigger asChild>
            <Button size="sm" disabled={waiting === 0 || busy(betActions)}>
              <HandCoins /> Settle table points
            </Button>
          </AlertDialogTrigger>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Settle {table.name}?</AlertDialogTitle>
              <AlertDialogDescription>
                Everyone's settle balance here goes back to 0. A message in chat shows who pays
                whom, and everyone gets receipts on their Friends page. This can't be undone.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>Cancel</AlertDialogCancel>
              <AlertDialogAction
                onClick={() => betActions.start({ kind: "settle-points", tableId: table.id })}
              >
                Confirm
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
    </SettingsSection>
  );
}

function TableDetailsSection({ table, bets, tableActions }: PanelProps) {
  const [name, setName] = useState(table.name);
  const [emoji, setEmoji] = useState(table.emoji);
  const [problem, setProblem] = useState<string | null>(null);
  const [remainderBlocked, setRemainderBlocked] = useState(false);
  useEffect(() => {
    setName(table.name);
    setEmoji(table.emoji);
  }, [table.name, table.emoji]);
  const dirty = name.trim() !== table.name || emoji !== table.emoji;
  const disabled = busy(tableActions);
  const active = (bets ?? []).some((bet) => bet.status === "ACTIVE");
  const rule = REMAINDER.find((r) => r.id === table.remainderRule)!;
  const emojis = TABLE_EMOJIS.includes(table.emoji) ? TABLE_EMOJIS : [table.emoji, ...TABLE_EMOJIS];
  return (
    <SettingsSection title="Table details" description="Only hosts can change these.">
      {SWITCHES.map((sw) => (
        <SettingRow
          key={sw.id}
          icon={sw.icon}
          htmlFor={`switch-${sw.id}`}
          label={sw.label}
          description={sw.description}
          control={
            <Switch
              id={`switch-${sw.id}`}
              checked={sw.isOn(table)}
              disabled={disabled}
              onCheckedChange={(on) =>
                tableActions.start({ kind: "settings", tableId: table.id, [sw.id]: on })
              }
            />
          }
        />
      ))}
      <div className="space-y-1.5 px-4 py-3.5 sm:px-5">
        <label htmlFor="remainder-rule" className="text-sm font-semibold">
          Leftover cents go to
        </label>
        <Select
          value={table.remainderRule}
          onValueChange={(value) =>
            tableActions.start({
              kind: "remainder",
              tableId: table.id,
              rule: value as RemainderRule,
            })
          }
          onOpenChange={(open) => {
            if (open && active) setRemainderBlocked(true);
          }}
          disabled={disabled}
          {...(active ? { open: false } : {})}
        >
          <SelectTrigger id="remainder-rule" className="h-10 bg-sidebar">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {REMAINDER.map((r) => (
              <SelectItem key={r.id} value={r.id}>
                {r.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <p className="text-xs text-muted-foreground">
          {rule.description} For a single bet's payout when its pot doesn't split evenly to the
          cent. Each bet keeps the rule it had when its picks locked.
        </p>
        <AlertDialog open={remainderBlocked} onOpenChange={setRemainderBlocked}>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Can't change this right now</AlertDialogTitle>
              <AlertDialogDescription>
                A bet at this table is still taking picks. Try again once every active bet has
                locked.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogAction>OK</AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
      <form
        className="space-y-3 px-4 py-3.5 sm:px-5"
        onSubmit={(event) => {
          event.preventDefault();
          const invalid = tableNameProblem(name);
          setProblem(invalid);
          if (invalid || !dirty) return;
          tableActions.start({
            kind: "profile",
            tableId: table.id,
            ...(name.trim() !== table.name ? { name: name.trim() } : {}),
            ...(emoji !== table.emoji ? { emoji } : {}),
          });
        }}
      >
        <Input
          value={name}
          maxLength={80}
          onChange={(e) => setName(e.target.value)}
          aria-label="Table name"
          className="h-11 bg-sidebar"
        />
        <div className="flex flex-wrap gap-1.5" role="radiogroup" aria-label="Table icon">
          {emojis.map((e) => (
            <button
              key={e}
              type="button"
              role="radio"
              aria-checked={emoji === e}
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
        {problem && (
          <p role="alert" className="text-xs text-loss">
            {problem}
          </p>
        )}
        <Button size="sm" type="submit" disabled={!dirty || disabled}>
          Save changes
        </Button>
      </form>
    </SettingsSection>
  );
}

/** Host popup for one member: host role, permissions and removing them from the table. */
function MemberSettingsDialog({
  userId,
  onOpenChange,
  ...props
}: PanelProps & { userId: string | null; onOpenChange: (open: boolean) => void }) {
  const { table, bets, tableActions, user, expire, peopleKey } = props;
  const focusPermissions = useRef(false);
  const open = !!userId && table.memberIds.includes(userId);
  const id = userId ?? user.id;
  const person = usePerson(id, user, expire, peopleKey);
  const name = person?.name ?? "…";
  const you = id === user.id;
  const host = table.hostIds.includes(id);
  const disabled = busy(tableActions);
  const canHostToggle = !host || table.hostIds.length > 1;
  const canLimit = !host;
  const off = table.disabledPermissions[id] ?? [];
  const removalBlocked = hasOpenBets(bets);
  const hostHelp = host
    ? table.hostIds.length === 1
      ? "A table always keeps one host. Make someone else a host first."
      : you
        ? "Step down and go back to being a member."
        : "They'll go back to being a member."
    : "Hosts can change table settings, add or remove players and settle points.";

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex max-h-[90dvh] flex-col gap-0 overflow-hidden p-0 sm:max-w-md">
        <DialogHeader className="flex-row items-center gap-3 space-y-0 border-b px-5 py-4 text-left">
          <PersonAvatar id={id} size="md" user={user} expire={expire} queryKey={peopleKey} />
          <div className="min-w-0">
            <DialogTitle className="flex flex-wrap items-center gap-1.5 break-words">
              {name}
              {host && <Crown className="size-4 shrink-0 text-primary" aria-label="Host" />}
            </DialogTitle>
            <DialogDescription className="font-mono text-[11px] uppercase tracking-wider">
              {host ? "Host" : "Member"}
            </DialogDescription>
          </div>
        </DialogHeader>
        <ScrollContainer isVisible={false} className="min-h-0 flex-1 space-y-4 p-4">
          <SettingsSection title="Role">
            <SettingRow
              icon={Crown}
              htmlFor="member-host"
              label="Host"
              description={hostHelp}
              disabled={!canHostToggle}
              control={
                <Switch
                  id="member-host"
                  checked={host}
                  disabled={!canHostToggle || disabled}
                  onCheckedChange={(on) =>
                    tableActions.start({ kind: "host", tableId: table.id, userId: id, host: on })
                  }
                />
              }
            />
          </SettingsSection>
          <SettingsSection
            title="Permissions"
            description={
              canLimit
                ? `Turn things off for ${you ? "you" : name} at this table. Only hosts and they see this.`
                : "Hosts can always do everything."
            }
          >
            {PERMISSIONS.map((p) => (
              <SettingRow
                key={p.id}
                icon={p.icon}
                htmlFor={`perm-${p.id}`}
                label={p.label}
                description={p.description}
                disabled={!canLimit}
                control={
                  <Switch
                    id={`perm-${p.id}`}
                    checked={!off.includes(p.id)}
                    disabled={!canLimit || disabled}
                    onCheckedChange={(enabled) =>
                      tableActions.start({
                        kind: "permission",
                        tableId: table.id,
                        userId: id,
                        permission: p.id,
                        enabled,
                      })
                    }
                  />
                }
              />
            ))}
          </SettingsSection>
          {!you && (
            <AlertDialog>
              <AlertDialogTrigger asChild>
                <Button
                  variant="outline"
                  className="w-full text-loss hover:text-loss"
                  disabled={disabled}
                >
                  <UserMinus /> Remove from table
                </Button>
              </AlertDialogTrigger>
              <AlertDialogContent
                onCloseAutoFocus={(event) => {
                  if (focusPermissions.current) {
                    event.preventDefault();
                    focusPermissions.current = false;
                    const control = document.getElementById("perm-bets");
                    control?.scrollIntoView({ block: "center" });
                    control?.focus();
                  }
                }}
              >
                <AlertDialogHeader>
                  <AlertDialogTitle>Remove {name}?</AlertDialogTitle>
                  <AlertDialogDescription>
                    {host
                      ? "Remove their host role before removing them."
                      : removalBlocked
                        ? "Members can't be removed while bets are active, pending or awaiting points settlement. You can turn off their permissions instead."
                        : `They leave ${table.name} right away. Their bets, ledger and receipts stay. You can add them back later.`}
                  </AlertDialogDescription>
                </AlertDialogHeader>
                <AlertDialogFooter>
                  <AlertDialogCancel>Cancel</AlertDialogCancel>
                  {host ? null : removalBlocked ? (
                    <AlertDialogCancel onClick={() => (focusPermissions.current = true)}>
                      Adjust permissions
                    </AlertDialogCancel>
                  ) : (
                    <AlertDialogAction
                      onClick={() => {
                        tableActions.start({ kind: "remove", tableId: table.id, userId: id });
                        onOpenChange(false);
                      }}
                    >
                      Remove
                    </AlertDialogAction>
                  )}
                </AlertDialogFooter>
              </AlertDialogContent>
            </AlertDialog>
          )}
        </ScrollContainer>
      </DialogContent>
    </Dialog>
  );
}

/** Friends and followers who aren't here yet; their settings decide whether they join or get a request. */
function AddPlayersSection({ table, tableActions, ...people }: PanelProps) {
  const relationships = useRelationships(people.user.id);
  const data = relationships.data;
  const candidates = data ? addableFollowers(table, data.followerIds, data.blockedIds) : [];
  const friends = candidates.filter((id) => data?.followingIds.includes(id));
  const disabled = busy(tableActions);
  const add = (userIds: string[]) =>
    tableActions.start({ kind: "add", tableId: table.id, userIds });
  return (
    <SettingsSection
      title="Add players"
      description="People who follow you. Their settings decide if they join right away or get a request."
    >
      <div className="grid grid-cols-2 gap-2 px-4 py-3 sm:px-5">
        <Button
          size="sm"
          variant="outline"
          disabled={friends.length === 0 || disabled}
          onClick={() => add(friends)}
        >
          <Heart /> All friends · {friends.length}
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={candidates.length === 0 || disabled}
          onClick={() => add(candidates)}
        >
          <Plus /> All followers · {candidates.length}
        </Button>
      </div>
      {relationships.isPending ? (
        <p className="px-4 py-4 text-sm text-muted-foreground sm:px-5">Loading…</p>
      ) : candidates.length === 0 ? (
        <p className="px-4 py-4 text-sm text-muted-foreground sm:px-5">
          Everyone who follows you is already here.
        </p>
      ) : (
        <ScrollContainer isVisible={false} className="max-h-72">
          <ul className="divide-y">
            <AnimatedList>
              {candidates.map((id) => (
                <AnimatedItem
                  as="li"
                  key={id}
                  className="flex items-center gap-3 px-4 py-2.5 sm:px-5"
                >
                  <PersonAvatar
                    id={id}
                    size="sm"
                    className="ring-surface"
                    user={people.user}
                    expire={people.expire}
                    queryKey={people.peopleKey}
                  />
                  <span className="min-w-0 flex-1">
                    <span className="block break-words text-sm font-semibold">
                      <PersonLabel id={id} {...people} />
                    </span>
                    <span className="flex items-center gap-1 font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
                      {friends.includes(id) ? (
                        <>
                          <Heart className="size-3 text-primary" /> Friend
                        </>
                      ) : (
                        "Follows you"
                      )}
                    </span>
                  </span>
                  <Button
                    size="sm"
                    variant="outline"
                    className="h-8 px-2.5 text-xs"
                    disabled={disabled}
                    onClick={() => add([id])}
                  >
                    <Plus /> Add
                  </Button>
                </AnimatedItem>
              ))}
            </AnimatedList>
          </ul>
        </ScrollContainer>
      )}
    </SettingsSection>
  );
}

/** Quick Picks sharing. Subscribing to another table's code isn't available yet. */
function QuickPicksSection({ table, tableActions }: PanelProps) {
  const [copied, setCopied] = useState(false);
  const disabled = busy(tableActions);
  const on = table.quickPicksEnabled;
  return (
    <SettingsSection
      title="Quick Picks"
      description="Advanced. Share this table's bets with other tables. Every table still only bets among itself; points never move between tables."
    >
      <SettingRow
        icon={Zap}
        htmlFor="switch-quick-picks"
        label="Turn on Quick Picks"
        description="Turning it off stops sharing and disconnects subscribers."
        control={
          <Switch
            id="switch-quick-picks"
            checked={on}
            disabled={disabled}
            onCheckedChange={(enabled) =>
              tableActions.start({ kind: "quickpicks", tableId: table.id, enabled })
            }
          />
        }
      />
      {on && (
        <>
          <SettingRow
            icon={Radio}
            htmlFor="switch-sharing"
            label="Share this table's bets"
            description="Other hosts with your code get copies of bets started here."
            control={
              <Switch
                id="switch-sharing"
                checked={table.quickPickSharing}
                disabled={disabled}
                onCheckedChange={(sharing) =>
                  tableActions.start({ kind: "sharing", tableId: table.id, sharing })
                }
              />
            }
          />
          {table.quickPickSharing && table.quickPickCode && (
            <div className="space-y-2 px-4 py-3.5 sm:px-5">
              <div className="flex flex-wrap items-center gap-2">
                <span className="scoreboard rounded-lg border bg-sidebar px-3 py-1.5 text-xl tracking-[0.3em]">
                  {table.quickPickCode}
                </span>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => {
                    void navigator.clipboard?.writeText(table.quickPickCode!);
                    setCopied(true);
                    setTimeout(() => setCopied(false), 1500);
                  }}
                >
                  {copied ? <Check /> : <Copy />} {copied ? "Copied" : "Copy"}
                </Button>
                <AlertDialog>
                  <AlertDialogTrigger asChild>
                    <Button size="sm" variant="ghost" disabled={disabled}>
                      <RefreshCw /> New code
                    </Button>
                  </AlertDialogTrigger>
                  <AlertDialogContent>
                    <AlertDialogHeader>
                      <AlertDialogTitle>Make a new code?</AlertDialogTitle>
                      <AlertDialogDescription>
                        Every table using the old code is cut off. Tickets they already got stay
                        with them. Hosts you still want will need the new code.
                      </AlertDialogDescription>
                    </AlertDialogHeader>
                    <AlertDialogFooter>
                      <AlertDialogCancel>Cancel</AlertDialogCancel>
                      <AlertDialogAction
                        onClick={() => tableActions.start({ kind: "rotate", tableId: table.id })}
                      >
                        New code
                      </AlertDialogAction>
                    </AlertDialogFooter>
                  </AlertDialogContent>
                </AlertDialog>
              </div>
            </div>
          )}
          <div className="space-y-1 px-4 py-3.5 sm:px-5">
            <p className="text-sm font-semibold">Subscribe to a table</p>
            <p className="text-xs text-muted-foreground">
              Coming soon: enter another host's code to get their bets posted here.
            </p>
          </div>
        </>
      )}
    </SettingsSection>
  );
}

/** Deleting is final; the chat stays readable for 90 days. */
function DeleteTableSection({ table, bets, tableActions }: PanelProps) {
  const allowed = !hasOpenBets(bets);
  return (
    <SettingsSection
      title="Delete table"
      description={
        allowed
          ? "Everyone loses access. The chat stays readable for 90 days. This can't be undone."
          : "Finish every active and pending bet and settle points before deleting the table."
      }
    >
      <div className="px-4 py-3.5 sm:px-5">
        <AlertDialog>
          <AlertDialogTrigger asChild>
            <Button size="sm" variant="destructive" disabled={!allowed || busy(tableActions)}>
              <Trash2 /> Delete table
            </Button>
          </AlertDialogTrigger>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Delete {table.name}?</AlertDialogTitle>
              <AlertDialogDescription>
                Everyone loses access to this table. Its chat stays readable for 90 days. This can't
                be undone.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>Cancel</AlertDialogCancel>
              <AlertDialogAction
                onClick={() => tableActions.start({ kind: "delete", tableId: table.id })}
              >
                Delete
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
    </SettingsSection>
  );
}

function OnOff({ on }: { on: boolean }) {
  return (
    <span className={cn("text-sm font-bold", on ? "text-primary" : "text-muted-foreground")}>
      {on ? "On" : "Off"}
    </span>
  );
}

/** Read-only view of the table's host settings, your permissions and where settlement stands. */
export function AboutPanel(props: PanelProps & { onOpenPeers: () => void }) {
  const { table, user, expire, peopleKey, className, onOpenPeers } = props;
  const rule = REMAINDER.find((r) => r.id === table.remainderRule)!;
  const off = table.disabledPermissions[user.id] ?? [];
  return (
    <ScrollContainer isVisible={false} className={cn("space-y-4 p-4", className)}>
      <button
        type="button"
        onClick={onOpenPeers}
        className="flex w-full items-center justify-between rounded-2xl border bg-surface px-4 py-3 text-left transition-colors hover:border-primary/60"
      >
        <span>
          <span className="block font-display text-xl font-black uppercase leading-none">
            {table.memberIds.length} {table.memberIds.length === 1 ? "member" : "members"}
          </span>
          <span className="text-xs text-muted-foreground">See everyone's points</span>
        </span>
        <ChevronRight className="size-4 text-muted-foreground" />
      </button>
      <SettingsSection title="Host settings" description="Only hosts can change these.">
        <div className="space-y-2 px-4 py-3.5 sm:px-5">
          {table.hostIds.map((id) => (
            <div key={id} className="flex items-center gap-2.5 text-sm">
              <PersonAvatar id={id} size="sm" user={user} expire={expire} queryKey={peopleKey} />
              <span className="min-w-0 flex-1 break-words font-semibold">
                <PersonLabel id={id} user={user} expire={expire} peopleKey={peopleKey} />
              </span>
              <Crown className="size-4 text-primary" aria-label="Host" />
            </div>
          ))}
        </div>
        <SettingRow
          label="Leftover cents go to"
          description={rule.description}
          control={<span className="text-sm font-bold">{rule.label}</span>}
        />
        {SWITCHES.map((sw) => (
          <SettingRow
            key={sw.id}
            label={sw.label}
            description={sw.description}
            control={<OnOff on={sw.isOn(table)} />}
          />
        ))}
        <SettingRow
          label="Quick Picks"
          description="Bets shared between tables. Each table only bets among itself."
          control={<OnOff on={table.quickPicksEnabled} />}
        />
      </SettingsSection>
      <SettingsSection
        title="Your permissions"
        description="Hosts can turn these off for you at this table."
      >
        {PERMISSIONS.map((p) => (
          <SettingRow
            key={p.id}
            label={p.label}
            description={p.description}
            control={<OnOff on={!off.includes(p.id)} />}
          />
        ))}
      </SettingsSection>
      <SettingsSection
        title="If settled now"
        description="Everyone pays or gets paid through the host who settles."
      >
        <div className="space-y-3 px-4 py-3.5 sm:px-5">
          <SettlementPreview {...props} />
        </div>
      </SettingsSection>
    </ScrollContainer>
  );
}

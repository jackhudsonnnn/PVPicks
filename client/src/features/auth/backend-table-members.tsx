import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { TABLE_EMOJIS } from "@/features/table/emojis";
import { backendErrorMessage, type BackendTable, type BackendUser } from "./backend-api";
import { ActionStatus } from "./backend-action-status";
import { ConfirmAction } from "./backend-confirm-action";
import { tableNameProblem } from "./backend-home";
import { PersonName } from "./backend-person-name";
import { userKey } from "./query-keys";
import { useRelationships } from "./social-queries";
import type { useTableActions } from "./table-actions";
import type { Permission, RemainderRule } from "./tables-api";

type Actions = ReturnType<typeof useTableActions>;

const PERMISSIONS: { id: Permission; label: string }[] = [
  { id: "bets", label: "Bets" },
  { id: "text", label: "Messages" },
  { id: "react", label: "Reactions" },
  { id: "gifs", label: "GIFs" },
];

const REMAINDER: { id: RemainderRule; label: string }[] = [
  { id: "host", label: "Host" },
  { id: "proposer", label: "Proposer" },
  { id: "earliest", label: "Earliest pick" },
  { id: "random", label: "Random" },
];

/** People who follow you, are not members yet and are not blocked: the ones a host can try to add. */
export function addableFollowers(
  table: BackendTable,
  followerIds: string[],
  blockedIds: string[],
): string[] {
  return followerIds.filter((id) => !table.memberIds.includes(id) && !blockedIds.includes(id));
}

/** Members, and for hosts: adding people, roles, permissions and table settings. */
export function TableMembers({
  user,
  table,
  expire,
  actions,
}: {
  user: BackendUser;
  table: BackendTable;
  expire: () => void;
  actions: Actions;
}) {
  const isHost = table.hostIds.includes(user.id);
  const blocked = actions.busy || !!actions.pending || !!actions.storageError;
  const names = userKey(user.id, "people");
  return (
    <div className="space-y-6">
      <ActionStatus
        actions={actions}
        pendingText="A table change may not have been saved."
        checkText="Check the table's members and settings first."
      />
      <section className="space-y-3" aria-labelledby="members-heading">
        <h2 id="members-heading" className="font-display text-2xl font-bold uppercase">
          Members
        </h2>
        <ul className="space-y-2">
          {table.memberIds.map((id) => (
            <MemberRow
              key={id}
              id={id}
              user={user}
              table={table}
              expire={expire}
              names={names}
              manage={isHost && id !== user.id}
              disabled={blocked}
              actions={actions}
            />
          ))}
        </ul>
        {isHost && (
          <AddMembers user={user} table={table} expire={expire} names={names} actions={actions} />
        )}
      </section>
      <TableSettings user={user} table={table} disabled={blocked} actions={actions} />
    </div>
  );
}

function MemberRow({
  id,
  user,
  table,
  expire,
  names,
  manage,
  disabled,
  actions,
}: {
  id: string;
  user: BackendUser;
  table: BackendTable;
  expire: () => void;
  names: string[];
  manage: boolean;
  disabled: boolean;
  actions: Actions;
}) {
  const host = table.hostIds.includes(id);
  const off = table.disabledPermissions[id] ?? [];
  return (
    <li className="space-y-3 rounded-lg border bg-surface p-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <span>
          <span className="font-semibold">
            <PersonName id={id} user={user} expire={expire} queryKey={names} />
          </span>
          <span className="block text-xs text-muted-foreground">
            {host ? "Host" : "Member"}
            {off.length > 0 && ` · Turned off: ${off.map(permissionLabel).join(", ")}`}
          </span>
        </span>
        {manage && (
          <span className="flex flex-wrap gap-2">
            <Button
              size="sm"
              variant="outline"
              disabled={disabled}
              onClick={() =>
                actions.start({ kind: "host", tableId: table.id, userId: id, host: !host })
              }
            >
              {host ? "Remove host" : "Make host"}
            </Button>
            {!host && (
              <ConfirmAction
                label="Remove"
                title="Remove this member?"
                description="They lose access to the table. Their bets, ledger and receipts stay."
                disabled={disabled}
                onConfirm={() => actions.start({ kind: "remove", tableId: table.id, userId: id })}
              />
            )}
          </span>
        )}
      </div>
      {manage && !host && (
        <div className="flex flex-wrap gap-4">
          {PERMISSIONS.map((permission) => {
            const switchId = `perm-${id}-${permission.id}`;
            return (
              <span key={permission.id} className="flex items-center gap-2">
                <Switch
                  id={switchId}
                  checked={!off.includes(permission.id)}
                  disabled={disabled}
                  onCheckedChange={(enabled) =>
                    actions.start({
                      kind: "permission",
                      tableId: table.id,
                      userId: id,
                      permission: permission.id,
                      enabled,
                    })
                  }
                />
                <Label htmlFor={switchId}>{permission.label}</Label>
              </span>
            );
          })}
        </div>
      )}
    </li>
  );
}

function AddMembers({
  user,
  table,
  expire,
  names,
  actions,
}: {
  user: BackendUser;
  table: BackendTable;
  expire: () => void;
  names: string[];
  actions: Actions;
}) {
  const relationships = useRelationships(user.id);
  const [chosen, setChosen] = useState<string[]>([]);
  const disabled = actions.busy || !!actions.pending || !!actions.storageError;
  const candidates = relationships.data
    ? addableFollowers(table, relationships.data.followerIds, relationships.data.blockedIds)
    : [];
  return (
    <details className="rounded-lg border bg-surface p-4">
      <summary className="cursor-pointer font-semibold">Add people</summary>
      <div className="mt-3 space-y-3">
        <p className="text-sm text-muted-foreground">
          You can add people who follow you. Their invite settings decide whether they join right
          away or get an invitation.
        </p>
        {relationships.isError ? (
          <p role="alert">{backendErrorMessage(relationships.error)}</p>
        ) : candidates.length === 0 ? (
          <p className="text-sm">Everyone who follows you is already here.</p>
        ) : (
          <>
            <ul className="space-y-2">
              {candidates.map((id) => (
                <li key={id} className="flex items-center gap-2">
                  <Switch
                    id={`add-${id}`}
                    checked={chosen.includes(id)}
                    disabled={disabled}
                    onCheckedChange={(on) =>
                      setChosen(on ? [...chosen, id] : chosen.filter((c) => c !== id))
                    }
                  />
                  <Label htmlFor={`add-${id}`}>
                    <PersonName id={id} user={user} expire={expire} queryKey={names} />
                  </Label>
                </li>
              ))}
            </ul>
            <Button
              size="sm"
              disabled={disabled || chosen.length === 0}
              onClick={() => {
                actions.start({ kind: "add", tableId: table.id, userIds: chosen });
                setChosen([]);
              }}
            >
              Add {chosen.length > 0 ? chosen.length : ""}
            </Button>
          </>
        )}
      </div>
    </details>
  );
}

function TableSettings({
  user,
  table,
  disabled,
  actions,
}: {
  user: BackendUser;
  table: BackendTable;
  disabled: boolean;
  actions: Actions;
}) {
  const isHost = table.hostIds.includes(user.id);
  const [name, setName] = useState(table.name);
  const [emoji, setEmoji] = useState(table.emoji);
  const [problem, setProblem] = useState<string | null>(null);
  const changed = name.trim() !== table.name || emoji !== table.emoji;

  function saveProfile(event: React.FormEvent) {
    event.preventDefault();
    const invalid = tableNameProblem(name);
    setProblem(invalid);
    if (invalid || !changed) return;
    actions.start({
      kind: "profile",
      tableId: table.id,
      ...(name.trim() !== table.name ? { name: name.trim() } : {}),
      ...(emoji !== table.emoji ? { emoji } : {}),
    });
  }

  return (
    <section className="space-y-4" aria-labelledby="settings-heading">
      <h2 id="settings-heading" className="font-display text-2xl font-bold uppercase">
        Table settings
      </h2>
      {!isHost ? (
        <dl className="grid gap-2 rounded-lg border bg-surface p-4 text-sm sm:grid-cols-2">
          <div>
            <dt className="text-muted-foreground">Leftover cents go to</dt>
            <dd>{REMAINDER.find((rule) => rule.id === table.remainderRule)?.label}</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Who can start bets</dt>
            <dd>{table.hostsOnlyBets ? "Hosts only" : "Members with betting on"}</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">U2Pick</dt>
            <dd>{table.u2pickEnabled ? "On" : "Off"}</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Quick Picks</dt>
            <dd>{table.quickPicksEnabled ? "On" : "Off"}</dd>
          </div>
        </dl>
      ) : (
        <div className="space-y-5 rounded-lg border bg-surface p-4">
          <form className="space-y-3" onSubmit={saveProfile}>
            <fieldset disabled={disabled} className="space-y-3">
              <div className="space-y-1">
                <Label htmlFor="edit-table-name">Name</Label>
                <Input
                  id="edit-table-name"
                  value={name}
                  maxLength={80}
                  onChange={(event) => setName(event.target.value)}
                />
              </div>
              <SegmentedTabs
                size="sm"
                ariaLabel="Table icon"
                tabs={(TABLE_EMOJIS.includes(table.emoji)
                  ? TABLE_EMOJIS
                  : [table.emoji, ...TABLE_EMOJIS]
                ).map((option) => ({ id: option, label: option }))}
                value={emoji}
                onChange={setEmoji}
              />
              {problem && <p role="alert">{problem}</p>}
              <Button type="submit" size="sm" disabled={!changed}>
                Save name and icon
              </Button>
            </fieldset>
          </form>
          <div className="space-y-3">
            <span className="flex items-center gap-2">
              <Switch
                id="hosts-only-bets"
                checked={table.hostsOnlyBets}
                disabled={disabled}
                onCheckedChange={(on) =>
                  actions.start({ kind: "settings", tableId: table.id, hostsOnlyBets: on })
                }
              />
              <Label htmlFor="hosts-only-bets">Only hosts can start bets</Label>
            </span>
            <span className="flex items-center gap-2">
              <Switch
                id="u2pick-enabled"
                checked={table.u2pickEnabled}
                disabled={disabled}
                onCheckedChange={(on) =>
                  actions.start({ kind: "settings", tableId: table.id, u2pickEnabled: on })
                }
              />
              <Label htmlFor="u2pick-enabled">Allow U2Pick bets</Label>
            </span>
          </div>
          <div className="space-y-1">
            <p className="text-sm font-medium">Leftover cents go to</p>
            <SegmentedTabs
              size="sm"
              ariaLabel="Leftover cents go to"
              tabs={REMAINDER.map((rule) => ({ ...rule, disabled }))}
              value={table.remainderRule}
              onChange={(rule) => actions.start({ kind: "remainder", tableId: table.id, rule })}
            />
            <p className="text-xs text-muted-foreground">
              Can't change while a bet is active; locked bets keep the rule they locked with.
            </p>
          </div>
          <ConfirmAction
            label="Delete table"
            title="Delete this table?"
            description="Everyone loses access. The chat stays readable for 90 days. You can't delete it while bets are active, pending or awaiting points settlement."
            disabled={disabled}
            onConfirm={() => actions.start({ kind: "delete", tableId: table.id })}
          />
        </div>
      )}
    </section>
  );
}

function permissionLabel(permission: string) {
  return PERMISSIONS.find((p) => p.id === permission)?.label.toLowerCase() ?? permission;
}

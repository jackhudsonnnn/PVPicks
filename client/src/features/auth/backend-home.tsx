import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { TABLE_EMOJIS } from "@/features/table/emojis";
import { backendErrorMessage, type BackendUser } from "./backend-api";
import { ActionStatus } from "./backend-action-status";
import { PersonName } from "./backend-person-name";
import { userKey } from "./query-keys";
import { useSocialActions } from "./social-actions";
import { useInvites, useRelationships } from "./social-queries";
import { useTableActions } from "./table-actions";

export const TABLE_NAME_LIMIT = 40;

/** Why a table name can't be used, or null. Matches the server: 1–40 characters after trimming. */
export function tableNameProblem(name: string): string | null {
  const trimmed = name.trim();
  if (!trimmed) return "Give the table a name.";
  if ([...trimmed].length > TABLE_NAME_LIMIT)
    return `Keep the name to ${TABLE_NAME_LIMIT} characters or fewer.`;
  return null;
}

/** Create a table, optionally asking people who follow you to join. */
export function CreateTable({ user, expire }: { user: BackendUser; expire: () => void }) {
  const queryClient = useQueryClient();
  const relationships = useRelationships(user.id);
  const actions = useTableActions(
    user.id,
    async () => {
      await queryClient.invalidateQueries({ queryKey: userKey(user.id) });
    },
    expire,
  );
  const [name, setName] = useState("");
  const [emoji, setEmoji] = useState(TABLE_EMOJIS[0]!);
  const [members, setMembers] = useState<string[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  const blocked = actions.busy || !!actions.pending || !!actions.storageError;
  const candidates = relationships.data?.followerIds ?? [];

  function submit(event: React.FormEvent) {
    event.preventDefault();
    const invalid = tableNameProblem(name);
    setProblem(invalid);
    if (invalid) return;
    actions.start({ kind: "create", name: name.trim(), emoji, memberIds: members });
    setName("");
    setMembers([]);
  }

  return (
    <div className="space-y-2">
      <ActionStatus
        actions={actions}
        pendingText="A table change may not have been saved."
        checkText="Check your tables first."
      />
      <details className="rounded-lg border bg-surface p-4">
        <summary className="cursor-pointer font-semibold">Create a table</summary>
        <form className="mt-4 space-y-4" onSubmit={submit}>
          <fieldset disabled={blocked} className="space-y-4">
            <div className="space-y-1">
              <Label htmlFor="table-name">Table name</Label>
              <Input
                id="table-name"
                value={name}
                maxLength={80}
                onChange={(event) => setName(event.target.value)}
              />
            </div>
            <div className="space-y-1">
              <p className="text-sm font-medium">Icon</p>
              <SegmentedTabs
                size="sm"
                ariaLabel="Table icon"
                tabs={TABLE_EMOJIS.map((option) => ({ id: option, label: option }))}
                value={emoji}
                onChange={setEmoji}
              />
            </div>
            <div className="space-y-2">
              <p className="text-sm font-medium">Invite people who follow you</p>
              {relationships.isError ? (
                <p role="alert">{backendErrorMessage(relationships.error)}</p>
              ) : candidates.length === 0 ? (
                <p className="text-sm text-muted-foreground">
                  Nobody follows you yet. You can add members later from the table.
                </p>
              ) : (
                <ul className="space-y-2">
                  {candidates.map((id) => (
                    <li key={id} className="flex items-center gap-2">
                      <Switch
                        id={`invite-${id}`}
                        checked={members.includes(id)}
                        onCheckedChange={(on) =>
                          setMembers(on ? [...members, id] : members.filter((m) => m !== id))
                        }
                      />
                      <Label htmlFor={`invite-${id}`}>
                        <PersonName
                          id={id}
                          user={user}
                          expire={expire}
                          queryKey={userKey(user.id, "people")}
                        />
                      </Label>
                    </li>
                  ))}
                </ul>
              )}
              <p className="text-xs text-muted-foreground">
                Each person's invite settings decide whether they join right away or get an
                invitation.
              </p>
            </div>
            {problem && <p role="alert">{problem}</p>}
            <Button type="submit">Create table</Button>
          </fieldset>
        </form>
      </details>
    </div>
  );
}

/** Pending invitations to join tables, with accept and decline. */
export function Invitations({ user, expire }: { user: BackendUser; expire: () => void }) {
  const queryClient = useQueryClient();
  const invites = useInvites(user.id);
  const actions = useSocialActions(
    user.id,
    async () => {
      await queryClient.invalidateQueries({ queryKey: userKey(user.id) });
    },
    expire,
  );
  const blocked = actions.busy || !!actions.pending || !!actions.storageError;
  if (invites.isPending || (invites.data?.length === 0 && !actions.message && !actions.pending))
    return null;
  return (
    <section className="space-y-3" aria-labelledby="invites-heading">
      <h2 id="invites-heading" className="font-display text-2xl font-bold uppercase">
        Invitations
      </h2>
      <ActionStatus
        actions={actions}
        pendingText="An invitation response may not have been saved."
        checkText="Check your tables and invitations first."
      />
      {invites.isError ? (
        <p role="alert">{backendErrorMessage(invites.error)}</p>
      ) : (
        <ul className="space-y-2">
          {invites.data.map((invite) => (
            <li
              key={invite.id}
              className="flex flex-wrap items-center justify-between gap-3 rounded-lg border bg-surface p-4"
            >
              <span>
                <span className="text-2xl">{invite.tableEmoji}</span>{" "}
                <span className="font-semibold">{invite.tableName}</span>
                <span className="block text-sm text-muted-foreground">
                  From{" "}
                  <PersonName
                    id={invite.fromId}
                    user={user}
                    expire={expire}
                    queryKey={userKey(user.id, "people")}
                  />
                </span>
              </span>
              <span className="flex gap-2">
                <Button
                  size="sm"
                  disabled={blocked}
                  onClick={() =>
                    actions.start({ kind: "invite", inviteId: invite.id, accept: true })
                  }
                >
                  Join
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={blocked}
                  onClick={() =>
                    actions.start({ kind: "invite", inviteId: invite.id, accept: false })
                  }
                >
                  Decline
                </Button>
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

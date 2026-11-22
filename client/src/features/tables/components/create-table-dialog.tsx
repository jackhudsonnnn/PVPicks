import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { ActionStatus } from "@/components/action-status";
import { TABLE_EMOJIS } from "@/features/tables/emojis";
import { backendErrorMessage } from "@/lib/api-client";
import { userKey } from "@/lib/query-keys";
import { type User, PersonName } from "@/features/people";
import { useRelationships } from "@/features/social";
import { useTableActions } from "@/features/tables/actions";
import { TABLE_NAME_LIMIT, tableNameProblem } from "@/features/tables/rules";
import { useNewcomer } from "@/features/tables/newcomer";
import { FindFriendsDialog } from "@/components/find-friends-dialog";

/** "New table": name, icon, and people who follow you to ask along. */
export function CreateTableDialog({ user, expire }: { user: User; expire: () => void }) {
  const queryClient = useQueryClient();
  const relationships = useRelationships(user.id);
  const [open, setOpen] = useState(false);
  const newcomer = useNewcomer(user.id);
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
  const length = [...name.trim()].length;

  function submit(event: React.FormEvent) {
    event.preventDefault();
    const invalid = tableNameProblem(name);
    setProblem(invalid);
    if (invalid) return;
    actions.start({ kind: "create", name: name.trim(), emoji, memberIds: members });
    setName("");
    setMembers([]);
    setOpen(false);
  }

  return (
    <>
      <ActionStatus
        actions={actions}
        pendingText="A table change may not have been saved."
        checkText="Check your tables first."
      />
      {newcomer && <FindFriendsDialog open={open} onOpenChange={setOpen} />}
      <Dialog open={open && !newcomer} onOpenChange={setOpen}>
        <DialogTrigger asChild>
          <Button variant="outline" disabled={blocked}>
            <Plus /> New table
          </Button>
        </DialogTrigger>
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="font-display text-3xl font-black uppercase">
              New table
            </DialogTitle>
            <DialogDescription>
              Each person's invite settings decide whether they join right away or get an
              invitation.
            </DialogDescription>
          </DialogHeader>
          <form className="space-y-4" onSubmit={submit}>
            <fieldset disabled={blocked} className="space-y-4">
              <div className="space-y-1">
                <div className="flex items-baseline justify-between">
                  <Label htmlFor="table-name">Table name</Label>
                  <span
                    className={
                      length > TABLE_NAME_LIMIT
                        ? "font-mono text-xs text-loss"
                        : "font-mono text-xs text-muted-foreground"
                    }
                  >
                    {length}/{TABLE_NAME_LIMIT}
                  </span>
                </div>
                <Input
                  id="table-name"
                  value={name}
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
              </div>
              {problem && (
                <p role="alert" className="text-sm text-loss">
                  {problem}
                </p>
              )}
              <Button type="submit" className="w-full">
                Create table
              </Button>
            </fieldset>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}

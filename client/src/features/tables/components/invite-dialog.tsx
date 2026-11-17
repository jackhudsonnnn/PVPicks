import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { userKey } from "@/lib/query-keys";
import { AvatarStack, type User, usePerson } from "@/features/people";
import type { InviteActions, TableInvite } from "@/features/social";

/**
 * The "Join?" popup for a table request, behind any trigger (a Home card on phones, a sidebar row on desktop).
 * Accepting adds the table to your list.
 */
export function InviteDialog({
  invite,
  actions,
  user,
  expire,
  triggerClassName,
  children,
}: {
  invite: TableInvite;
  actions: InviteActions;
  user: User;
  expire: () => void;
  triggerClassName: string;
  children: ReactNode;
}) {
  const from = usePerson(invite.fromId, user, expire, userKey(user.id, "people"));
  const blocked = actions.busy || !!actions.pending || !!actions.storageError;
  const count = invite.memberCount;
  return (
    <Dialog>
      <DialogTrigger asChild>
        <button
          type="button"
          aria-label={`Request to join ${invite.tableName}`}
          className={triggerClassName}
        >
          {children}
        </button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <span className="mx-auto flex size-14 items-center justify-center rounded-2xl bg-invite/20 text-3xl sm:mx-0">
            {invite.tableEmoji}
          </span>
          <DialogTitle className="font-display text-3xl font-black uppercase">
            Join {invite.tableName}?
          </DialogTitle>
          <DialogDescription>
            {from?.name ?? "Someone"} wants you at their table. You'll see its chat and can join its
            bets.
          </DialogDescription>
        </DialogHeader>
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <AvatarStack
            ids={invite.memberPreview}
            total={invite.memberCount}
            max={4}
            user={user}
            expire={expire}
            queryKey={userKey(user.id, "people")}
          />
          {count} {count === 1 ? "person" : "people"} at the table
        </div>
        <DialogFooter className="gap-2">
          <DialogClose asChild>
            <Button
              variant="outline"
              disabled={blocked}
              onClick={() =>
                actions.start({
                  kind: "invite",
                  inviteId: invite.id,
                  tableId: invite.tableId,
                  accept: false,
                })
              }
            >
              Decline
            </Button>
          </DialogClose>
          <DialogClose asChild>
            <Button
              className="bg-invite text-invite-foreground hover:bg-invite/90"
              disabled={blocked}
              onClick={() =>
                actions.start({
                  kind: "invite",
                  inviteId: invite.id,
                  tableId: invite.tableId,
                  accept: true,
                })
              }
            >
              Accept
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

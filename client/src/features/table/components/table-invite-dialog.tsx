import type { ReactNode } from "react";
import { useNavigate } from "@tanstack/react-router";
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
import { Button } from "@/components/ui/button";
import { AvatarStack } from "@/features/social/components/user-avatar";
import { TrustNotice } from "@/features/social/components/trust-notice";
import { useAppStore, useGetUser } from "@/features/store";
import type { Table } from "@/features/table/types";

/** "Accept?" popup for a request to join someone's table. */
export function TableInviteDialog({
  invite,
  trigger,
}: {
  invite: { id: string; fromId: string; table: Table };
  trigger: ReactNode;
}) {
  const getUser = useGetUser();
  const { actions } = useAppStore();
  const navigate = useNavigate();
  const { table } = invite;
  const from = getUser(invite.fromId).username;

  return (
    <Dialog>
      <DialogTrigger asChild>{trigger}</DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <span className="mx-auto flex size-14 items-center justify-center rounded-2xl bg-invite/20 text-3xl sm:mx-0">
            {table.emoji}
          </span>
          <DialogTitle className="font-display text-3xl font-black uppercase">
            Join {table.name}?
          </DialogTitle>
          <DialogDescription>
            {from} wants you at their table. You'll see its chat and can join its bets.
          </DialogDescription>
        </DialogHeader>
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <AvatarStack ids={table.memberIds} max={4} />
          {table.memberIds.length} {table.memberIds.length === 1 ? "person" : "people"} at the table
        </div>
        <TrustNotice />
        <DialogFooter className="gap-2">
          <DialogClose asChild>
            <Button variant="outline" onClick={() => actions.respondInvite(invite.id, false)}>
              Decline
            </Button>
          </DialogClose>
          <DialogClose asChild>
            <Button
              className="bg-invite text-invite-foreground hover:bg-invite/90"
              onClick={() => {
                actions.respondInvite(invite.id, true);
                navigate({ to: "/tables/$tableId", params: { tableId: table.id } });
              }}
            >
              Accept
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

import { useState } from "react";
import { Heart, UserCheck, UserPlus } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { useAppStore, useMe, useGetUser } from "@/features/store";
import { relationOf, type Relation } from "../follows";

const FOLLOW_LABEL: Record<Relation, string> = {
  none: "Follow",
  follower: "Follow back",
  following: "Following",
  friend: "Friends",
};

/** Follow / Follow back, or Following / Friends (tap again to unfollow, after a confirm). */
export function FollowButton({
  userId,
  compact,
}: {
  userId: string;
  compact?: boolean | undefined;
}) {
  const me = useMe();
  const getUser = useGetUser();
  const { state, actions } = useAppStore();
  const [confirm, setConfirm] = useState(false);
  const rel = relationOf(state.follows, me, userId);
  const on = rel === "following" || rel === "friend";
  const first = getUser(userId).username.split(" ")[0];
  return (
    <>
      <Button
        size="sm"
        variant={on ? "secondary" : rel === "follower" ? "default" : "outline"}
        className={cn("ml-auto shrink-0", compact && "h-8 px-2 text-xs")}
        aria-pressed={on}
        title={on ? "Tap to unfollow" : undefined}
        onClick={() => (on ? setConfirm(true) : actions.follow(userId, true))}
      >
        {rel === "friend" ? <Heart /> : on ? <UserCheck /> : <UserPlus />}
        {FOLLOW_LABEL[rel]}
      </Button>
      <ConfirmDialog
        open={confirm}
        onOpenChange={setConfirm}
        title={`Unfollow ${first}?`}
        description={
          rel === "friend"
            ? `You and ${first} won't be friends anymore, so friend-only table invites between you stop working. They aren't told.`
            : `${first} won't be able to add you to tables as someone you follow. They aren't told.`
        }
        cancelLabel="Keep following"
        confirmLabel="Unfollow"
        onConfirm={() => actions.follow(userId, false)}
      />
    </>
  );
}

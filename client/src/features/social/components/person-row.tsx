import { useGetUser } from "@/features/store";
import { UserAvatar } from "./user-avatar";
import { SafetyMenu } from "./safety-dialogs";
import { FollowButton } from "./follow-button";

export function PersonRow({ userId }: { userId: string }) {
  const getUser = useGetUser();
  const u = getUser(userId);
  return (
    <div className="flex min-w-0 items-center gap-3 rounded-xl border bg-surface p-3">
      <UserAvatar userId={userId} size="md" />
      <div className="min-w-0 flex-1">
        <p className="truncate font-semibold leading-tight" title={u.username}>{u.username}</p>
      </div>
      <FollowButton userId={userId} compact />
      <SafetyMenu userId={userId} className="-ml-1" />
    </div>
  );
}

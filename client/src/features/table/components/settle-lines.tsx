import { cn } from "@/lib/utils";
import { formatPts } from "@/features/bets/ledger";
import { useGetUser, useMe } from "@/features/store";
import { UserAvatar } from "@/features/social/components/user-avatar";

/** "A owes B $x" rows for a settlement through `hostId`; signed + colored from your side. */
export function SettleLines({
  lines,
  hostId,
  className,
}: {
  lines: { userId: string; amount: number }[];
  hostId: string;
  className?: string;
}) {
  const me = useMe();
  const getUser = useGetUser();
  return (
    <ul className={cn("space-y-1", className)}>
      {lines.map((line) => {
        const from = line.amount < 0 ? line.userId : hostId;
        const to = line.amount < 0 ? hostId : line.userId;
        return (
          <li key={line.userId} className="flex items-center justify-between gap-2 rounded-lg bg-surface/60 px-2 py-1.5 text-xs">
            <span className="flex min-w-0 flex-wrap items-center gap-1.5">
              <UserAvatar userId={from} size="xs" className="shrink-0 ring-0" />
              <span className="break-words">{getUser(from).username}</span>
              <span className="text-muted-foreground">owes</span>
              <UserAvatar userId={to} size="xs" className="shrink-0 ring-0" />
              <span className="break-words">{getUser(to).username}</span>
            </span>
            <span
              className={cn(
                "scoreboard shrink-0 text-sm",
                to === me ? "text-win" : from === me ? "text-loss" : "text-muted-foreground",
              )}
            >
              {to === me ? "+" : from === me ? "-" : ""}${formatPts(Math.abs(line.amount))}
            </span>
          </li>
        );
      })}
    </ul>
  );
}

import { cn } from "@/lib/utils";
import { useGetUser } from "../people";
import { HUE_BG } from "./hue";

const SIZES = {
  xs: "size-6 text-[10px]",
  sm: "size-8 text-xs",
  md: "size-10 text-sm",
  lg: "size-14 text-lg",
} as const;

export function UserAvatar({
  userId,
  size = "sm",
  className,
}: {
  userId: string;
  size?: keyof typeof SIZES;
  className?: string;
}) {
  const u = useGetUser()(userId);
  const initials = u.deleted
    ? "?"
    : u.username.slice(0, 2).toUpperCase();
  return (
    <span
      title={u.username}
      className={cn(
        "inline-flex shrink-0 items-center justify-center rounded-full font-display font-extrabold text-primary-foreground ring-2 ring-background",
        u.deleted ? "bg-muted text-muted-foreground" : HUE_BG[u.hue],
        SIZES[size],
        className,
      )}
    >
      {initials}
    </span>
  );
}

export function AvatarStack({ ids, max = 4 }: { ids: string[]; max?: number }) {
  const shown = ids.slice(0, max);
  const extra = ids.length - shown.length;
  return (
    <div className="flex -space-x-2">
      {shown.map((id) => (
        <UserAvatar key={id} userId={id} size="xs" />
      ))}
      {extra > 0 && (
        <span className="inline-flex size-6 items-center justify-center rounded-full bg-muted text-[10px] font-bold text-muted-foreground ring-2 ring-background">
          +{extra}
        </span>
      )}
    </div>
  );
}

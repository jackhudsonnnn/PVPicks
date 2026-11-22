import { cn } from "@/lib/utils";
import { HUE_BG } from "./hue";
import type { User } from "./api";
import { usePerson } from "./use-person";

const SIZES = {
  xs: "size-6 text-[10px]",
  sm: "size-8 text-xs",
  md: "size-10 text-sm",
  lg: "size-14 text-lg",
} as const;

export function PersonAvatar({
  id,
  user,
  expire,
  queryKey,
  size = "sm",
  className,
}: {
  id: string;
  user: User;
  expire: () => void;
  queryKey: readonly string[];
  size?: keyof typeof SIZES;
  className?: string;
}) {
  const person = usePerson(id, user, expire, queryKey);
  // Your own avatar shows your username's initials, not "YO".
  const label = id === user.id ? user.username : person?.name;
  return (
    <span
      title={person?.name}
      aria-hidden
      className={cn(
        "inline-flex shrink-0 items-center justify-center rounded-full font-display font-extrabold text-primary-foreground ring-2 ring-background",
        person?.hue ? HUE_BG[person.hue] : "bg-muted text-muted-foreground",
        SIZES[size],
        className,
      )}
    >
      {person?.deleted || !label ? "?" : label.slice(0, 2).toUpperCase()}
    </span>
  );
}

export function AvatarStack({
  ids,
  max = 4,
  total = ids.length,
  ...person
}: {
  ids: string[];
  max?: number;
  /** Everyone in the group, when `ids` is only a preview of it (for the "+N"). */
  total?: number;
  user: User;
  expire: () => void;
  queryKey: readonly string[];
}) {
  const shown = ids.slice(0, max);
  const extra = Math.max(0, total - shown.length);
  return (
    <div className="flex -space-x-2">
      {shown.map((id) => (
        <PersonAvatar key={id} id={id} size="xs" {...person} />
      ))}
      {extra > 0 && (
        <span className="inline-flex size-6 items-center justify-center rounded-full bg-muted text-[10px] font-bold text-muted-foreground ring-2 ring-background">
          +{extra}
        </span>
      )}
    </div>
  );
}

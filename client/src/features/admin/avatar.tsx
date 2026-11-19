import { HUE_BG } from "@/features/people";
import { cn } from "@/lib/utils";

const SIZES = {
  xs: "size-6 text-[10px]",
  sm: "size-8 text-xs",
  md: "size-10 text-sm",
  lg: "size-14 text-lg",
} as const;

/**
 * A person's avatar from what admin endpoints return (username and hue), the same look as the player app's. Admins
 * can't use the player profile reads `PersonAvatar` relies on.
 */
export function NameAvatar({
  name,
  hue,
  size = "sm",
  className,
}: {
  name: string | null;
  hue: number | null | undefined;
  size?: keyof typeof SIZES;
  className?: string;
}) {
  const color = hue && hue >= 1 && hue <= 5 ? HUE_BG[hue as keyof typeof HUE_BG] : null;
  return (
    <span
      aria-hidden
      className={cn(
        "inline-flex shrink-0 items-center justify-center rounded-full font-display font-extrabold ring-2 ring-background",
        color ? `${color} text-primary-foreground` : "bg-muted text-muted-foreground",
        SIZES[size],
        className,
      )}
    >
      {name ? name.slice(0, 2).toUpperCase() : "?"}
    </span>
  );
}

/** Avatar and name side by side; a button when `onOpen` is given (opens their audit). */
export function NameTag({
  name,
  hue,
  fallback = "Deleted player",
  size = "xs",
  onOpen,
  className,
}: {
  name: string | null;
  hue: number | null | undefined;
  fallback?: string;
  size?: keyof typeof SIZES;
  onOpen?: (() => void) | undefined;
  className?: string;
}) {
  const body = (
    <>
      <NameAvatar name={name} hue={hue} size={size} />
      <span className="min-w-0 truncate font-semibold">{name ?? fallback}</span>
    </>
  );
  const classes = cn("inline-flex min-w-0 items-center gap-1.5 align-middle", className);
  return onOpen ? (
    <button
      type="button"
      title="Open their audit"
      className={cn(classes, "rounded-full pr-1 hover:underline underline-offset-2")}
      onClick={onOpen}
    >
      {body}
    </button>
  ) : (
    <span className={classes}>{body}</span>
  );
}

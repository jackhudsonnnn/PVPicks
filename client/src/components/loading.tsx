import { Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { Logo } from "./logo";

/**
 * Loading placeholders. Every "loading" state uses these instead of text: a shape like what's coming (rows,
 * cards, bubbles, chips), announced once to screen readers by `label`. Showing the empty state instead would claim
 * there's nothing for a moment before content appears.
 */

/** One grey block; size it with `className`. */
function Skeleton({ className }: { className?: string }) {
  return <div aria-hidden className={cn("animate-pulse rounded-md bg-muted/60", className)} />;
}

function Status({
  label,
  className,
  children,
}: {
  label: string;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <div role="status" aria-label={label} className={className}>
      <span className="sr-only">{label}</span>
      {children}
    </div>
  );
}

/** Rows like a list of tickets, people or receipts. */
export function LoadingList({
  label,
  rows = 3,
  rowClassName = "h-16",
  className,
}: {
  label: string;
  rows?: number;
  rowClassName?: string;
  className?: string;
}) {
  return (
    <Status label={label} className={cn("space-y-3", className)}>
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className={cn("w-full rounded-xl", rowClassName)} />
      ))}
    </Status>
  );
}

/** A page's header and a list under it, for whole pages. */
export function LoadingPage({ label }: { label: string }) {
  return (
    <Status label={label} className="space-y-6 p-4 md:p-8">
      <div className="space-y-2">
        <Skeleton className="h-3 w-24" />
        <Skeleton className="h-8 w-56" />
      </div>
      <div className="space-y-3">
        {Array.from({ length: 4 }, (_, i) => (
          <Skeleton key={i} className="h-16 w-full rounded-xl" />
        ))}
      </div>
    </Status>
  );
}

/** Chat bubbles, alternating sides. */
export function LoadingChat() {
  return (
    <Status label="Loading chat" className="space-y-3 p-4">
      {["w-2/3", "ml-auto w-1/2", "w-1/2", "ml-auto w-2/5", "w-3/5"].map((width, i) => (
        <Skeleton key={i} className={cn("h-10 rounded-2xl", width)} />
      ))}
    </Status>
  );
}

/** A few small chips in a row, e.g. the score ticker. */
export function LoadingChips({ label, count = 4 }: { label: string; count?: number }) {
  return (
    <Status label={label} className="flex items-center gap-3 overflow-hidden px-4">
      {Array.from({ length: count }, (_, i) => (
        <Skeleton key={i} className="h-5 w-40 shrink-0" />
      ))}
    </Status>
  );
}

/** A stand-in for a short piece of text inside a sentence, e.g. a name. */
export function LoadingText({ className }: { className?: string }) {
  return (
    <span role="status" aria-label="Loading" className="inline-block align-middle">
      <span
        aria-hidden
        className={cn("inline-block h-3 w-16 animate-pulse rounded bg-muted/60", className)}
      />
    </span>
  );
}

/** For buttons that are working: put it before the label. */
export function Spinner({ className }: { className?: string }) {
  return <Loader2 aria-hidden className={cn("animate-spin", className)} />;
}

/** The whole screen, before anything is known (the session check), or while the app is busy (a reset). */
export function LoadingScreen({ label, message }: { label: string; message?: string }) {
  return (
    <main
      role="status"
      aria-label={label}
      className="flex min-h-dvh flex-col items-center justify-center gap-4 bg-background px-4"
    >
      <Logo asLink={false} className="animate-pulse" />
      {message && (
        <p className="flex items-center gap-2 text-sm text-muted-foreground">
          <Spinner className="size-4" /> {message}
        </p>
      )}
    </main>
  );
}

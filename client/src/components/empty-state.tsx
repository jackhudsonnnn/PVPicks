import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

/**
 * "Nothing here" message for empty lists. `boxed` draws the dashed outline used for
 * whole-page lists; plain is for small lists inside cards and dialogs.
 */
export function EmptyState({
  children,
  icon,
  action,
  boxed,
  className,
}: {
  children: ReactNode;
  icon?: ReactNode;
  action?: ReactNode;
  boxed?: boolean;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "flex flex-col items-center gap-2 text-center text-sm text-muted-foreground",
        boxed ? "rounded-xl border border-dashed p-10" : "py-6",
        className,
      )}
    >
      {icon}
      <p>{children}</p>
      {action}
    </div>
  );
}

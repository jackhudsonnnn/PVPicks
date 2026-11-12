import { Link } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

/** The P2Picks wordmark. Links home unless `asLink` is false; `badge` sits after it (e.g. Admin). */
export function Logo({
  className,
  asLink = true,
  badge,
}: {
  className?: string;
  asLink?: boolean;
  badge?: ReactNode;
}) {
  const mark = (
    <span className="font-display text-2xl font-black uppercase leading-none tracking-wide">
      <span className="text-primary">P2P</span>
      <span className="text-foreground">icks</span>
    </span>
  );
  return (
    <div className={cn("flex items-center gap-2", className)}>
      {asLink ? (
        <Link to="/" className="flex items-center gap-1.5" aria-label="P2Picks home">
          {mark}
        </Link>
      ) : (
        mark
      )}
      {badge}
    </div>
  );
}

import { Info } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * Heads-up shown when joining the app or a table: anyone can delete their account, and points
 * aren't collectable debts, so play with people you trust.
 * TODO: link to the Terms and Conditions once they're written.
 */
export function TrustNotice({ className }: { className?: string }) {
  return (
    <p
      className={cn(
        "flex gap-2 rounded-lg border border-dashed px-3 py-2.5 text-xs leading-relaxed text-muted-foreground",
        className,
      )}
    >
      <Info className="mt-0.5 size-3.5 shrink-0" />
      <span>
        Anyone can delete their account at any time. Their past tickets stay, shown as “Deleted
        user”, but the app can't collect what they owe. Settle up often and play with people you
        trust.
      </span>
    </p>
  );
}

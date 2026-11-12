import { formatPts } from "@/features/bets/ledger";
import { cn } from "@/lib/utils";

/** A points amount with its sign, green when up, red when down, muted at zero. */
export function SignedPoints({
  value,
  className,
  unit,
}: {
  value: number;
  className?: string;
  /** e.g. "pts", shown after the number. */
  unit?: string;
}) {
  return (
    <span
      className={cn(
        "scoreboard",
        value > 0 ? "text-win" : value < 0 ? "text-loss" : "text-muted-foreground",
        className,
      )}
    >
      {formatPts(value, true)}
      {unit && ` ${unit}`}
    </span>
  );
}

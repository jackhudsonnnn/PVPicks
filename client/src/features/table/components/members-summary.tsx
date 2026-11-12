import { ChevronRight } from "lucide-react";
import type { Table } from "@/features/table/types";
import { AvatarStack } from "@/features/social/components/user-avatar";

/** Top of About/Host: who's at the table; tap to open Peers. */
export function MembersSummary({
  table,
  onOpen,
}: {
  table: Table;
  onOpen?: (() => void) | undefined;
}) {
  return (
    <button
      type="button"
      onClick={onOpen}
      className="flex w-full items-center gap-3 rounded-xl border bg-surface px-4 py-3 text-left transition-colors hover:border-primary/60"
    >
      <AvatarStack ids={table.memberIds} max={5} />
      <span className="min-w-0 flex-1 text-sm font-semibold">
        {table.memberIds.length} {table.memberIds.length === 1 ? "member" : "members"}
      </span>
      <ChevronRight className="size-4 shrink-0 text-muted-foreground" />
    </button>
  );
}

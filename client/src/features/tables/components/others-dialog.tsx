import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { ScrollContainer } from "@/components/scroll-container";
import { type User, PersonAvatar, PersonName } from "@/features/people";

/**
 * The felt's "+N others" seat when it isn't showing a ticket's players: everyone at the table who didn't fit around
 * it. Tapping one opens their profile. (The Peers tab is separate: balances for everyone.)
 */
export function OthersDialog({
  ids,
  open,
  onOpenChange,
  onSelectUser,
  user,
  expire,
  peopleKey,
}: {
  ids: string[];
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSelectUser: (id: string) => void;
  user: User;
  expire: () => void;
  peopleKey: readonly string[];
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex max-h-[85dvh] max-w-sm flex-col gap-4 p-0">
        <DialogHeader className="px-5 pt-5 text-left">
          <DialogTitle>{ids.length} more at the table</DialogTitle>
          <DialogDescription>Everyone who didn't fit around the felt.</DialogDescription>
        </DialogHeader>
        <ScrollContainer isVisible={false} className="min-h-0 flex-1 px-5 pb-5">
          <ul className="grid gap-1.5">
            {ids.map((id) => (
              <li key={id}>
                <button
                  type="button"
                  onClick={() => onSelectUser(id)}
                  className="flex w-full items-center gap-3 rounded-lg border bg-surface px-3 py-2.5 text-left text-sm font-semibold transition-colors hover:border-primary/60"
                >
                  <PersonAvatar
                    id={id}
                    size="sm"
                    user={user}
                    expire={expire}
                    queryKey={peopleKey}
                  />
                  <span className="min-w-0 flex-1 truncate">
                    <PersonName id={id} user={user} expire={expire} queryKey={peopleKey} />
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </ScrollContainer>
      </DialogContent>
    </Dialog>
  );
}

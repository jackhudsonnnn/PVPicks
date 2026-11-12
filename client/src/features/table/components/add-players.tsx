import { Clock, Heart, Plus, Send, UserCheck, Users } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ScrollContainer } from "@/components/scroll-container";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { SettingsSection } from "@/components/settings";
import { invitePrefsOf, useAppStore, useMe, useGetUser } from "@/features/store";
import type { Table } from "@/features/table/types";
import { UserAvatar } from "@/features/social/components/user-avatar";
import { followersOf, friendsOf, isFriend } from "@/features/social/follows";
import { addablePlayers, hasPendingInvite, inviteOutcome } from "@/features/social/invites";

/** Host tool: add friends and followers, one at a time or all at once. */
export function AddPlayersSection({ table }: { table: Table }) {
  const me = useMe();
  const getUser = useGetUser();
  const { state, actions } = useAppStore();
  const f = state.follows;
  const prefsOf = (id: string) => invitePrefsOf(state, id);
  const { friends, followers } = addablePlayers(f, prefsOf, table, state.tableInvites, me);

  // Friends first, then other followers; everyone not already at the table.
  const people = [...new Set([...friendsOf(f, me), ...followersOf(f, me)])].filter(
    (id) => !table.memberIds.includes(id),
  );

  return (
    <SettingsSection
      title="Add players"
      description="Your friends and followers. Their settings decide if they join right away or get a request."
    >
      <div className="grid grid-cols-2 gap-2 px-4 py-3 sm:px-5">
        <Button
          size="sm"
          variant="outline"
          disabled={friends.length === 0}
          onClick={() => actions.addMembers(table.id, friends)}
        >
          <Users /> All friends · {friends.length}
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={followers.length === 0}
          onClick={() => actions.addMembers(table.id, followers)}
        >
          <UserCheck /> All followers · {followers.length}
        </Button>
      </div>
      {people.length === 0 ? (
        <p className="px-4 py-4 text-sm text-muted-foreground sm:px-5">
          All your friends and followers are already here.
        </p>
      ) : (
        <ScrollContainer isVisible={false} className="max-h-72">
          <ul className="divide-y">
            <AnimatedList>
              {people.map((id) => {
                const friend = isFriend(f, me, id);
                const pending = hasPendingInvite(state.tableInvites, table.id, id);
                const outcome = inviteOutcome(f, prefsOf(id), id, me);
                return (
                  <AnimatedItem
                    as="li"
                    key={id}
                    className="flex items-center gap-3 px-4 py-2.5 sm:px-5"
                  >
                    <UserAvatar userId={id} size="sm" className="ring-surface" />
                    <span className="min-w-0 flex-1">
                      <span className="block break-words text-sm font-semibold">
                        {getUser(id).username}
                      </span>
                      <span className="flex items-center gap-1 font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
                        {friend ? (
                          <>
                            <Heart className="size-3 text-primary" /> Friend
                          </>
                        ) : (
                          "Follows you"
                        )}
                      </span>
                    </span>
                    {pending ? (
                      <span className="flex items-center gap-1 font-mono text-[10px] font-bold uppercase text-invite">
                        <Clock className="size-3" /> Requested
                      </span>
                    ) : outcome === "blocked" ? (
                      <span className="font-mono text-[10px] font-bold uppercase text-muted-foreground">
                        Not open
                      </span>
                    ) : (
                      <Button
                        size="sm"
                        variant="outline"
                        className="h-8 px-2.5 text-xs"
                        onClick={() => actions.addMembers(table.id, [id])}
                      >
                        {outcome === "add" ? (
                          <>
                            <Plus /> Add
                          </>
                        ) : (
                          <>
                            <Send /> Request
                          </>
                        )}
                      </Button>
                    )}
                  </AnimatedItem>
                );
              })}
            </AnimatedList>
          </ul>
        </ScrollContainer>
      )}
    </SettingsSection>
  );
}

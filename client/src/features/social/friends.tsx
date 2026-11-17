import { useState } from "react";
import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { EmptyState } from "@/components/empty-state";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { backendErrorMessage } from "@/lib/api-client";
import { type User, PersonAvatar, PersonName } from "@/features/people";
import { ActionStatus } from "@/components/action-status";
import { ConfirmAction } from "@/components/confirm-action";
import { SettlementReceipts } from "@/features/settlements";
import { userKey } from "@/lib/query-keys";
import { searchUsers, type Relationships } from "./api";
import { useSocialActions } from "./actions";
import { useRelationships } from "./queries";
import { peopleIn, searchTerm } from "./rules";
import { LoadingList } from "@/components/loading";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { PageEdge } from "@/components/client-pages";
import { ScrollContainer } from "@/components/scroll-container";
import { useClientPages } from "@/components/use-client-pages";

const noRelationships: Relationships = {
  followingIds: [],
  followerIds: [],
  friendIds: [],
  blockedIds: [],
};

type Tab = "friends" | "requests" | "following" | "blocked" | "find";

/** The line under the tabs, styled like the settle-ups' description. */
const HINTS: Record<Tab, string> = {
  friends: "People you follow who follow you back, newest first.",
  requests: "People who follow you but you don't follow back. Follow them to be friends.",
  following: "Everyone you follow, newest first.",
  blocked: "People you blocked. Unblocking does not restore follows.",
  find: "Search usernames across P2Picks.",
};

/**
 * Friends: settle-ups, then your people in tabs (friends, friend requests, following, blocked, find), each newest
 * first. Followers are friends plus requests, so they have no tab of their own.
 */
export function FriendsPage({ user, expire }: { user: User; expire: () => void }) {
  const queryClient = useQueryClient();
  const relationships = useRelationships(user.id);
  const actions = useSocialActions(
    user.id,
    async () => {
      await queryClient.invalidateQueries({ queryKey: userKey(user.id) });
    },
    expire,
  );
  const [tab, setTab] = useState<Tab>("friends");
  const names = userKey(user.id, "people");
  const data = relationships.data ?? noRelationships;

  const row = (id: string) => (
    <PersonRow
      key={id}
      id={id}
      user={user}
      expire={expire}
      names={names}
      relationships={data}
      actions={actions}
    />
  );

  return (
    <div className="space-y-10">
      <h1 className="sr-only">Friends</h1>
      <SettlementReceipts user={user} expire={expire} />

      <section className="space-y-4" aria-labelledby="network-heading">
        {/* Same heading style as Settle-ups above. */}
        <h2
          id="network-heading"
          className="font-mono text-xs font-bold uppercase tracking-[0.2em] text-muted-foreground"
        >
          My network
        </h2>
        <ActionStatus
          actions={actions}
          pendingText="A follow, block or invitation change may not have been saved."
          checkText="Check your people lists first."
        />
        {relationships.isPending ? (
          <LoadingList label="Loading people" rows={4} rowClassName="h-14" />
        ) : relationships.isError ? (
          <div className="space-y-3">
            <p role="alert">{backendErrorMessage(relationships.error)}</p>
            <Button variant="outline" onClick={() => void relationships.refetch()}>
              Try again
            </Button>
          </div>
        ) : (
          <>
            <SegmentedTabs
              size="sm"
              ariaLabel="People"
              tabs={[
                { id: "friends", label: `Friends · ${peopleIn("friends", data).length}` },
                { id: "requests", label: `Requests · ${peopleIn("requests", data).length}` },
                { id: "following", label: `Following · ${data.followingIds.length}` },
                { id: "blocked", label: `Blocked · ${data.blockedIds.length}` },
                { id: "find", label: "Find" },
              ]}
              value={tab}
              onChange={setTab}
            />
            <p className="text-sm text-muted-foreground">{HINTS[tab]}</p>
            {tab === "find" ? (
              <FindPeople user={user} row={row} />
            ) : peopleIn(tab, data).length === 0 ? (
              <EmptyState boxed>No one here yet.</EmptyState>
            ) : (
              <PeopleRow key={tab} ids={peopleIn(tab, data)} row={row} label={HINTS[tab]} />
            )}
          </>
        )}
      </section>
    </div>
  );
}

function FindPeople({ user, row }: { user: User; row: (id: string) => React.ReactNode }) {
  const [text, setText] = useState("");
  const term = searchTerm(text);
  const results = useInfiniteQuery({
    queryKey: userKey(user.id, "search", term ?? ""),
    queryFn: ({ pageParam, signal }) => searchUsers(term!, pageParam, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
    enabled: term !== null,
    retry: false,
  });
  const people = results.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <div className="space-y-3">
      <Input
        aria-label="Search usernames"
        placeholder="Search usernames"
        value={text}
        onChange={(event) => setText(event.target.value)}
      />
      {text.trim() && term === null ? (
        <p className="text-sm text-muted-foreground">
          Usernames use up to 15 letters, numbers and underscores.
        </p>
      ) : term === null ? null : results.isPending ? (
        <LoadingList label="Searching" rows={2} rowClassName="h-14" />
      ) : results.isError ? (
        <p role="alert">{backendErrorMessage(results.error)}</p>
      ) : people.length === 0 ? (
        <EmptyState boxed>No one found.</EmptyState>
      ) : (
        <PeopleRow
          key={term}
          ids={people.map((person) => person.id)}
          row={row}
          label="Search results"
          more={
            results.hasNextPage && !results.isFetchingNextPage
              ? () => void results.fetchNextPage()
              : undefined
          }
        />
      )}
    </div>
  );
}

/**
 * People as a sideways row of cards, the same size as the settle-up cards above. Long lists mount 20 cards at a
 * time and add more as you scroll toward the end; `more` loads the next server page instead (search).
 */
function PeopleRow({
  ids,
  row,
  label,
  more,
}: {
  ids: string[];
  row: (id: string) => React.ReactNode;
  label: string;
  more?: (() => void) | undefined;
}) {
  const pages = useClientPages(ids);
  const onMore = pages.hasMore ? pages.more : more;
  return (
    <ScrollContainer
      isHorizontal
      isVisible={false}
      aria-label={label}
      className="snap-x gap-3 pb-2 md:scrollbar-thin"
    >
      <AnimatedList>
        {pages.shown.map((id) => (
          <AnimatedItem key={id} isHorizontal className="shrink-0 snap-start">
            {row(id)}
          </AnimatedItem>
        ))}
      </AnimatedList>
      {onMore && <PageEdge onMore={onMore} label="More" className="w-24 shrink-0 self-stretch" />}
    </ScrollContainer>
  );
}

/** One person as a card: who they are to you, then big follow and block buttons that each ask first. */
function PersonRow({
  id,
  user,
  expire,
  names,
  relationships,
  actions,
}: {
  id: string;
  user: User;
  expire: () => void;
  names: string[];
  relationships: Relationships;
  actions: ReturnType<typeof useSocialActions>;
}) {
  const following = relationships.followingIds.includes(id);
  const followsYou = relationships.followerIds.includes(id);
  const blocked = relationships.blockedIds.includes(id);
  const disabled = actions.busy || !!actions.pending || !!actions.storageError;
  const button = "h-10 w-full";
  return (
    <div className="flex min-h-48 w-60 flex-col gap-3 rounded-lg border bg-surface p-3">
      <div className="flex min-w-0 items-center gap-3">
        <PersonAvatar id={id} user={user} expire={expire} queryKey={names} size="lg" />
        <div className="min-w-0">
          <p className="break-words font-semibold leading-tight">
            <PersonName id={id} user={user} expire={expire} queryKey={names} />
          </p>
          <p className="mt-0.5 font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
            {blocked
              ? "Blocked"
              : following && followsYou
                ? "Friends"
                : followsYou
                  ? "Follows you"
                  : following
                    ? "You follow them"
                    : "Not connected"}
          </p>
        </div>
      </div>
      <div className="mt-auto grid gap-2">
        {!blocked &&
          (following ? (
            <ConfirmAction
              label="Unfollow"
              title="Unfollow this person?"
              description={
                followsYou
                  ? "You'll no longer be friends. They keep following you, and you can follow them again later."
                  : "You can follow them again later."
              }
              disabled={disabled}
              className={button}
              onConfirm={() => actions.start({ kind: "follow", userId: id, follow: false })}
            />
          ) : (
            <ConfirmAction
              label="Follow"
              title="Follow this person?"
              description={
                followsYou
                  ? "They already follow you, so you'll be friends. Friends can add each other to tables, depending on invite settings."
                  : "They'll be able to add you to their tables, depending on your invite settings. If they follow you back, you'll be friends."
              }
              disabled={disabled}
              variant="default"
              className={button}
              onConfirm={() => actions.start({ kind: "follow", userId: id, follow: true })}
            />
          ))}
        {blocked ? (
          <ConfirmAction
            label="Unblock"
            title="Unblock this person?"
            description="They'll see your messages again and you can follow each other. Follows you had before the block aren't restored."
            disabled={disabled}
            className={button}
            onConfirm={() => actions.start({ kind: "block", userId: id, block: false })}
          />
        ) : (
          <ConfirmAction
            label="Block"
            title="Block this person?"
            description="You'll stop following each other, pending invitations between you are cancelled, and their messages are hidden from you. Unblocking later does not restore follows."
            disabled={disabled}
            variant="destructive"
            className={button}
            onConfirm={() => actions.start({ kind: "block", userId: id, block: true })}
          />
        )}
      </div>
    </div>
  );
}

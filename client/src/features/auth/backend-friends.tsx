import { useState } from "react";
import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { EmptyState } from "@/components/empty-state";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { backendErrorMessage, type BackendUser } from "./backend-api";
import { ActionStatus } from "./backend-action-status";
import { ConfirmAction } from "./backend-confirm-action";
import { PersonName } from "./backend-person-name";
import { BackendSettlements } from "./backend-settlements";
import { userKey } from "./query-keys";
import { searchUsers, type Relationships } from "./social-api";
import { useSocialActions } from "./social-actions";
import { useRelationships } from "./social-queries";

const noRelationships: Relationships = { followingIds: [], followerIds: [], blockedIds: [] };

type Tab = "friends" | "following" | "followers" | "blocked" | "find";

const HINTS: Record<Tab, string> = {
  friends: "People you follow who follow you back.",
  following: "Everyone you follow.",
  followers: "Everyone who follows you. Only followers can be added to your tables.",
  blocked: "People you blocked. Unblocking does not restore follows.",
  find: "Search usernames across P2Picks.",
};

/** Usernames are 1–15 lowercase letters, digits or underscores; search uses the same rule. */
export function searchTerm(text: string): string | null {
  const term = text.trim().toLowerCase();
  return /^[a-z0-9_]{1,15}$/.test(term) ? term : null;
}

export function peopleIn(tab: Exclude<Tab, "find">, relationships: Relationships): string[] {
  const { followingIds, followerIds, blockedIds } = relationships;
  switch (tab) {
    case "friends":
      return followingIds.filter((id) => followerIds.includes(id));
    case "following":
      return followingIds;
    case "followers":
      return followerIds;
    case "blocked":
      return blockedIds;
  }
}

/** Friends: settle-ups, new followers and people, like the prototype's Friends tab. */
export function BackendFriends({ user, expire }: { user: BackendUser; expire: () => void }) {
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
  const newFollowers = data ? data.followerIds.filter((id) => !data.followingIds.includes(id)) : [];

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
      <h1 className="font-display text-3xl font-bold uppercase">Friends</h1>
      <BackendSettlements user={user} expire={expire} />

      <section className="space-y-3" aria-labelledby="people-heading">
        <h2 id="people-heading" className="font-display text-2xl font-bold uppercase">
          People
        </h2>
        <ActionStatus
          actions={actions}
          pendingText="A follow, block or invitation change may not have been saved."
          checkText="Check your people lists first."
        />
        {relationships.isPending ? (
          <p>Loading people…</p>
        ) : relationships.isError ? (
          <div className="space-y-3">
            <p role="alert">{backendErrorMessage(relationships.error)}</p>
            <Button variant="outline" onClick={() => void relationships.refetch()}>
              Try again
            </Button>
          </div>
        ) : (
          <>
            {newFollowers.length > 0 && (
              <div className="space-y-2">
                <h3 className="font-semibold">New followers</h3>
                <p className="text-sm text-muted-foreground">
                  They follow you. Follow back to be friends.
                </p>
                <ul className="space-y-2">{newFollowers.map(row)}</ul>
              </div>
            )}
            <SegmentedTabs
              size="sm"
              ariaLabel="People"
              tabs={[
                { id: "friends", label: `Friends · ${peopleIn("friends", data).length}` },
                { id: "following", label: `Following · ${data.followingIds.length}` },
                { id: "followers", label: `Followers · ${data.followerIds.length}` },
                { id: "blocked", label: `Blocked · ${data.blockedIds.length}` },
                { id: "find", label: "Find" },
              ]}
              value={tab}
              onChange={setTab}
            />
            <p className="text-xs text-muted-foreground">{HINTS[tab]}</p>
            {tab === "find" ? (
              <FindPeople user={user} row={row} />
            ) : peopleIn(tab, data).length === 0 ? (
              <EmptyState boxed>No one here yet.</EmptyState>
            ) : (
              <ul className="space-y-2">{peopleIn(tab, data).map(row)}</ul>
            )}
          </>
        )}
      </section>
    </div>
  );
}

function FindPeople({ user, row }: { user: BackendUser; row: (id: string) => React.ReactNode }) {
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
        <p>Searching…</p>
      ) : results.isError ? (
        <p role="alert">{backendErrorMessage(results.error)}</p>
      ) : people.length === 0 ? (
        <EmptyState boxed>No one found.</EmptyState>
      ) : (
        <>
          <ul className="space-y-2">{people.map((person) => row(person.id))}</ul>
          {results.hasNextPage && (
            <Button
              variant="outline"
              size="sm"
              disabled={results.isFetchingNextPage}
              onClick={() => void results.fetchNextPage()}
            >
              Show more
            </Button>
          )}
        </>
      )}
    </div>
  );
}

function PersonRow({
  id,
  user,
  expire,
  names,
  relationships,
  actions,
}: {
  id: string;
  user: BackendUser;
  expire: () => void;
  names: string[];
  relationships: Relationships;
  actions: ReturnType<typeof useSocialActions>;
}) {
  const following = relationships.followingIds.includes(id);
  const followsYou = relationships.followerIds.includes(id);
  const blocked = relationships.blockedIds.includes(id);
  const disabled = actions.busy || !!actions.pending || !!actions.storageError;
  return (
    <li className="flex flex-wrap items-center justify-between gap-3 rounded-lg border bg-surface p-3">
      <span>
        <span className="font-semibold">
          <PersonName id={id} user={user} expire={expire} queryKey={names} />
        </span>
        <span className="block text-xs text-muted-foreground">
          {blocked
            ? "Blocked"
            : following && followsYou
              ? "Friends"
              : followsYou
                ? "Follows you"
                : following
                  ? "You follow them"
                  : ""}
        </span>
      </span>
      <span className="flex flex-wrap gap-2">
        {!blocked && (
          <Button
            size="sm"
            variant={following ? "outline" : "default"}
            disabled={disabled}
            onClick={() => actions.start({ kind: "follow", userId: id, follow: !following })}
          >
            {following ? "Unfollow" : followsYou ? "Follow back" : "Follow"}
          </Button>
        )}
        {blocked ? (
          <Button
            size="sm"
            variant="outline"
            disabled={disabled}
            onClick={() => actions.start({ kind: "block", userId: id, block: false })}
          >
            Unblock
          </Button>
        ) : (
          <ConfirmAction
            label="Block"
            title="Block this person?"
            description="You'll stop following each other, pending invitations between you are cancelled, and their messages are hidden from you. Unblocking later does not restore follows."
            disabled={disabled}
            onConfirm={() => actions.start({ kind: "block", userId: id, block: true })}
          />
        )}
      </span>
    </li>
  );
}

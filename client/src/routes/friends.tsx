import { EmptyState } from "@/components/empty-state";
import { SearchInput } from "@/components/search-input";
import { FollowButton } from "@/features/social/components/follow-button";
import { PersonRow } from "@/features/social/components/person-row";
import { SettleCard } from "@/features/social/components/settle-card";
import { createFileRoute } from "@tanstack/react-router";
import { useMemo, useState } from "react";
import { ChevronDown, Heart, Search, UserCheck, Users } from "lucide-react";
import { cn } from "@/lib/utils";
import { PageHeader } from "@/components/page-header";
import { useAppStore, visiblePeople, useMe, useGetUser } from "@/features/store";
import { SafetyMenu } from "@/features/social/components/safety-dialogs";
import { UserAvatar } from "@/features/social/components/user-avatar";
import { CreateTableDialog } from "@/features/table/components/create-table-dialog";
import { settleGroups } from "@/features/social/settle-groups";
import { ScrollContainer } from "@/components/scroll-container";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { followersOf, followingOf, friendsOf, relationOf } from "@/features/social/follows";

export const Route = createFileRoute("/friends")({
  head: () => ({
    meta: [
      { title: "Friends | P2Picks" },
      {
        name: "description",
        content: "Follow people, follow back, and see your friends and followers on P2Picks.",
      },
      { property: "og:title", content: "Friends | P2Picks" },
      {
        property: "og:description",
        content: "Follow people, follow back, and see your friends and followers on P2Picks.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: FriendsPage,
});

type ListTab = "friends" | "following" | "followers" | "find";

const TAB_HINT: Record<ListTab, string> = {
  friends: "People you follow who follow you back.",
  following: "Everyone you follow",
  followers: "Everyone who follows you.",
  find: "Search usernames across all of P2Picks",
};

function FriendsPage() {
  const me = useMe();
  const getUser = useGetUser();
  const { state } = useAppStore();
  const [q, setQ] = useState("");
  const [tab, setTab] = useState<ListTab>("friends");
  const f = state.follows;

  // Deleted accounts and anyone blocked (either way) are left out of every list.
  const people = new Set(visiblePeople(state).map((u) => u.id));
  const shown = (ids: string[]) => ids.filter((id) => people.has(id));
  const friends = shown(friendsOf(f, me));
  const following = shown(followingOf(f, me));
  const followers = shown(followersOf(f, me));
  // Followers you haven't followed back yet.
  const newFollowers = followers.filter((id) => !following.includes(id));

  const term = q.trim().toLowerCase();
  const matches = (id: string) => {
    const u = getUser(id);
    return u.username.toLowerCase().includes(term);
  };
  const others = [...people];
  const base =
    tab === "friends"
      ? friends
      : tab === "following"
        ? following
        : tab === "followers"
          ? followers
          : term
            ? others
            : others.filter((id) => relationOf(f, me, id) === "none");
  const list = term ? base.filter(matches) : base;

  // TODO(backend): server returns grouped settle-ups; until then memo on the messages array.
  const owed = useMemo(() => settleGroups(state.messages, me, true), [state.messages, me]);

  return (
    <div className="mx-auto max-w-4xl space-y-10 px-4 py-6 md:px-8 md:py-10">
      <PageHeader eyebrow="Social" title="Friends" action={<CreateTableDialog />} />

      <CollapsibleSection title="Settle-ups" count={owed.length}>
        {owed.length === 0 ? (
          <EmptyState boxed className="h-48 justify-center px-4 py-0">
            Find some friends to bet with.
          </EmptyState>
        ) : (
          <ScrollContainer
            isHorizontal
            isVisible={false}
            className="-mx-4 snap-x scroll-px-4 gap-3 px-4 pb-2 md:mx-0 md:scroll-px-0 md:px-0"
          >
            <AnimatedList>
              {owed.map((group) => (
                <AnimatedItem
                  key={group.otherUserId}
                  isHorizontal
                  className="shrink-0 snap-start"
                >
                  <SettleCard group={group} />
                </AnimatedItem>
              ))}
            </AnimatedList>
          </ScrollContainer>
        )}
      </CollapsibleSection>

      <CollapsibleSection title="New followers" count={newFollowers.length}>
        {newFollowers.length === 0 ? (
          <EmptyState boxed className="h-48 justify-center px-4 py-0">
            Nobody wants to be your friend right now.
          </EmptyState>
        ) : (
          <ScrollContainer
            isHorizontal
            isVisible={false}
            className="-mx-4 snap-x scroll-px-4 gap-3 px-4 pb-2 md:mx-0 md:scroll-px-0 md:px-0"
          >
            <AnimatedList>
              {newFollowers.map((id) => (
                <AnimatedItem
                  key={id}
                  isHorizontal
                  className="flex h-40 w-60 shrink-0 snap-start flex-col gap-3 rounded-xl border border-primary/40 bg-primary/5 p-3"
                >
                  <div className="flex min-w-0 items-center gap-3">
                    <UserAvatar userId={id} size="md" />
                    <div className="min-w-0 flex-1">
                      <p className="break-words font-semibold leading-tight">{getUser(id).username}</p>
                    </div>
                  </div>
                  <p className="text-xs text-muted-foreground">
                    Follows you. Follow back to be friends.
                  </p>
                  <div className="mt-auto flex items-center gap-1 [&>button:first-child]:ml-0 [&>button:first-child]:flex-1">
                    <FollowButton userId={id} />
                    <SafetyMenu userId={id} />
                  </div>
                </AnimatedItem>
              ))}
            </AnimatedList>
          </ScrollContainer>
        )}
      </CollapsibleSection>

      <section className="space-y-3">
        <Label>Search people and friends</Label>
        <div className="flex flex-col gap-2 sm:flex-row">
          <Select value={tab} onValueChange={(v) => setTab(v as ListTab)}>
            <SelectTrigger
              aria-label="People"
              className="h-11 w-full bg-surface sm:w-48 sm:shrink-0"
            >
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="friends">
                <span className="flex items-center gap-2">
                  <Heart /> Friends · {friends.length}
                </span>
              </SelectItem>
              <SelectItem value="following">
                <span className="flex items-center gap-2">
                  <UserCheck /> Following · {following.length}
                </span>
              </SelectItem>
              <SelectItem value="followers">
                <span className="flex items-center gap-2">
                  <Users /> Followers · {followers.length}
                </span>
              </SelectItem>
              <SelectItem value="find">
                <span className="flex items-center gap-2">
                  <Search /> Find people
                </span>
              </SelectItem>
            </SelectContent>
          </Select>
          <SearchInput
            value={q}
            onChange={setQ}
            label="Search people"
            placeholder={tab === "find" ? "Search username" : "Filter this list"}
            className="flex-1"
          />
        </div>
        <p className="text-xs text-muted-foreground">{TAB_HINT[tab]}</p>
        <ScrollContainer className="max-h-96">
          {list.length === 0 ? (
            <EmptyState>{term ? "No one found." : "No one here yet."}</EmptyState>
          ) : (
            <div className="grid gap-2 sm:grid-cols-2">
              <AnimatedList key={tab}>
                {list.map((id) => (
                  <AnimatedItem key={id} className="min-w-0">
                    <PersonRow userId={id} />
                  </AnimatedItem>
                ))}
              </AnimatedList>
            </div>
          )}
        </ScrollContainer>
      </section>
    </div>
  );
}

/** Section with a caret to fold it; starts open only when it has items. */
function CollapsibleSection({
  title,
  count,
  children,
}: {
  title: string;
  count: number;
  children: React.ReactNode;
}) {
  const [open, setOpen] = useState(count > 0);
  return (
    <section className="space-y-3">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        className="flex items-center gap-1.5 text-muted-foreground transition-colors hover:text-foreground"
      >
        <ChevronDown className={cn("size-4 transition-transform", !open && "-rotate-90")} />
        <Label>
          {title}
          {count > 0 ? ` · ${count}` : ""}
        </Label>
      </button>
      {open && children}
    </section>
  );
}

function Label({ children }: { children: React.ReactNode }) {
  return (
    <h2 className="font-mono text-[11px] font-bold uppercase tracking-widest text-muted-foreground">
      {children}
    </h2>
  );
}

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { ActionStatus } from "@/components/action-status";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { ScrollContainer } from "@/components/scroll-container";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { PageEdge } from "@/components/client-pages";
import { useClientPages } from "@/components/use-client-pages";
import { userKey } from "@/lib/query-keys";
import { NAV_SIDEBAR, useMediaQuery } from "@/lib/use-media-query";
import { cn } from "@/lib/utils";
import { type User, usePerson } from "@/features/people";
import {
  type InviteActions,
  type TableInvite,
  useInviteActions,
  useInvites,
} from "@/features/social";
import { listTables } from "@/features/tables/api";
import { byRecentBet, newestFirst } from "@/features/tables/table-order";
import { InviteDialog } from "./invite-dialog";

type Props = { user: User; pathname: string; expire: () => void };
type Tab = "tables" | "requests";

/**
 * The sidebar's tables and table requests (desktop only; phones answer requests on Home).
 *
 * Always the same My tables / Requests switch. Home already shows your tables as cards, so there My tables is off
 * (and the sidebar is empty without requests); Requests is off while none are waiting.
 *
 * Tables go by most recent bet and requests newest first, in the order last fetched (see `byRecentBet`).
 */
export function SidebarTables(props: Props) {
  // The sidebar is always in the page but hidden below its breakpoint: mount this only when it shows, so its
  // invite-actions hook never runs alongside Home's (which phones use).
  const shown = useMediaQuery(NAV_SIDEBAR);
  return shown ? <SidebarLists {...props} /> : null;
}

function SidebarLists({ user, pathname, expire }: Props) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const tables = useQuery({
    queryKey: userKey(user.id, "list"),
    queryFn: ({ signal }) => listTables(signal),
    retry: false,
  });
  const invites = useInvites(user.id);
  const actions = useInviteActions(
    user.id,
    async () => {
      await queryClient.invalidateQueries({ queryKey: userKey(user.id) });
    },
    expire,
    (tableId) => void navigate({ to: "/tables/$tableId", params: { tableId } }),
  );
  const [chosen, setChosen] = useState<Tab>("tables");
  const requests = newestFirst(invites.data ?? []);
  const mine = byRecentBet(tables.data ?? []);
  const requestPages = useClientPages(requests);
  const tablePages = useClientPages(mine);
  const home = pathname === "/";
  // Answering the last request leaves nothing to switch to.
  const tab: Tab = home ? "requests" : requests.length ? chosen : "tables";
  const hasStatus = !!(actions.message || actions.storageError || actions.pending);
  const status = hasStatus && (
    <div className="shrink-0 px-5 pt-2 text-xs">
      <ActionStatus
        actions={actions}
        pendingText="A request response may not have been saved."
        checkText="Check your tables and requests first."
      />
    </div>
  );

  // Nothing to list: keep only the outcome of an answer (e.g. "Joined the table.") if there is one.
  if (home && requests.length === 0) return status || null;
  if (!home && requests.length === 0 && mine.length === 0) return status || null;
  return (
    // Only the list grows and scrolls; the header above it and the points box below never move.
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="mt-4 shrink-0 px-3">
        <SegmentedTabs<Tab>
          size="sm"
          ariaLabel="Tables or requests"
          tabs={[
            { id: "tables", label: "My tables", disabled: home },
            { id: "requests", label: "Requests", disabled: requests.length === 0 },
          ]}
          value={tab}
          onChange={setChosen}
        />
      </div>
      {status}
      <ScrollContainer isVisible={false} className="flex min-h-0 flex-1 flex-col gap-0.5 px-3 py-2">
        <AnimatedList key={tab}>
          {tab === "requests"
            ? requestPages.shown.map((invite) => (
                <AnimatedItem key={`invite-${invite.id}`} className="flex flex-col">
                  <RequestRow invite={invite} actions={actions} user={user} expire={expire} />
                </AnimatedItem>
              ))
            : tablePages.shown.map((table) => (
                <AnimatedItem key={`table-${table.id}`} className="flex flex-col">
                  <Link
                    to="/tables/$tableId"
                    params={{ tableId: table.id }}
                    aria-current={pathname === `/tables/${table.id}` ? "page" : undefined}
                    className={cn(
                      ROW,
                      "border-transparent",
                      pathname === `/tables/${table.id}`
                        ? "bg-sidebar-accent text-foreground"
                        : "text-muted-foreground hover:bg-sidebar-accent/60 hover:text-foreground",
                    )}
                  >
                    <span className="text-base">{table.emoji}</span>
                    <span className="truncate">{table.name}</span>
                  </Link>
                </AnimatedItem>
              ))}
        </AnimatedList>
        {(tab === "requests" ? requestPages.hasMore : tablePages.hasMore) && (
          <PageEdge
            onMore={tab === "requests" ? requestPages.more : tablePages.more}
            label={tab === "requests" ? "Older requests" : "More tables"}
            className="mt-1"
            margin="200px"
          />
        )}
      </ScrollContainer>
    </div>
  );
}

/** One sidebar row: tables and requests are the same height, so switching lists never resizes anything. */
const ROW =
  "flex h-9 items-center gap-3 rounded-lg border px-3 text-left text-sm transition-colors";

/** A request in the sidebar: the table and who sent it. Opening it asks to accept or decline, right there. */
function RequestRow({
  invite,
  actions,
  user,
  expire,
}: {
  invite: TableInvite;
  actions: InviteActions;
  user: User;
  expire: () => void;
}) {
  const from = usePerson(invite.fromId, user, expire, userKey(user.id, "people"));
  return (
    <InviteDialog
      invite={invite}
      actions={actions}
      user={user}
      expire={expire}
      triggerClassName={cn(ROW, "border-invite/50 bg-invite/10 text-invite hover:bg-invite/20")}
    >
      <span className="text-base">{invite.tableEmoji}</span>
      <span className="min-w-0 flex-1 truncate">{invite.tableName}</span>
      <span className="max-w-[45%] shrink-0 truncate text-[11px] text-muted-foreground">
        {from?.name ?? "…"}
      </span>
    </InviteDialog>
  );
}

import { useEffect, useRef } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { PageEdge } from "@/components/client-pages";
import { LoadingList } from "@/components/loading";
import { ScrollContainer } from "@/components/scroll-container";
import { backendErrorMessage } from "@/lib/api-client";
import { userKey } from "@/lib/query-keys";
import type { User } from "@/features/people";
import { UserAuditView } from "./audit";
import { NameTag } from "./avatar";
import { listAuditLog } from "./dashboard-api";
import { LockoutBanner } from "./lockout";
import { ago, stamp } from "./format";
import { PlayerList, SECTION_HEADING } from "./players";

/**
 * Users: two sideways rows, the players (search, create, sign-in codes) and below them the admin audit log; picking a
 * player opens their audit underneath.
 */
export function UsersTab({
  user,
  playerId,
  openPlayer,
  openReports,
}: {
  user: User;
  playerId: string | null;
  openPlayer: (id: string | null) => void;
  openReports: () => void;
}) {
  const auditRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (playerId) auditRef.current?.scrollIntoView?.({ behavior: "smooth", block: "start" });
  }, [playerId]);
  return (
    <div className="space-y-8">
      <LockoutBanner user={user} />
      <PlayerList
        user={user}
        selected={playerId}
        onOpen={(id) => openPlayer(id)}
        onClose={() => openPlayer(null)}
      />
      <AuditLog user={user} onOpenPlayer={(id) => openPlayer(id)} />
      {playerId && (
        <div ref={auditRef} className="scroll-mt-4 border-t pt-6">
          <UserAuditView
            key={playerId}
            user={user}
            playerId={playerId}
            onBack={() => openPlayer(null)}
            onOpenReports={openReports}
            onOpenPlayer={(id) => openPlayer(id)}
          />
        </div>
      )}
    </div>
  );
}

/** Every audit an admin opened and every search they ran, newest first. */
function AuditLog({ user, onOpenPlayer }: { user: User; onOpenPlayer: (id: string) => void }) {
  const log = useInfiniteQuery({
    queryKey: userKey(user.id, "admin-audit-log"),
    queryFn: ({ pageParam, signal }) => listAuditLog(pageParam, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
    retry: false,
  });
  const entries = log.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <section className="space-y-3" aria-labelledby="audit-log-heading">
      <h2 id="audit-log-heading" className={SECTION_HEADING}>
        Audit log
      </h2>
      {log.isPending ? (
        <LoadingList label="Loading the audit log" rows={1} rowClassName="h-28" />
      ) : log.isError ? (
        <p role="alert">{backendErrorMessage(log.error)}</p>
      ) : entries.length === 0 ? (
        <p className="rounded-lg border bg-surface p-4 text-sm text-muted-foreground">
          No audits yet.
        </p>
      ) : (
        <ScrollContainer
          isHorizontal
          isVisible={false}
          aria-label="Audit log"
          className="snap-x gap-3 pb-2 md:scrollbar-thin"
        >
          {entries.map((entry) => (
            <article
              key={entry.id}
              className="flex w-64 shrink-0 snap-start flex-col gap-2 rounded-xl border bg-surface p-3 text-sm"
            >
              <NameTag
                name={entry.subjectName}
                hue={entry.subjectHue}
                size="sm"
                onOpen={() => onOpenPlayer(entry.subjectId)}
              />
              <p className="line-clamp-2 text-muted-foreground">
                {entry.action === "view_user" ? (
                  "Audit opened"
                ) : (
                  <>
                    Searched “<span className="text-foreground">{entry.query ?? ""}</span>”
                  </>
                )}
              </p>
              <p
                className="mt-auto flex items-center justify-between gap-2 text-xs text-muted-foreground"
                title={stamp(entry.at)}
              >
                <NameTag name={entry.adminName} hue={entry.adminHue} fallback="A deleted admin" />
                <span className="shrink-0">{ago(entry.at)}</span>
              </p>
            </article>
          ))}
          {log.hasNextPage && !log.isFetchingNextPage && (
            <PageEdge
              onMore={() => void log.fetchNextPage()}
              label="Load more"
              className="w-24 shrink-0 self-stretch"
            />
          )}
        </ScrollContainer>
      )}
    </section>
  );
}

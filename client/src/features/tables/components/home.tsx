import { PageHeader } from "@/components/page-header";
import type { User } from "@/features/people";
import { HomeTickets } from "@/features/bets";
import { HOME_TICKETS, useMediaQuery } from "@/lib/use-media-query";
import { ArchivedTables } from "./archives";
import { TableCards } from "./table-cards";

const weekday = new Intl.DateTimeFormat("en-US", { weekday: "long" });

/**
 * Home: a greeting, your tables and invitations as cards, and deleted tables you can still read. Wide screens add a
 * Tickets column beside the tables, and tall tablet-sized ones a Tickets box below them ({@link HOME_TICKETS});
 * elsewhere it isn't mounted, so phones don't fetch it (they use the Tickets page).
 */
export function HomePage({ user, expire }: { user: User; expire: () => void }) {
  const showTickets = useMediaQuery(HOME_TICKETS);
  return (
    <div className="space-y-10">
      <PageHeader eyebrow={`${weekday.format(new Date())} slate`} title={`Hey, ${user.username}`} />
      <div className="grid grid-cols-1 gap-10 lg:grid-cols-[minmax(0,1fr)_minmax(0,28rem)]">
        <div className="min-w-0 space-y-10">
          <TableCards user={user} expire={expire} />
          <ArchivedTables user={user} />
        </div>
        {showTickets && <HomeTickets user={user} expire={expire} />}
      </div>
    </div>
  );
}

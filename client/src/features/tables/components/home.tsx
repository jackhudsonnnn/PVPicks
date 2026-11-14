import { PageHeader } from "@/components/page-header";
import type { User } from "@/features/people";
import { HomeTickets } from "@/features/bets";
import { DESKTOP, useMediaQuery } from "@/lib/use-media-query";
import { ArchivedTables } from "./archives";
import { TableCards } from "./table-cards";

const weekday = new Intl.DateTimeFormat("en-US", { weekday: "long" });

/**
 * Home: a greeting, your tables and invitations as cards, and deleted tables you can still read. Desktop adds a
 * Tickets column beside the tables (phones use the Tickets page), mounted only there so phones don't fetch it.
 */
export function HomePage({ user, expire }: { user: User; expire: () => void }) {
  const desktop = useMediaQuery(DESKTOP);
  return (
    <div className="space-y-10">
      <PageHeader eyebrow={`${weekday.format(new Date())} slate`} title={`Hey, ${user.username}`} />
      <div className="grid grid-cols-1 gap-10 lg:grid-cols-[minmax(0,1fr)_minmax(0,28rem)]">
        <div className="min-w-0 space-y-10">
          <TableCards user={user} expire={expire} />
          <ArchivedTables user={user} />
        </div>
        {desktop && <HomeTickets user={user} expire={expire} />}
      </div>
    </div>
  );
}

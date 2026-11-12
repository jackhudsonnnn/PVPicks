import { EmptyState } from "@/components/empty-state";
import { Flag } from "lucide-react";
import { ScrollContainer } from "@/components/scroll-container";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { cn } from "@/lib/utils";
import { DEMO_ACCOUNTS } from "@/features/auth/demo-accounts";
import { UserAvatar } from "@/features/social/components/user-avatar";
import { useGetUser } from "@/features/social/people";
import { useReports } from "@/features/social/reports-storage";
import {
  REPORT_REASONS,
  REPORT_STATUS_LABEL,
  queueOrder,
  type Report,
  type ReportStatus,
} from "@/features/social/safety";

function reporterLabel(r: Report, name: string) {
  const acct = DEMO_ACCOUNTS.find((a) => a.id === r.reporterAccount);
  return acct ? `${name} (${acct.label} demo)` : name;
}

function ago(ms: number) {
  const m = Math.round((Date.now() - ms) / 60000);
  if (m < 1) return "just now";
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  return h < 24 ? `${h}h ago` : `${Math.round(h / 24)}d ago`;
}

const STATUSES: ReportStatus[] = ["open", "reviewing", "actioned", "dismissed"];

/** Moderator review queue. Status changes show up in the reporter's Safety tab. */
export function ReportsQueue() {
  const getUser = useGetUser();
  const nameOf = (id: string) => getUser(id).username;
  const { reports, setStatus } = useReports();
  const list = queueOrder(reports);
  return (
    <section className="space-y-3 rounded-2xl border bg-surface p-4 sm:p-5">
      <div>
        <h2 className="font-display text-2xl font-extrabold uppercase leading-none">
          Reports queue
        </h2>
        <p className="mt-1 text-xs text-muted-foreground">
          Unhandled first. Reporters see the status you set; the reported person never sees who
          reported them. Settlement disputes are private between players, so act only on harassment
          or pressure.
        </p>
      </div>
      {list.length === 0 ? (
        <EmptyState className="py-8">Nothing to review.</EmptyState>
      ) : (
        <ScrollContainer isVisible={false} className="max-h-[32rem]">
          <ul className="grid gap-2">
            <AnimatedList>
              {list.map((r) => (
                <AnimatedItem as="li" key={r.id}>
                  <div
                    className={cn(
                      "space-y-2 rounded-xl border bg-sidebar p-3",
                      (r.status === "open" || r.status === "reviewing") && "border-loss/40",
                    )}
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <UserAvatar userId={r.targetId} size="sm" className="ring-0" />
                      <div className="min-w-0 flex-1">
                        <p className="break-words text-sm font-semibold">{nameOf(r.targetId)}</p>
                        <p className="break-words font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
                          <Flag className="mr-1 inline size-3 text-loss" />
                          {REPORT_REASONS.find((x) => x.id === r.reason)?.label} · by{" "}
                          {reporterLabel(r, nameOf(r.reporterId))} · {ago(r.createdAt)}
                        </p>
                      </div>
                    </div>
                    {r.messageText && (
                      <p className="break-words rounded-lg border bg-surface px-3 py-2 text-sm italic text-muted-foreground">
                        “{r.messageText}”
                      </p>
                    )}
                    {r.note && <p className="break-words text-sm">{r.note}</p>}
                    <SegmentedTabs<ReportStatus>
                      size="sm"
                      ariaLabel="Report status"
                      tabs={STATUSES.map((s) => ({ id: s, label: REPORT_STATUS_LABEL[s] }))}
                      value={r.status}
                      onChange={(s) => setStatus(r.id, s)}
                    />
                  </div>
                </AnimatedItem>
              ))}
            </AnimatedList>
          </ul>
        </ScrollContainer>
      )}
    </section>
  );
}

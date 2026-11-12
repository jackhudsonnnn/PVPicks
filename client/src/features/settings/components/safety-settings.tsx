import { useState } from "react";
import { Link } from "@tanstack/react-router";
import { Ban, Flag } from "lucide-react";
import { Button } from "@/components/ui/button";
import { SettingsSection } from "@/components/settings";
import { ScrollContainer } from "@/components/scroll-container";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { cn } from "@/lib/utils";
import { myBlocks, useAppStore, useGetUser } from "@/features/store";
import { useAuth } from "@/features/auth/auth-provider";
import { UserAvatar } from "@/features/social/components/user-avatar";
import { BlockDialog } from "@/features/social/components/safety-dialogs";
import { useReports } from "@/features/social/reports-storage";
import { REPORT_REASONS, REPORT_STATUS_LABEL, type ReportStatus } from "@/features/social/safety";
import { DebtCapSettings } from "./debt-cap-settings";

const STATUS_TONE: Record<ReportStatus, string> = {
  open: "bg-invite/20 text-invite",
  reviewing: "bg-chart-2/20 text-chart-2",
  actioned: "bg-primary/20 text-primary",
  dismissed: "bg-muted text-muted-foreground",
};

export function SafetySettings() {
  const getUser = useGetUser();
  const { state } = useAppStore();
  const { account } = useAuth();
  const { reports } = useReports();
  const [unblock, setUnblock] = useState<string | null>(null);
  const blocked = myBlocks(state);
  const mine = reports.filter((r) => r.reporterAccount === account);

  return (
    <div className="space-y-4">
      <DebtCapSettings />
      <SettingsSection
        title="Blocked people"
        description="They aren't told. Their chats are hidden and neither of you can add the other to a table."
      >
        {blocked.length === 0 ? (
          <p className="px-4 py-5 text-sm text-muted-foreground sm:px-5">
            You haven't blocked anyone. Use the ⋯ on a chat message or someone's profile.
          </p>
        ) : (
          <ScrollContainer isVisible={false} className="max-h-72 px-4 py-3 sm:px-5">
            <ul className="grid gap-1.5">
              <AnimatedList>
                {blocked.map((id) => (
                  <AnimatedItem as="li" key={id}>
                    <div className="flex items-center gap-3 rounded-lg border bg-sidebar px-3 py-2">
                      <UserAvatar userId={id} size="sm" className="ring-0" />
                      <span className="min-w-0 flex-1">
                        <span className="block break-words text-sm font-semibold">
                          {getUser(id).username}
                        </span>
                      </span>
                      <Button size="sm" variant="outline" onClick={() => setUnblock(id)}>
                        Unblock
                      </Button>
                    </div>
                  </AnimatedItem>
                ))}
              </AnimatedList>
            </ul>
          </ScrollContainer>
        )}
      </SettingsSection>

      <SettingsSection
        title="Your reports"
        description="A person on the P2Picks team reviews each one. The person you reported isn't told who sent it."
      >
        {mine.length === 0 ? (
          <p className="px-4 py-5 text-sm text-muted-foreground sm:px-5">No reports sent.</p>
        ) : (
          <ScrollContainer isVisible={false} className="max-h-80 px-4 py-3 sm:px-5">
            <ul className="grid gap-1.5">
              <AnimatedList>
                {mine.map((r) => (
                  <AnimatedItem as="li" key={r.id}>
                    <div className="space-y-1 rounded-lg border bg-sidebar px-3 py-2.5">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <span className="flex items-center gap-2 text-sm font-semibold">
                          <Flag className="size-3.5 text-loss" />
                          {getUser(r.targetId).username}
                        </span>
                        <span
                          className={cn(
                            "rounded-full px-2 py-0.5 font-mono text-[10px] font-bold uppercase tracking-wide",
                            STATUS_TONE[r.status],
                          )}
                        >
                          {REPORT_STATUS_LABEL[r.status]}
                        </span>
                      </div>
                      <p className="text-xs text-muted-foreground">
                        {REPORT_REASONS.find((x) => x.id === r.reason)?.label} ·{" "}
                        {new Date(r.createdAt).toLocaleDateString()}
                      </p>
                      {r.note && <p className="break-words text-sm">{r.note}</p>}
                    </div>
                  </AnimatedItem>
                ))}
              </AnimatedList>
            </ul>
          </ScrollContainer>
        )}
      </SettingsSection>

      <p className="flex items-start gap-2 px-1 text-xs text-muted-foreground">
        <Ban className="mt-0.5 size-3.5 shrink-0" />
        <span>
          How blocking, reports and account deletion work is in the{" "}
          <Link to="/how-to-play" hash="safety" className="font-semibold text-foreground underline">
            Guide
          </Link>
          .
        </span>
      </p>

      {unblock && (
        <BlockDialog userId={unblock} open onOpenChange={(o) => !o && setUnblock(null)} />
      )}
    </div>
  );
}

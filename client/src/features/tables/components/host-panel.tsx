import { useState } from "react";
import { Crown } from "lucide-react";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { ScrollContainer } from "@/components/scroll-container";
import { PageEdge } from "@/components/client-pages";
import { useClientPages } from "@/components/use-client-pages";
import { SettingRow, SettingsSection } from "@/components/settings";
import { cn } from "@/lib/utils";
import { PersonAvatar, usePerson } from "@/features/people";
import { ActionStatus } from "@/components/action-status";
import { PERMISSIONS, PanelProps, People, REMAINDER, SWITCHES } from "./host-shared";
import { SettleSection, SettlementPreview } from "./host-settle";
import { DeleteTableSection, QuickPicksSection, TableDetailsSection } from "./host-table-settings";
import { AddPlayersSection, MemberButton, MemberSettingsDialog } from "./host-members";

/** Host-only tools: settle points, table details, members and hosts, adding players, Quick Picks, delete. */
export function HostPanel(props: PanelProps) {
  const { table, tableActions, className } = props;
  const [openId, setOpenId] = useState<string | null>(null);
  const members = [...table.memberIds].sort(
    (a, b) => Number(table.hostIds.includes(b)) - Number(table.hostIds.includes(a)),
  );
  // Up to 500 members: mount them 20 at a time as you scroll.
  const memberPages = useClientPages(members);
  return (
    <ScrollContainer isVisible={false} className={cn("space-y-4 p-4", className)}>
      <ActionStatus
        actions={tableActions}
        pendingText="A table change may not have been saved."
        checkText="Check the table's members and settings first."
      />
      <SettleSection {...props} />
      <TableDetailsSection {...props} />
      <SettingsSection
        title="Members & hosts"
        description={`${table.hostIds.length} host${table.hostIds.length === 1 ? "" : "s"} · ${table.memberIds.length} members. Tap someone to change their role or permissions.`}
      >
        <ScrollContainer isVisible={false} className="max-h-80">
          <ul className="divide-y">
            <AnimatedList>
              {memberPages.shown.map((id) => (
                <AnimatedItem as="li" key={id}>
                  <MemberButton id={id} onOpen={() => setOpenId(id)} {...props} />
                </AnimatedItem>
              ))}
            </AnimatedList>
          </ul>
          {memberPages.hasMore && (
            <PageEdge onMore={memberPages.more} label="More members" className="my-2 w-full" />
          )}
        </ScrollContainer>
      </SettingsSection>
      <MemberSettingsDialog
        {...props}
        userId={openId}
        onOpenChange={(o) => !o && setOpenId(null)}
      />
      <AddPlayersSection {...props} />
      <QuickPicksSection {...props} />
      <DeleteTableSection {...props} />
    </ScrollContainer>
  );
}

export function PersonLabel({ id, user, expire, peopleKey }: People & { id: string }) {
  return <>{usePerson(id, user, expire, peopleKey)?.name ?? "…"}</>;
}

function OnOff({ on }: { on: boolean }) {
  return (
    <span className={cn("text-sm font-bold", on ? "text-primary" : "text-muted-foreground")}>
      {on ? "On" : "Off"}
    </span>
  );
}

/** Read-only view of the table's host settings, your permissions and where settlement stands. */
export function AboutPanel(props: PanelProps) {
  const { table, user, expire, peopleKey, className } = props;
  const rule = REMAINDER.find((r) => r.id === table.remainderRule)!;
  const off = table.disabledPermissions[user.id] ?? [];
  return (
    <ScrollContainer isVisible={false} className={cn("space-y-4 p-4", className)}>
      <SettingsSection title="Host settings" description="Only hosts can change these.">
        <div className="space-y-2 px-4 py-3.5 sm:px-5">
          {table.hostIds.map((id) => (
            <div key={id} className="flex items-center gap-2.5 text-sm">
              <PersonAvatar id={id} size="sm" user={user} expire={expire} queryKey={peopleKey} />
              <span className="min-w-0 flex-1 break-words font-semibold">
                <PersonLabel id={id} user={user} expire={expire} peopleKey={peopleKey} />
              </span>
              <Crown className="size-4 text-primary" aria-label="Host" />
            </div>
          ))}
        </div>
        <SettingRow
          label="Leftover cents go to"
          description={rule.description}
          control={<span className="text-sm font-bold">{rule.label}</span>}
        />
        {SWITCHES.map((sw) => (
          <SettingRow
            key={sw.id}
            label={sw.label}
            description={sw.description}
            control={<OnOff on={sw.isOn(table)} />}
          />
        ))}
        <SettingRow
          label="Quick Picks"
          description="Bets shared between tables. Each table only bets among itself."
          control={<OnOff on={table.quickPicksEnabled} />}
        />
      </SettingsSection>
      <SettingsSection
        title="Your permissions"
        description="Hosts can turn these off for you at this table."
      >
        {PERMISSIONS.map((p) => (
          <SettingRow
            key={p.id}
            label={p.label}
            description={p.description}
            control={<OnOff on={!off.includes(p.id)} />}
          />
        ))}
      </SettingsSection>
      <SettingsSection
        title="If settled now"
        description="Everyone pays or gets paid through the host who settles."
      >
        <div className="space-y-3 px-4 py-3.5 sm:px-5">
          <SettlementPreview {...props} />
        </div>
      </SettingsSection>
    </ScrollContainer>
  );
}

import { MembersSummary } from "./members-summary";
import { Crown } from "lucide-react";
import { cn } from "@/lib/utils";
import { REMAINDER_MODES } from "@/features/bets/payout";
import { ScrollContainer } from "@/components/scroll-container";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { SettingRow, SettingsSection } from "@/components/settings";
import { useAppStore, useMe, useGetUser } from "@/features/store";
import type { Table } from "@/features/table/types";
import { UserAvatar } from "@/features/social/components/user-avatar";
import { outstandingFor } from "../settlements";
import {
  MEMBER_PERMISSIONS,
  TABLE_SWITCHES,
  hasPermission,
  isHost,
  permissionNote,
} from "../hosts";
import { PaymentRow } from "./settlement-card";
import { isSharing, subscriptionsOf } from "../quick-picks";
import { SettlementSummary } from "./settlement-summary";

/** Read-only view of the table's host settings and where settlement stands. */
export function AboutPanel({
  table,
  className,
  onOpenPeers,
}: {
  table: Table;
  className?: string;
  onOpenPeers?: () => void;
}) {
  const me = useMe();
  const getUser = useGetUser();
  const { state } = useAppStore();
  const host = table.hostIds[0]!;
  const mode = REMAINDER_MODES.find((m) => m.id === (table.remainderMode ?? "host"))!;
  const mine = outstandingFor(
    state.messages.filter((m) => m.tableId === table.id),
    me,
  );

  return (
    <ScrollContainer isVisible={false} className={cn("space-y-4 p-4", className)}>
      <MembersSummary table={table} onOpen={onOpenPeers} />
      {mine.length > 0 && (
        <SettingsSection title="Your payments" description="Confirm once money changes hands.">
          <ul className="space-y-1 px-4 py-3.5 sm:px-5">
            <AnimatedList>
              {mine.map((p) => (
                <AnimatedItem as="li" key={`${p.messageId}-${p.lineUserId}`}>
                  <PaymentRow p={p} asDiv />
                </AnimatedItem>
              ))}
            </AnimatedList>
          </ul>
        </SettingsSection>
      )}

      <SettingsSection title="Host settings" description="Only hosts can change these.">
        <div className="space-y-2 px-4 py-3.5 sm:px-5">
          {table.hostIds.map((id) => (
            <div key={id} className="flex items-center gap-2.5 text-sm">
              <UserAvatar userId={id} size="sm" />
              <span className="min-w-0 flex-1 break-words font-semibold">
                {id === me ? "You" : getUser(id).username}
              </span>
              <Crown className="size-4 text-primary" />
            </div>
          ))}
        </div>
        <SettingRow
          label="Leftover cents go to"
          description={mode.description}
          control={<span className="text-sm font-bold">{mode.label}</span>}
        />
        {TABLE_SWITCHES.map((sw) => (
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
          control={<OnOff on={!!table.quickPicksOn} />}
        />
        {table.quickPicksOn && (
          <>
            <SettingRow
              label="Sharing this table's bets"
              description="Other hosts with the code get copies of bets started here."
              control={<OnOff on={isSharing(table)} />}
            />
            <SettingRow
              label="Bets posted here from"
              description="Their bets post here automatically. They call the result; a host here can wash it."
              control={
                <span className="text-right text-sm font-bold">
                  {subscriptionsOf(table)
                    .map((id) => state.tables.find((t) => t.id === id)?.name)
                    .filter(Boolean)
                    .join(", ") || "None"}
                </span>
              }
            />
          </>
        )}
      </SettingsSection>

      {!isHost(table, me) && (
        <SettingsSection
          title="Your permissions"
          description="Hosts can turn these off for you at this table."
        >
          {MEMBER_PERMISSIONS.map((p) => (
            <SettingRow
              key={p.id}
              label={p.label}
              description={permissionNote(table, me, p.id)}
              control={<OnOff on={hasPermission(table, me, p.id)} />}
            />
          ))}
        </SettingsSection>
      )}

      <SettingsSection
        title="If settled now"
        description={`Everyone pays or gets paid through ${getUser(host).username.split(" ")[0]} (host).`}
      >
        <div className="space-y-3 px-4 py-3.5 sm:px-5">
          <SettlementSummary table={table} hostId={host} />
        </div>
      </SettingsSection>
    </ScrollContainer>
  );
}

function OnOff({ on }: { on: boolean }) {
  return (
    <span className={cn("text-sm font-bold", on ? "text-primary" : "text-muted-foreground")}>
      {on ? "On" : "Off"}
    </span>
  );
}

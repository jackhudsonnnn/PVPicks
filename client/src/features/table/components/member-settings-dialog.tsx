import { Crown, Images, MessageSquareText, SmilePlus, Ticket, UserMinus } from "lucide-react";
import { useRef, type ComponentType } from "react";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { ScrollContainer } from "@/components/scroll-container";
import { SettingRow, SettingsSection } from "@/components/settings";
import { useAppStore, useMe, useGetUser } from "@/features/store";
import type { Table } from "@/features/table/types";
import { UserAvatar } from "@/features/social/components/user-avatar";
import {
  MEMBER_PERMISSIONS,
  canRemoveMember,
  canSetPermissions,
  canToggleHost,
  hasPermission,
  isHost,
  permissionNote,
  removalBlockedByBets,
  type MemberPermission,
} from "../hosts";

const PERM_ICON: Record<MemberPermission, ComponentType<{ className?: string }>> = {
  bets: Ticket,
  text: MessageSquareText,
  react: SmilePlus,
  gifs: Images,
};

/** Host popup for one member: host role, permissions and kicking them from the table. */
export function MemberSettingsDialog({
  table,
  userId,
  onOpenChange,
}: {
  table: Table;
  userId: string | null;
  onOpenChange: (open: boolean) => void;
}) {
  const me = useMe();
  const getUser = useGetUser();
  const { state, actions } = useAppStore();
  const focusPermissions = useRef(false);
  const open = !!userId && table.memberIds.includes(userId);
  const id = userId ?? me;
  const u = getUser(id);
  const you = id === me;
  const host = isHost(table, id);
  const canHostToggle = canToggleHost(table, me, id);
  const canLimit = canSetPermissions(table, me, id);
  const removalBlocked = removalBlockedByBets(table, state.bets);
  const first = you ? "you" : u.username.split(" ")[0];

  const hostHelp = host
    ? table.hostIds.length === 1
      ? "A table always keeps one host. Make someone else a host first."
      : you
        ? "Step down and go back to being a member."
        : "They'll go back to being a member."
    : "Hosts can change table settings, add or kick players and settle points.";

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex max-h-[90dvh] flex-col gap-0 overflow-hidden p-0 sm:max-w-md">
        <DialogHeader className="flex-row items-center gap-3 space-y-0 border-b px-5 py-4 text-left">
          <UserAvatar userId={id} size="md" />
          <div className="min-w-0">
            <DialogTitle className="flex flex-wrap items-center gap-1.5 break-words">
              {you ? "You" : u.username}
              {host && <Crown className="size-4 shrink-0 text-primary" aria-label="Host" />}
            </DialogTitle>
            <DialogDescription className="font-mono text-[11px] uppercase tracking-wider">
              {host ? "Host" : "Member"}
            </DialogDescription>
          </div>
        </DialogHeader>

        <ScrollContainer isVisible={false} className="min-h-0 flex-1 space-y-4 p-4">
          <SettingsSection title="Role">
            <SettingRow
              icon={Crown}
              htmlFor="member-host"
              label="Host"
              description={hostHelp}
              disabled={!canHostToggle}
              control={
                <Switch
                  id="member-host"
                  checked={host}
                  disabled={!canHostToggle}
                  onCheckedChange={() => actions.toggleHost(table.id, id)}
                />
              }
            />
          </SettingsSection>

          <SettingsSection
            title="Permissions"
            description={
              canLimit
                ? `Turn things off for ${first} at this table. Only hosts see this.`
                : "Hosts can always do everything."
            }
          >
            {MEMBER_PERMISSIONS.map((p) => (
              <SettingRow
                key={p.id}
                icon={PERM_ICON[p.id]}
                htmlFor={`perm-${p.id}`}
                label={p.label}
                description={permissionNote(table, id, p.id)}
                disabled={!canLimit}
                control={
                  <Switch
                    id={`perm-${p.id}`}
                    checked={hasPermission(table, id, p.id)}
                    disabled={!canLimit}
                    onCheckedChange={(on) => actions.setPermission(table.id, id, p.id, on)}
                  />
                }
              />
            ))}
          </SettingsSection>

          {isHost(table, me) && !you && (
            <AlertDialog>
              <AlertDialogTrigger asChild>
                <Button variant="outline" className="w-full text-loss hover:text-loss">
                  <UserMinus /> Kick from table
                </Button>
              </AlertDialogTrigger>
              <AlertDialogContent
                onCloseAutoFocus={(event) => {
                  if (focusPermissions.current) {
                    event.preventDefault();
                    focusPermissions.current = false;
                    const control = document.getElementById("perm-bets");
                    control?.scrollIntoView({ block: "center" });
                    control?.focus();
                  }
                }}
              >
                <AlertDialogHeader>
                  <AlertDialogTitle>Kick {u.username.split(" ")[0]}?</AlertDialogTitle>
                  <AlertDialogDescription>
                    {host
                      ? "Demote this host before removing them."
                      : removalBlocked
                        ? "Members cannot be removed while bets are active, pending, or awaiting points settlement. You can adjust their permissions instead."
                        : `They leave ${table.name} right away. Existing records and settlement receipts remain. You can add them back later.`}
                  </AlertDialogDescription>
                </AlertDialogHeader>
                <AlertDialogFooter>
                  <AlertDialogCancel>Cancel</AlertDialogCancel>
                  {removalBlocked && !host ? (
                    <AlertDialogCancel
                      onClick={() => {
                        focusPermissions.current = true;
                      }}
                    >
                      Adjust permissions
                    </AlertDialogCancel>
                  ) : canRemoveMember(table, me, id) ? (
                    <AlertDialogAction
                      onClick={() => {
                        actions.removeMember(table.id, id);
                        onOpenChange(false);
                      }}
                    >
                      Kick
                    </AlertDialogAction>
                  ) : null}
                </AlertDialogFooter>
              </AlertDialogContent>
            </AlertDialog>
          )}
        </ScrollContainer>
      </DialogContent>
    </Dialog>
  );
}

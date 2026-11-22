/** The Host panel's members: roles and permissions per member, and adding players. */
import { useRef } from "react";
import { ChevronRight, Crown, Heart, Plus, UserMinus } from "lucide-react";
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
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Switch } from "@/components/ui/switch";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { ScrollContainer } from "@/components/scroll-container";
import { PageEdge } from "@/components/client-pages";
import { useClientPages } from "@/components/use-client-pages";
import { SettingRow, SettingsSection } from "@/components/settings";
import { PersonAvatar, usePerson } from "@/features/people";
import { addableFollowers } from "@/features/tables/rules";
import { useRelationships } from "@/features/social";
import { PERMISSIONS, PanelProps, busy, hasOpenBets } from "./host-shared";
import { PersonLabel } from "./host-panel";
import { LoadingList } from "@/components/loading";

export function MemberButton({
  id,
  onOpen,
  table,
  ...people
}: PanelProps & { id: string; onOpen: () => void }) {
  const person = usePerson(id, people.user, people.expire, people.peopleKey);
  const host = table.hostIds.includes(id);
  const limits = table.disabledPermissions[id]?.length ?? 0;
  return (
    <button
      type="button"
      onClick={onOpen}
      className="flex w-full items-center gap-3 px-4 py-2.5 text-left transition-colors hover:bg-surface-raised sm:px-5"
    >
      <PersonAvatar
        id={id}
        size="sm"
        className="ring-surface"
        user={people.user}
        expire={people.expire}
        queryKey={people.peopleKey}
      />
      <span className="min-w-0 flex-1">
        <span className="flex flex-wrap items-center gap-1.5 break-words text-sm font-semibold">
          {person?.name ?? "…"}
          {host && <Crown className="size-3.5 shrink-0 text-primary" aria-label="Host" />}
        </span>
        <span className="block font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
          {host ? "Host" : "Member"}
          {limits > 0 && (
            <span className="text-status-pending">
              {" "}
              · {limits} limit{limits === 1 ? "" : "s"}
            </span>
          )}
        </span>
      </span>
      <ChevronRight className="size-4 shrink-0 text-muted-foreground" />
    </button>
  );
}

/** Host popup for one member: host role, permissions and removing them from the table. */
export function MemberSettingsDialog({
  userId,
  onOpenChange,
  ...props
}: PanelProps & { userId: string | null; onOpenChange: (open: boolean) => void }) {
  const { table, bets, tableActions, user, expire, peopleKey } = props;
  const focusPermissions = useRef(false);
  const open = !!userId && table.memberIds.includes(userId);
  const id = userId ?? user.id;
  const person = usePerson(id, user, expire, peopleKey);
  const name = person?.name ?? "…";
  const you = id === user.id;
  const host = table.hostIds.includes(id);
  const disabled = busy(tableActions);
  const canHostToggle = !host || table.hostIds.length > 1;
  const canLimit = !host;
  const off = table.disabledPermissions[id] ?? [];
  const removalBlocked = hasOpenBets(bets);
  const hostHelp = host
    ? table.hostIds.length === 1
      ? "A table always keeps one host. Make someone else a host first."
      : you
        ? "Step down and go back to being a member."
        : "They'll go back to being a member."
    : "Hosts can change table settings, add or remove players and settle points.";

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex max-h-[90dvh] flex-col gap-0 overflow-hidden p-0 sm:max-w-md">
        <DialogHeader className="flex-row items-center gap-3 space-y-0 border-b px-5 py-4 text-left">
          <PersonAvatar id={id} size="md" user={user} expire={expire} queryKey={peopleKey} />
          <div className="min-w-0">
            <DialogTitle className="flex flex-wrap items-center gap-1.5 break-words">
              {name}
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
                  disabled={!canHostToggle || disabled}
                  onCheckedChange={(on) =>
                    tableActions.start({ kind: "host", tableId: table.id, userId: id, host: on })
                  }
                />
              }
            />
          </SettingsSection>
          <SettingsSection
            title="Permissions"
            description={
              canLimit
                ? `Turn things off for ${you ? "you" : name} at this table. Only hosts and they see this.`
                : "Hosts can always do everything."
            }
          >
            {PERMISSIONS.map((p) => (
              <SettingRow
                key={p.id}
                icon={p.icon}
                htmlFor={`perm-${p.id}`}
                label={p.label}
                description={p.description}
                disabled={!canLimit}
                control={
                  <Switch
                    id={`perm-${p.id}`}
                    checked={!off.includes(p.id)}
                    disabled={!canLimit || disabled}
                    onCheckedChange={(enabled) =>
                      tableActions.start({
                        kind: "permission",
                        tableId: table.id,
                        userId: id,
                        permission: p.id,
                        enabled,
                      })
                    }
                  />
                }
              />
            ))}
          </SettingsSection>
          {!you && (
            <AlertDialog>
              <AlertDialogTrigger asChild>
                <Button
                  variant="outline"
                  className="w-full text-loss hover:text-loss"
                  disabled={disabled}
                >
                  <UserMinus /> Remove from table
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
                  <AlertDialogTitle>Remove {name}?</AlertDialogTitle>
                  <AlertDialogDescription>
                    {host
                      ? "Remove their host role before removing them."
                      : removalBlocked
                        ? "Members can't be removed while bets are active, pending or awaiting points settlement. You can turn off their permissions instead."
                        : `They leave ${table.name} right away. Their bets, ledger and receipts stay. You can add them back later.`}
                  </AlertDialogDescription>
                </AlertDialogHeader>
                <AlertDialogFooter>
                  <AlertDialogCancel>Cancel</AlertDialogCancel>
                  {host ? null : removalBlocked ? (
                    <AlertDialogCancel onClick={() => (focusPermissions.current = true)}>
                      Adjust permissions
                    </AlertDialogCancel>
                  ) : (
                    <AlertDialogAction
                      onClick={() => {
                        tableActions.start({ kind: "remove", tableId: table.id, userId: id });
                        onOpenChange(false);
                      }}
                    >
                      Remove
                    </AlertDialogAction>
                  )}
                </AlertDialogFooter>
              </AlertDialogContent>
            </AlertDialog>
          )}
        </ScrollContainer>
      </DialogContent>
    </Dialog>
  );
}

/** Friends and followers who aren't here yet; their settings decide whether they join or get a request. */
export function AddPlayersSection({ table, tableActions, ...people }: PanelProps) {
  const relationships = useRelationships(people.user.id);
  const data = relationships.data;
  const candidates = data ? addableFollowers(table, data.followerIds, data.blockedIds) : [];
  const friends = candidates.filter((id) => data?.followingIds.includes(id));
  const candidatePages = useClientPages(candidates);
  const disabled = busy(tableActions);
  const add = (userIds: string[]) =>
    tableActions.start({ kind: "add", tableId: table.id, userIds });
  return (
    <SettingsSection
      title="Add players"
      description="People who follow you. Their settings decide if they join right away or get a request."
    >
      <div className="grid grid-cols-2 gap-2 px-4 py-3 sm:px-5">
        <Button
          size="sm"
          variant="outline"
          disabled={friends.length === 0 || disabled}
          onClick={() => add(friends)}
        >
          <Heart /> All friends · {friends.length}
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={candidates.length === 0 || disabled}
          onClick={() => add(candidates)}
        >
          <Plus /> All followers · {candidates.length}
        </Button>
      </div>
      {relationships.isPending ? (
        <LoadingList
          label="Loading people"
          rows={3}
          rowClassName="h-10"
          className="px-4 py-4 sm:px-5"
        />
      ) : candidates.length === 0 ? (
        <p className="px-4 py-4 text-sm text-muted-foreground sm:px-5">
          Everyone who follows you is already here.
        </p>
      ) : (
        <ScrollContainer isVisible={false} className="max-h-72">
          <ul className="divide-y">
            <AnimatedList>
              {candidatePages.shown.map((id) => (
                <AnimatedItem
                  as="li"
                  key={id}
                  className="flex items-center gap-3 px-4 py-2.5 sm:px-5"
                >
                  <PersonAvatar
                    id={id}
                    size="sm"
                    className="ring-surface"
                    user={people.user}
                    expire={people.expire}
                    queryKey={people.peopleKey}
                  />
                  <span className="min-w-0 flex-1">
                    <span className="block break-words text-sm font-semibold">
                      <PersonLabel id={id} {...people} />
                    </span>
                    <span className="flex items-center gap-1 font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
                      {friends.includes(id) ? (
                        <>
                          <Heart className="size-3 text-primary" /> Friend
                        </>
                      ) : (
                        "Follows you"
                      )}
                    </span>
                  </span>
                  <Button
                    size="sm"
                    variant="outline"
                    className="h-8 px-2.5 text-xs"
                    disabled={disabled}
                    onClick={() => add([id])}
                  >
                    <Plus /> Add
                  </Button>
                </AnimatedItem>
              ))}
            </AnimatedList>
          </ul>
          {candidatePages.hasMore && (
            <PageEdge
              onMore={candidatePages.more}
              label="More followers"
              className="mx-4 my-2 w-[calc(100%-2rem)]"
            />
          )}
        </ScrollContainer>
      )}
    </SettingsSection>
  );
}

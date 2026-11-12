import type { Bet } from "@/features/bets/types";
import type { Table } from "@/features/table/types";

/** Host permission rules. Hosts run the table: details, members and who else hosts. */
export const isHost = (table: Table, userId: string) => table.hostIds.includes(userId);

/** Hosts must demote another host before removing them; leaving is separate. */
export function canRemoveMember(table: Table, actor: string, target: string) {
  return (
    isHost(table, actor) &&
    target !== actor &&
    table.memberIds.includes(target) &&
    !isHost(table, target)
  );
}

export function removalBlockedByBets(table: Table, bets: Bet[]) {
  return bets.some(
    (bet) =>
      bet.tableId === table.id &&
      (bet.status === "active" ||
        bet.status === "pending" ||
        (bet.status === "resolved" && !bet.forFun && !bet.settled)),
  );
}

/** Hosts can delete a table only when none of its bets are still Active or Pending. */
export function canDeleteTable(table: Table, actor: string, bets: Bet[]) {
  return (
    isHost(table, actor) &&
    !bets.some((b) => b.tableId === table.id && (b.status === "active" || b.status === "pending"))
  );
}

/** Hosts can promote/demote members, but a table always keeps at least one host. */
export function canToggleHost(table: Table, actor: string, target: string) {
  if (!isHost(table, actor) || !table.memberIds.includes(target)) return false;
  if (isHost(table, target) && table.hostIds.length === 1) return false;
  return true;
}

/** Things a host can turn off for one member. */
export type MemberPermission = "bets" | "text" | "react" | "gifs";

export const MEMBER_PERMISSIONS: { id: MemberPermission; label: string; description: string }[] = [
  { id: "bets", label: "Can place bets", description: "Pick on tickets and start new bets." },
  { id: "text", label: "Can send texts", description: "Write messages in the table chat." },
  { id: "react", label: "Can react", description: "Add emoji reactions to messages." },
  { id: "gifs", label: "Can send GIFs", description: "Post GIFs in the table chat." },
];

/** Members have every permission unless a host turned it off. Hosts always have all of them. */
export function hasPermission(table: Table, userId: string, perm: MemberPermission) {
  if (!table.memberIds.includes(userId)) return false;
  if (isHost(table, userId)) return true;
  return !(table.limits?.[userId] ?? []).includes(perm);
}

/** Hosts can limit other members, but not themselves or another host. */
export function canSetPermissions(table: Table, actor: string, target: string) {
  return (
    isHost(table, actor) &&
    target !== actor &&
    table.memberIds.includes(target) &&
    !isHost(table, target)
  );
}

/** Returns the table with one member's permission turned on or off. */
export function setPermission(
  table: Table,
  userId: string,
  perm: MemberPermission,
  on: boolean,
): Table {
  const current = table.limits?.[userId] ?? [];
  const nextList = on ? current.filter((p) => p !== perm) : [...new Set([...current, perm])];
  const { [userId]: _drop, ...rest } = table.limits ?? {};
  return { ...table, limits: nextList.length ? { ...rest, [userId]: nextList } : rest };
}

/** Drops any limits on a member (used when they become a host or leave). */
export function clearLimits(table: Table, userId: string): Table {
  if (!table.limits?.[userId]) return table;
  const { [userId]: _drop, ...rest } = table.limits;
  return { ...table, limits: rest };
}

/** Anyone at the table can start a bet unless a host limited bets to hosts or turned off theirs. */
export function canProposeBet(table: Table, userId: string) {
  if (!hasPermission(table, userId, "bets")) return false;
  return !table.hostsOnlyBets || isHost(table, userId);
}

/** Hosts can turn off U2Pick (custom) bets for everyone, hosts included. */
const isU2PickOn = (table: Table) => !table.u2pickOff;

export function canProposeMode(table: Table, userId: string, modeId: string) {
  if (!canProposeBet(table, userId)) return false;
  return modeId !== "u2pick" || isU2PickOn(table);
}

export function canAddMember(table: Table, actor: string, target: string) {
  return isHost(table, actor) && !table.memberIds.includes(target);
}

/** Table-wide host switches. Host tab (editable) and About tab (read-only) both render from this. */
export const TABLE_SWITCHES = [
  {
    id: "hostsOnlyBets",
    label: "Only hosts can start bets",
    description: "Members can still join and pick on bets hosts start.",
    isOn: (t: Table) => !!t.hostsOnlyBets,
  },
  {
    id: "allowU2Pick",
    label: "Allow U2Pick bets",
    description: "Custom bets where the proposer writes the question and options.",
    isOn: (t: Table) => !t.u2pickOff,
  },
] as const;

export type TableSwitchId = (typeof TABLE_SWITCHES)[number]["id"];

/** What a member's permission actually allows, accounting for table-wide switches. */
export function permissionNote(table: Table, userId: string, perm: MemberPermission) {
  const base = MEMBER_PERMISSIONS.find((p) => p.id === perm)!.description;
  if (perm !== "bets" || !hasPermission(table, userId, "bets")) return base;
  if (table.hostsOnlyBets && !isHost(table, userId))
    return "Can pick on tickets. Starting bets is hosts only right now.";
  return base;
}

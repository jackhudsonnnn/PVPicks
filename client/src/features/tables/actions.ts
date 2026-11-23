import { useRef } from "react";
import { z } from "zod";
import { BackendError, backendErrorMessage, idSchema } from "@/lib/api-client";
import { useRetryableMutation } from "@/lib/mutation";
import {
  addMembers,
  createTable,
  deleteTable,
  removeMember,
  rotateQuickPickCode,
  setHost,
  setQuickPickSharing,
  setQuickPicksEnabled,
  setPermission,
  updateTableProfile,
  updateTableSettings,
  type MemberOutcome,
} from "./api";

const uuid = idSchema;
const name = z.string().trim().min(1).max(160);
const emoji = z.string().min(1).max(32);
const intentSchema = z.discriminatedUnion("kind", [
  z.object({
    kind: z.literal("create"),
    name,
    emoji,
    memberIds: z.array(uuid).max(100),
  }),
  z.object({
    kind: z.literal("profile"),
    tableId: uuid,
    name: name.optional(),
    emoji: emoji.optional(),
  }),
  z.object({
    kind: z.literal("settings"),
    tableId: uuid,
    hostsOnlyBets: z.boolean().optional(),
    u2pickEnabled: z.boolean().optional(),
    pollsEnabled: z.boolean().optional(),
    jackblackAccess: z.enum(["hosts", "everyone", "off"]).optional(),
    holdemAccess: z.enum(["hosts", "everyone", "off"]).optional(),
  }),
  z.object({ kind: z.literal("delete"), tableId: uuid }),
  z.object({ kind: z.literal("add"), tableId: uuid, userIds: z.array(uuid).min(1).max(100) }),
  z.object({ kind: z.literal("remove"), tableId: uuid, userId: uuid }),
  z.object({ kind: z.literal("host"), tableId: uuid, userId: uuid, host: z.boolean() }),
  z.object({
    kind: z.literal("permission"),
    tableId: uuid,
    userId: uuid,
    permission: z.enum(["bets", "text", "react", "gifs", "polls"]),
    enabled: z.boolean(),
  }),
  z.object({ kind: z.literal("quickpicks"), tableId: uuid, enabled: z.boolean() }),
  z.object({ kind: z.literal("sharing"), tableId: uuid, sharing: z.boolean() }),
  z.object({ kind: z.literal("rotate"), tableId: uuid }),
]);

type TableIntent = z.infer<typeof intentSchema>;

/** "2 added, 1 invited, 1 couldn't be added" for the people a host asked to add. */
export function describeOutcomes(outcomes: MemberOutcome[]): string {
  const count = (kind: MemberOutcome["outcome"]) =>
    outcomes.filter((outcome) => outcome.outcome === kind).length;
  const parts = [
    count("added") && `${count("added")} added`,
    count("invited") && `${count("invited")} invited`,
    count("not_added") && `${count("not_added")} couldn't be added`,
  ].filter(Boolean);
  return parts.join(", ");
}

const tableErrors: Record<string, string> = {
  member_removal_self: "A host can't remove themselves.",
  member_removal_host: "Remove their host role before removing them.",
  member_removal_unsettled:
    "Members can't be removed while bets are active, pending or awaiting points settlement.",
  table_archive_unsettled:
    "The table can't be deleted while bets are active, pending or awaiting points settlement.",
  table_archive_membership_changed: "Membership changed while deleting. Try again.",
};

function tableErrorMessage(error: BackendError) {
  return (error.code && tableErrors[error.code]) ?? backendErrorMessage(error);
}

/** Table creation and management, with the same retry-key safety as bets and receipts. */
export function useTableActions(
  userId: string,
  refresh: (intent: TableIntent | null) => Promise<void>,
  expire: () => void,
) {
  // The intent being executed, so the refresh after it knows what changed.
  const last = useRef<TableIntent | null>(null);
  return useRetryableMutation({
    storageKey: `p2picks:table-action:${userId}`,
    intentSchema,
    refresh: () => refresh(last.current),
    expire,
    checkDescription: "your tables and their members",
    errorMessage: tableErrorMessage,
    execute: async (intent, key) => {
      last.current = intent;
      switch (intent.kind) {
        case "create": {
          const result = await createTable(
            { name: intent.name, emoji: intent.emoji, memberIds: intent.memberIds },
            key,
          );
          const people = describeOutcomes(result.outcomes);
          return `Table created${people ? `: ${people}` : ""}.`;
        }
        case "profile":
          await updateTableProfile(
            intent.tableId,
            {
              ...(intent.name === undefined ? {} : { name: intent.name }),
              ...(intent.emoji === undefined ? {} : { emoji: intent.emoji }),
            },
            key,
          );
          return "Table updated.";
        case "settings":
          await updateTableSettings(
            intent.tableId,
            {
              ...(intent.hostsOnlyBets === undefined
                ? {}
                : { hostsOnlyBets: intent.hostsOnlyBets }),
              ...(intent.u2pickEnabled === undefined
                ? {}
                : { u2pickEnabled: intent.u2pickEnabled }),
              ...(intent.pollsEnabled === undefined ? {} : { pollsEnabled: intent.pollsEnabled }),
              ...(intent.jackblackAccess === undefined
                ? {}
                : { jackblackAccess: intent.jackblackAccess }),
              ...(intent.holdemAccess === undefined ? {} : { holdemAccess: intent.holdemAccess }),
            },
            key,
          );
          return "Settings saved.";
        case "delete":
          await deleteTable(intent.tableId, key);
          return "Table deleted. Its chat stays readable for 90 days.";
        case "add": {
          const outcomes = await addMembers(intent.tableId, intent.userIds, key);
          return `${describeOutcomes(outcomes) || "No change"}.`;
        }
        case "remove":
          await removeMember(intent.tableId, intent.userId, key);
          return "Member removed.";
        case "host":
          await setHost(intent.tableId, intent.userId, intent.host, key);
          return intent.host ? "They are now a host." : "They are no longer a host.";
        case "permission":
          await setPermission(
            intent.tableId,
            intent.userId,
            intent.permission,
            intent.enabled,
            key,
          );
          return "Permission saved.";
        case "quickpicks":
          await setQuickPicksEnabled(intent.tableId, intent.enabled, key);
          return intent.enabled ? "Quick Picks on." : "Quick Picks off.";
        case "sharing":
          await setQuickPickSharing(intent.tableId, intent.sharing, key);
          return intent.sharing ? "Sharing your code." : "Sharing stopped.";
        case "rotate": {
          const code = await rotateQuickPickCode(intent.tableId, key);
          return `New code: ${code}. Old subscribers were disconnected.`;
        }
      }
    },
  });
}

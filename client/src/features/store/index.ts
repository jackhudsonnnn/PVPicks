/**
 * The player's app data. Screens read it only through `useAppStore` (state + commands),
 * `useMe` and `useGetUser`, so a real backend can replace the sample-data provider.
 */
export { AppStoreProvider, useAppStore, useGames, useMe, useNames } from "./provider";
export type { AppState } from "./state";
export type { AppCommands, ProposeInput } from "./commands";
export {
  gameById,
  invitePrefsOf,
  myBlocks,
  myFriendIds,
  myInvites,
  myTables,
  visiblePeople,
} from "./selectors";
export { useGetUser } from "@/features/social/people";

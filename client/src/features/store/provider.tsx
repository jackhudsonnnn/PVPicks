// TODO(backend-middleware): replace seedFor/sample activity with an initial fetch + WebSocket/STOMP
// subscription that dispatches server events into the same reducer. Ticks (lock timing) must come from the server.
import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useReducer,
  useRef,
  type ReactNode,
} from "react";
import type { PlayerAccountId } from "@/features/auth/demo-accounts";
import { accountStorageKeys } from "@/features/auth/storage-keys";
import { seedFor } from "@/features/mock/profiles";
import { useSampleActivity } from "@/features/mock/sample-activity";
import { GAMES, ME } from "@/features/mock/seed";
import type { Profile } from "@/features/settings/profile";
import { PeopleProvider, useGetUser } from "@/features/social/people";
import { isArray, readJson, writeJson } from "@/lib/storage";
import { nowLabel } from "@/lib/time";
import { createCommands, type AppCommands } from "./commands";
import { reducer } from "./reducer";
import type { AppState } from "./state";
import type { Game } from "@/features/bets/types";
import { DEFAULT_DEBT_CAP, isDebtCap } from "@/features/settings/debt-cap";

interface Store {
  state: AppState;
  actions: AppCommands;
  /** The signed-in player's id (same as `state.meId`). */
  me: string;
}

const StoreContext = createContext<Store | null>(null);
const GamesContext = createContext<Game[] | null>(null);

/** Sample world for `account`, with `me` as the person you are in it. */
function initialState({ account, me }: { account: PlayerAccountId; me: Profile }): AppState {
  return {
    ...seedFor(account, me),
    meId: ME,
    games: GAMES,
    idBase: Date.now().toString(36),
    seq: 0,
  };
}

/**
 * The player's data, backed by in-memory sample data (no server yet).
 * Remount (key by account) to load a different account. `account` picks the sample world,
 * `me` is who you are in it, `storageKey` scopes what's saved in this browser.
 * TODO(backend): an HTTP/WebSocket version provides the same `Store` value.
 */
export function AppStoreProvider({
  account,
  me,
  storageKey,
  children,
}: {
  account: PlayerAccountId;
  me: Profile;
  storageKey: string;
  children: ReactNode;
}) {
  const [state, dispatch] = useReducer(reducer, { account, me }, initialState);
  const stateRef = useRef(state);
  stateRef.current = state;

  // Your blocks are saved in this browser per account so they survive a reload.
  // TODO(backend): store blocks on the account server-side.
  const blocksKey = accountStorageKeys(storageKey).blocks;
  const blocksLoaded = useRef(false);
  const debtCapKey = accountStorageKeys(storageKey).debtCap;
  const debtCapLoaded = useRef(false);
  useEffect(() => {
    dispatch({ type: "loadDebtCap", cap: readJson(debtCapKey, DEFAULT_DEBT_CAP, isDebtCap) });
    debtCapLoaded.current = true;
  }, [debtCapKey]);
  const myDebtCap = state.debtCaps?.[state.meId];
  useEffect(() => {
    if (debtCapLoaded.current && myDebtCap !== undefined) writeJson(debtCapKey, myDebtCap);
  }, [debtCapKey, myDebtCap]);
  useEffect(() => {
    const blocked = readJson<string[] | null>(blocksKey, null, isArray);
    if (blocked) dispatch({ type: "loadBlocks", userId: stateRef.current.meId, blocked });
    blocksLoaded.current = true;
  }, [blocksKey]);
  const myBlockList = state.blocks[state.meId];
  useEffect(() => {
    if (blocksLoaded.current && myBlockList) writeJson(blocksKey, myBlockList);
  }, [blocksKey, myBlockList]);

  // Live clock: counts down active tickets and locks them into Pending at zero.
  useEffect(() => {
    const t = setInterval(() => dispatch({ type: "tick", at: nowLabel() }), 1000);
    return () => clearInterval(t);
  }, []);

  const replies = useSampleActivity(account, state, stateRef, dispatch);
  const actions = useMemo(
    () => createCommands(() => stateRef.current, dispatch, replies),
    [replies],
  );

  const value = useMemo(() => ({ state, actions, me: state.meId }), [state, actions]);
  return (
    <StoreContext.Provider value={value}>
      <GamesContext.Provider value={state.games}>
        <PeopleProvider users={state.users}>{children}</PeopleProvider>
      </GamesContext.Provider>
    </StoreContext.Provider>
  );
}

export function useAppStore() {
  const ctx = useContext(StoreContext);
  if (!ctx) throw new Error("useAppStore must be used inside AppStoreProvider");
  return ctx;
}

/** Score consumers don't subscribe to ticket clocks, chats, or account changes. */
export function useGames() {
  const games = useContext(GamesContext);
  if (!games) throw new Error("useGames must be used inside AppStoreProvider");
  return games;
}

/** The signed-in player's id. */
export const useMe = () => useAppStore().me;

/** Names as the UI shows them: "You" for the signed-in player, otherwise their username. */
export function useNames() {
  const me = useMe();
  const getUser = useGetUser();
  return useMemo(
    () => ({
      name: (id: string) => (id === me ? "You" : getUser(id).username),
    }),
    [me, getUser],
  );
}

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { useRouterState } from "@tanstack/react-router";
import { useAppStore, useMe, useNames } from "@/features/store";
import { formatPts } from "@/features/bets/ledger";
import { useAuth } from "@/features/auth/auth-provider";
import { accountStorageKeys } from "@/features/auth/storage-keys";
import { newId } from "@/lib/ids";
import { isArray, isRecord, readJson, writeJson } from "@/lib/storage";
import {
  DEFAULT_PREFS,
  diffEvents,
  shouldNotify,
  type NotificationEvent,
  type NotificationPrefs,
} from "./events";
import {
  HISTORY_CAP,
  describeEvent,
  markAllRead,
  pushItem,
  type NotificationItem,
} from "./history";
import { emitError, onError } from "./error-bus";

/**
 * The bell inbox (no pop-up toasts). Prefs and history are cached per account in this browser.
 * TODO(backend): replace with a server-backed, paginated notifications feed
 * (e.g. GET /notifications?cursor=…) and keep this cache as the first page only.
 */

interface NotificationsCtx {
  prefs: NotificationPrefs;
  update: (patch: Partial<NotificationPrefs>) => void;
  toggleTableMute: (tableId: string) => void;
  /** Newest first, capped at HISTORY_CAP. Paginate with `pageAfter` from history.ts. */
  history: NotificationItem[];
  markAllRead: () => void;
  clearHistory: () => void;
  /** Record an event in the inbox (respects preferences). */
  notify: (e: NotificationEvent) => void;
}
const Ctx = createContext<NotificationsCtx | null>(null);

type Record = (e: NotificationEvent, read: boolean) => NotificationItem | null;

/** Needs to sit inside a provider keyed by account, so a new account starts fresh. */
export function NotificationsProvider({ children }: { children: ReactNode }) {
  const me = useMe();
  const { name } = useNames();
  const { account } = useAuth();
  const keys = accountStorageKeys(account ?? "none");
  const prefsKey = keys.notificationPrefs;
  const historyKey = keys.notificationHistory;
  const [prefs, setPrefs] = useState<NotificationPrefs>(DEFAULT_PREFS);
  const [history, setHistory] = useState<NotificationItem[]>([]);
  const loaded = useRef(false);

  // Read caches after hydration so server and client render the same first frame.
  useEffect(() => {
    setPrefs({ ...DEFAULT_PREFS, ...readJson(prefsKey, {}, isRecord) });
    const cached = readJson<NotificationItem[]>(historyKey, [], isArray).slice(0, HISTORY_CAP);
    // Merge anything recorded before the cache finished loading.
    if (cached.length) setHistory((now) => [...now, ...cached].slice(0, HISTORY_CAP));
    loaded.current = true;
  }, [prefsKey, historyKey]);

  useEffect(() => {
    if (loaded.current) writeJson(prefsKey, prefs);
  }, [prefsKey, prefs]);

  // Debounced write: chat bursts shouldn't serialize the whole inbox on every message.
  // Unmounting (sign out) writes right away so the last changes aren't lost.
  const latest = useRef(history);
  latest.current = history;
  useEffect(() => {
    if (!loaded.current) return;
    const t = setTimeout(() => writeJson(historyKey, history), 500);
    return () => clearTimeout(t);
  }, [historyKey, history]);
  useEffect(
    () => () => {
      if (loaded.current) writeJson(historyKey, latest.current);
    },
    [historyKey],
  );

  const record = useCallback<Record>(
    (e, read) => {
      const content = describeEvent(e, me, name, (n) => formatPts(n));
      if (!content) return null;
      const item: NotificationItem = { ...content, id: newId("n"), at: Date.now(), read };
      setHistory((h) => pushItem(h, item));
      return item;
    },
    [me, name],
  );

  const value: NotificationsCtx = {
    prefs,
    update: (patch) => setPrefs((p) => ({ ...p, ...patch })),
    toggleTableMute: (id) =>
      setPrefs((p) => ({
        ...p,
        mutedTableIds: p.mutedTableIds.includes(id)
          ? p.mutedTableIds.filter((x) => x !== id)
          : [...p.mutedTableIds, id],
      })),
    history,
    markAllRead: () => setHistory((h) => markAllRead(h)),
    clearHistory: () => setHistory([]),
    notify: (e) => {
      if (shouldNotify(e, prefs, { me })) record(e, false);
    },
  };

  return (
    <Ctx.Provider value={value}>
      {children}
      <NotificationCenter prefs={prefs} record={record} />
    </Ctx.Provider>
  );
}

export function useNotifications() {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useNotifications must be used inside NotificationsProvider");
  return ctx;
}

/** Watches the app store and records changes in the inbox, filtered by preferences. */
function NotificationCenter({ prefs, record }: { prefs: NotificationPrefs; record: Record }) {
  const me = useMe();
  const { state } = useAppStore();
  const pathname = useRouterState({ select: (s) => s.location.pathname });
  const viewingTableId = pathname.startsWith("/tables/") ? pathname.split("/")[2] : undefined;
  const snap = {
    bets: state.bets,
    messages: state.messages,
    tables: state.tables,
    invites: state.tableInvites,
  };
  const prev = useRef(snap);
  const live = useRef({ prefs, viewingTableId, record, blocked: state.blocks[me] ?? [] });
  live.current = { prefs, viewingTableId, record, blocked: state.blocks[me] ?? [] };

  useEffect(() => {
    const events = diffEvents(prev.current, snap, me);
    prev.current = snap;
    const { prefs, viewingTableId, record, blocked } = live.current;
    for (const e of events) {
      if (!shouldNotify(e, prefs, { me, blocked })) continue;
      // Already read if it's the chat you're looking at.
      const unseen = shouldNotify(e, prefs, { me, viewingTableId, blocked });
      record(e, !unseen);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `snap` is rebuilt from these each render
  }, [state.bets, state.messages, state.tables, state.tableInvites, me]);

  useEffect(() => {
    const off = onError((e) => {
      const { prefs, record } = live.current;
      const ev: NotificationEvent = { kind: "error", ...e };
      if (shouldNotify(ev, prefs, { me })) record(ev, false);
    });
    const onWindowError = () => emitError("Something went wrong", "Try that again in a moment.");
    window.addEventListener("error", onWindowError);
    window.addEventListener("unhandledrejection", onWindowError);
    return () => {
      off();
      window.removeEventListener("error", onWindowError);
      window.removeEventListener("unhandledrejection", onWindowError);
    };
  }, [me]);

  return null;
}

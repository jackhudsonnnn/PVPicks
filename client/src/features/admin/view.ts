import { useCallback, useEffect, useState } from "react";

const ADMIN_TABS = ["users", "reports", "perf", "sports"] as const;
export type AdminTab = (typeof ADMIN_TABS)[number];

/**
 * Where the admin is, kept in the URL's query string so a link or a screenshot's address bar points at the exact
 * view (a player's audit, a perf route, a game's inspector). Everything but `tab` belongs to one tab.
 */
interface AdminView {
  tab: AdminTab;
  /** Users: the audited player. */
  user?: string;
  /** Reports: queue or feedback. */
  section?: string;
  /** Perf: window, source and the route drilled into ("GET /tables/{tableId}"). */
  window?: string;
  source?: string;
  route?: string;
  /** Sports: the selection and the inspector panel. */
  league?: string;
  day?: string;
  game?: string;
  panel?: string;
  player?: string;
}

const KEYS = [
  "user",
  "section",
  "window",
  "source",
  "route",
  "league",
  "day",
  "game",
  "panel",
  "player",
] as const;

function parse(search: string): AdminView {
  const params = new URLSearchParams(search);
  const tab = params.get("tab");
  const view: AdminView = {
    tab: ADMIN_TABS.includes(tab as AdminTab) ? (tab as AdminTab) : "users",
  };
  for (const key of KEYS) {
    const value = params.get(key);
    if (value) view[key] = value;
  }
  return view;
}

function format(view: AdminView): string {
  const params = new URLSearchParams({ tab: view.tab });
  for (const key of KEYS) {
    const value = view[key];
    if (value) params.set(key, value);
  }
  return `?${params}`;
}

/**
 * The admin view and a setter. `push` adds a history entry (opening a player or a game), so Back returns to the
 * previous view; small changes (a filter) replace it.
 */
export function useAdminView() {
  const [view, setView] = useState<AdminView>(() => parse(window.location.search));
  useEffect(() => {
    const sync = () => setView(parse(window.location.search));
    window.addEventListener("popstate", sync);
    return () => window.removeEventListener("popstate", sync);
  }, []);
  const go = useCallback((next: AdminView, push = false) => {
    const url = `${window.location.pathname}${format(next)}`;
    if (push) window.history.pushState(window.history.state, "", url);
    else window.history.replaceState(window.history.state, "", url);
    setView(next);
  }, []);
  return [view, go] as const;
}

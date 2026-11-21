import { useQuery } from "@tanstack/react-query";
import { getAppMode, type AppMode } from "./app-mode-api";

export type { AppMode };

/**
 * The backend's mode; undefined until it answers. An unreachable backend (restarting, say) is retried every few
 * seconds rather than guessed at. It never changes while the page is open.
 */
export function useAppMode(): AppMode | undefined {
  const query = useQuery({
    queryKey: ["app-mode"],
    queryFn: ({ signal }) => getAppMode(signal),
    staleTime: Infinity,
    retry: false,
    refetchInterval: (state) => (state.state.status === "error" ? 3000 : false),
  });
  return query.data;
}

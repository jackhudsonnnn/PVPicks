import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { backendErrorMessage } from "@/lib/api-client";
import { userKey } from "@/lib/query-keys";
import type { User } from "@/features/people";
import { clearSignInLockout, getSignInLockouts, type PlayerLockout } from "./dashboard-api";

/**
 * Wrong sign-in codes, polled every 10 s. Ten wrong codes against one player lock that player (QR included) for the
 * window; anyone who knows a username can cause that, so the admin unlocks them here. One query serves the Users
 * banner, each player's card and the Perf row.
 */
export function useLockouts(user: User) {
  const queryClient = useQueryClient();
  const key = userKey(user.id, "admin-sign-in-lockout");
  const lockouts = useQuery({
    queryKey: key,
    queryFn: ({ signal }) => getSignInLockouts(signal),
    refetchInterval: 10_000,
    retry: false,
  });
  const clear = useMutation({
    mutationFn: (playerId?: string) => clearSignInLockout(playerId),
    onSuccess: (_, playerId) => {
      toast.success(
        playerId ? "Unlocked: they can sign in now." : "Every sign-in lockout cleared.",
      );
      void queryClient.invalidateQueries({ queryKey: key });
    },
    onError: (failure) => toast.error(backendErrorMessage(failure)),
  });
  return { lockouts, clear };
}

/** The players locked out right now, by id. */
export function lockedById(players: PlayerLockout[] | undefined) {
  return new Map((players ?? []).filter((p) => p.locked).map((p) => [p.id, p]));
}

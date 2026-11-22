import { LockOpen, ShieldAlert } from "lucide-react";
import { Button } from "@/components/ui/button";
import { backendErrorMessage } from "@/lib/api-client";
import type { User } from "@/features/people";
import { cn } from "@/lib/utils";
import { NameTag } from "./avatar";
import { inMinutes } from "./format";
import { useLockouts } from "./lockout-state";

/** Shown on Users only while someone is locked out (or the shared address backstop is), with Unlock for each. */
export function LockoutBanner({ user }: { user: User }) {
  const { lockouts, clear } = useLockouts(user);
  const data = lockouts.data;
  const locked = data?.players.filter((p) => p.locked) ?? [];
  if (!data || (locked.length === 0 && !data.address.locked)) return null;
  return (
    <div role="alert" className="space-y-2 rounded-xl border border-loss/50 bg-loss/10 p-3 text-sm">
      <p className="flex items-start gap-2">
        <ShieldAlert className="mt-0.5 size-4 shrink-0 text-loss" />
        <span>
          <span className="font-semibold">Locked out of code sign-in.</span>{" "}
          {data.address.locked
            ? `${data.address.wrongCodes} wrong codes from this network: nobody can sign in with a code${
                data.address.resetsAt ? ` for another ${minutesLeft(data.address.resetsAt)}` : ""
              } unless you clear it.`
            : `Too many wrong codes against ${locked.length === 1 ? "this player" : "these players"}; their QR won't work either until it resets or you unlock them.`}
        </span>
      </p>
      {locked.length > 0 && (
        <ul className="flex flex-wrap gap-2">
          {locked.map((player) => (
            <li
              key={player.id}
              className="flex items-center gap-2 rounded-lg border bg-surface px-2 py-1"
            >
              <NameTag name={player.username} hue={player.hue} size="sm" />
              {player.resetsAt && (
                <span className="text-xs text-muted-foreground">
                  {minutesLeft(player.resetsAt)} left
                </span>
              )}
              <Button
                size="sm"
                variant="outline"
                disabled={clear.isPending}
                aria-label={`Unlock ${player.username ?? "player"}`}
                onClick={() => clear.mutate(player.id)}
              >
                <LockOpen /> Unlock
              </Button>
            </li>
          ))}
        </ul>
      )}
      {data.address.locked && (
        <Button size="sm" disabled={clear.isPending} onClick={() => clear.mutate(undefined)}>
          <LockOpen /> Clear lockout
        </Button>
      )}
    </div>
  );
}

/** The Perf tab's always-on view: players with wrong codes, the shared backstop, and Clear all. */
export function LockoutRow({ user }: { user: User }) {
  const { lockouts, clear } = useLockouts(user);
  if (lockouts.isError)
    return (
      <p role="alert" className="text-sm">
        {backendErrorMessage(lockouts.error)}
      </p>
    );
  if (!lockouts.data) return null;
  const { players, address, addressLimit } = lockouts.data;
  const locked = players.filter((p) => p.locked).length;
  const any = players.length > 0 || address.wrongCodes > 0;
  return (
    <div
      className={cn(
        "flex flex-wrap items-center gap-x-3 gap-y-1 rounded-lg border px-3 py-2 text-sm",
        (locked > 0 || address.locked) && "border-loss/50 bg-loss/10",
      )}
    >
      <span className="font-mono text-xs font-bold uppercase tracking-[0.2em] text-muted-foreground">
        Sign-in codes
      </span>
      <span className={cn((locked > 0 || address.locked) && "font-semibold text-loss")}>
        {locked} locked · {players.length} with wrong codes · network {address.wrongCodes} of{" "}
        {addressLimit}
        {address.resetsAt ? ` · resets ${inMinutes(address.resetsAt)}` : ""}
      </span>
      <Button
        size="sm"
        variant={locked > 0 || address.locked ? "default" : "outline"}
        className="ml-auto"
        disabled={!any || clear.isPending}
        onClick={() => clear.mutate(undefined)}
      >
        <LockOpen /> Clear all
      </Button>
    </div>
  );
}

function minutesLeft(iso: string) {
  return inMinutes(iso).replace(/^in /, "");
}

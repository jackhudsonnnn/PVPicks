import type { ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { type User, PersonAvatar, usePerson } from "@/features/people";
import type { TableRef } from "@/features/tables";
import { formatCents } from "@/lib/money";
import { cn } from "@/lib/utils";

/** The pieces every ticket's Details is built from: titled sections of label/value rows, and point deltas. */

export function FactSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="space-y-1">
      <h4 className="font-mono text-[10px] font-bold uppercase tracking-widest text-primary">
        {title}
      </h4>
      <div className="divide-y rounded-lg border bg-surface px-3">{children}</div>
    </section>
  );
}

export function FactRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4 py-1.5 text-sm">
      <span className="text-muted-foreground">{label}</span>
      <span className="break-words text-right font-medium">{children}</span>
    </div>
  );
}

/**
 * A player's picture in Details, opening their profile on the table page (where you can also throw a tomato at
 * them). Like the byline's proposer link, it closes the ticket on the way.
 */
export function PlayerAvatar({
  id,
  table,
  user,
  expire,
  peopleKey,
}: {
  id: string;
  table: TableRef;
  user: User;
  expire: () => void;
  peopleKey: readonly string[];
}) {
  const name = usePerson(id, user, expire, peopleKey)?.name;
  return (
    <Link
      to="/tables/$tableId"
      params={{ tableId: table.id }}
      search={{ profile: id }}
      aria-label={`${name ?? "Player"}'s profile`}
      className="shrink-0 rounded-full transition-transform hover:scale-110"
    >
      <PersonAvatar id={id} user={user} expire={expire} queryKey={peopleKey} size="xs" />
    </Link>
  );
}

/** A signed result in points: green up, red down, "Even" for nothing. */
export function Delta({ cents }: { cents: number }) {
  return (
    <span
      className={cn(
        "w-16 text-right font-mono text-xs font-bold",
        cents > 0 ? "text-win" : cents < 0 ? "text-loss" : "text-muted-foreground",
      )}
    >
      {cents === 0 ? "Even" : formatCents(cents, true)}
    </span>
  );
}

interface Shuffle {
  round: number;
  commitment: string;
  decks?: number | undefined;
}

/**
 * A card game's shuffle audit (domain rules, "Shuffle audit"): each round's or hand's fingerprint from the deal, and
 * once the game is over the browser's own check that each revealed secret matches it and that the dealt cards came
 * off the top of that shuffle. `check` runs only then.
 */
export function ShuffleAudit({
  unit,
  intro,
  shuffles,
  done,
  check,
}: {
  /** "Round" (Jackblack's shoes) or "Hand" (Hold'em's decks). */
  unit: "Round" | "Hand";
  intro: string;
  shuffles: Shuffle[];
  done: boolean;
  check: () => Promise<{ round: number; committed: boolean; dealt: boolean }[] | null>;
}) {
  const audit = useQuery({
    queryKey: ["card-audit", unit, shuffles],
    queryFn: check,
    enabled: done,
    staleTime: Infinity,
    retry: false,
  });
  return (
    <FactSection title="Shuffle audit">
      <p className="py-1.5 text-xs text-muted-foreground">{intro}</p>
      {shuffles.map((shuffle) => {
        const checked = audit.data?.find((r) => r.round === shuffle.round);
        const ok = checked?.committed && checked.dealt;
        const decks =
          shuffle.decks !== undefined && unit === "Round"
            ? `${shuffle.decks} deck${shuffle.decks === 1 ? "" : "s"} · `
            : "";
        return (
          <FactRow key={shuffle.round} label={`${unit} ${shuffle.round}`}>
            <span className="block font-mono text-xs" title={shuffle.commitment}>
              #{shuffle.commitment.slice(0, 16)}…
            </span>
            {done && (
              <span className="block text-xs text-muted-foreground">
                {decks}
                {checked === undefined ? (
                  audit.isError ? (
                    "couldn't check"
                  ) : (
                    "checking…"
                  )
                ) : (
                  <span className={cn("font-semibold", ok ? "text-win" : "text-loss")}>
                    {ok
                      ? "checks out"
                      : !checked.committed
                        ? "doesn't match its fingerprint"
                        : "cards don't match the shuffle"}
                  </span>
                )}
              </span>
            )}
          </FactRow>
        );
      })}
    </FactSection>
  );
}

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Search } from "lucide-react";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { HUE_BG } from "@/features/people";
import { cn } from "@/lib/utils";
import type { DemoAccount, DemoAccounts } from "./api";
import { demoAccounts } from "./api";

/** What each main account is for, shown under its name. */
const ABOUT: Record<string, string> = {
  newbie: "Brand new: no friends, tables or history",
  intermediate: "9 friends, a few tables, a short history",
  pro: "60 friends, lots of tables, bets and blocks",
  admin: "Moderation queue and site health",
};

/**
 * The dev demo's sign-in: pick a main account or search the demo users; no passwords. `initial` is the first
 * (empty-search) answer, which also told the sign-in page the demo is on.
 */
export function DemoPicker({
  initial,
  busy,
  pick,
}: {
  initial: DemoAccounts;
  busy: boolean;
  pick: (account: DemoAccount) => void;
}) {
  const [query, setQuery] = useState("");
  const search = query.trim().toLowerCase();
  const results = useQuery({
    queryKey: ["demo-accounts", search],
    queryFn: ({ signal }) => demoAccounts(search, signal),
    enabled: search !== "",
    retry: false,
  });
  const others = search === "" ? initial.others : (results.data?.others ?? []);

  return (
    <div className="space-y-4 rounded-2xl border bg-surface p-5">
      <h1 className="font-display text-2xl font-extrabold uppercase">Pick an account</h1>
      <p className="text-sm text-muted-foreground">
        Demo world: sign in as anyone. If something asks for a password, it's “demo”. Reset it from
        the Dev page.
      </p>
      <ul className="space-y-2">
        {initial.main.map((account) => (
          <li key={account.id}>
            <AccountButton
              account={account}
              about={ABOUT[account.username]}
              busy={busy}
              pick={pick}
            />
          </li>
        ))}
      </ul>
      <div className="space-y-1.5">
        <Label htmlFor="demo-search">Demo users</Label>
        <div className="relative">
          <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            id="demo-search"
            className="pl-9"
            placeholder="Search by username"
            autoCapitalize="none"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </div>
      </div>
      <ul className="max-h-64 space-y-1 overflow-y-auto">
        {others.map((account) => (
          <li key={account.id}>
            <AccountButton account={account} busy={busy} pick={pick} compact />
          </li>
        ))}
        {search !== "" && results.isSuccess && others.length === 0 && (
          <li className="px-2 py-1 text-sm text-muted-foreground">No one by that name.</li>
        )}
      </ul>
    </div>
  );
}

function AccountButton({
  account,
  about,
  busy,
  pick,
  compact = false,
}: {
  account: DemoAccount;
  about?: string | undefined;
  busy: boolean;
  pick: (account: DemoAccount) => void;
  compact?: boolean;
}) {
  return (
    <button
      type="button"
      disabled={busy}
      onClick={() => pick(account)}
      className={cn(
        "flex w-full items-center gap-3 rounded-xl text-left transition-colors hover:bg-muted disabled:opacity-60",
        compact ? "px-2 py-1.5" : "border px-3 py-2.5",
      )}
    >
      <span
        aria-hidden
        className={cn(
          "inline-flex shrink-0 items-center justify-center rounded-full font-display font-extrabold uppercase text-primary-foreground",
          HUE_BG[account.hue as keyof typeof HUE_BG],
          compact ? "size-7 text-[10px]" : "size-9 text-xs",
        )}
      >
        {account.username.slice(0, 2)}
      </span>
      <span className="min-w-0">
        <span className="block truncate text-sm font-semibold">
          {account.username}
          {account.role === "ADMIN" && " (admin)"}
          {account.frozen && " (frozen)"}
        </span>
        {about && <span className="block truncate text-xs text-muted-foreground">{about}</span>}
      </span>
    </button>
  );
}

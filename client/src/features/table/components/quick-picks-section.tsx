import { useState } from "react";
import { Check, Copy, RefreshCw, Radio, Zap, X } from "lucide-react";
import { Link } from "@tanstack/react-router";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { SettingRow, SettingsSection } from "@/components/settings";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { useAppStore } from "@/features/store";
import type { Table } from "@/features/table/types";
import { CODE_LENGTH, isSharing, subscribersOf, subscriptionsOf } from "../quick-picks";

/** Sample feed code so the demo has something to subscribe to. */
const SAMPLE_CODE = "K4R7QX";

/** Host tab: turn on Quick Picks, share this table's bets, and subscribe to other tables. */
export function QuickPicksSection({ table }: { table: Table }) {
  const { state, actions } = useAppStore();
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const on = !!table.quickPicksOn;
  const subs = subscriptionsOf(table)
    .map((id) => state.tables.find((t) => t.id === id))
    .filter((t): t is Table => !!t);
  const subscriberCount = subscribersOf(state.tables, table.id).length;

  const subscribe = () => {
    const err = actions.subscribe(table.id, code);
    setError(err);
    if (!err) setCode("");
  };

  return (
    <SettingsSection
      title="Quick Picks"
      description="Advanced. Share this table's bets with other tables, or get another table's bets posted here automatically."
    >
      <SettingRow
        icon={Zap}
        htmlFor="switch-quick-picks"
        label="Turn on Quick Picks"
        description="Every table still only bets among itself. Points never move between tables."
        control={
          <Switch
            id="switch-quick-picks"
            checked={on}
            onCheckedChange={(v) => actions.setQuickPicks(table.id, v)}
          />
        }
      />
      {on && (
        <>
          <SettingRow
            icon={Radio}
            label="This table's share code"
            description="Give it to other hosts so their table gets your bets. They don't need to give you anything back."
          />
          {isSharing(table) && (
            <div className="space-y-2 px-4 py-3.5 sm:px-5">
              <div className="flex flex-wrap items-center gap-2">
                <span className="scoreboard rounded-lg border bg-sidebar px-3 py-1.5 text-xl tracking-[0.3em]">
                  {table.shareCode}
                </span>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => {
                    void navigator.clipboard?.writeText(table.shareCode!);
                    setCopied(true);
                    setTimeout(() => setCopied(false), 1500);
                  }}
                >
                  {copied ? <Check /> : <Copy />} {copied ? "Copied" : "Copy"}
                </Button>
                <AlertDialog>
                  <AlertDialogTrigger asChild>
                    <Button size="sm" variant="ghost">
                      <RefreshCw /> New code
                    </Button>
                  </AlertDialogTrigger>
                  <AlertDialogContent>
                    <AlertDialogHeader>
                      <AlertDialogTitle>Make a new code?</AlertDialogTitle>
                      <AlertDialogDescription>
                        Every table using the old code is cut off. Tickets they already got stay
                        with them. Hosts you still want will need the new code.
                      </AlertDialogDescription>
                    </AlertDialogHeader>
                    <AlertDialogFooter>
                      <AlertDialogCancel>Cancel</AlertDialogCancel>
                      <AlertDialogAction onClick={() => actions.setSharing(table.id, true)}>
                        New code
                      </AlertDialogAction>
                    </AlertDialogFooter>
                  </AlertDialogContent>
                </AlertDialog>
              </div>
              <p className="text-xs text-muted-foreground">
                {subscriberCount === 0
                  ? "No tables are subscribed yet."
                  : `${subscriberCount} ${subscriberCount === 1 ? "table is" : "tables are"} subscribed.`}{" "}
                Copies of tickets that started here can't be shared again.
              </p>
            </div>
          )}

          <div className="space-y-2.5 px-4 py-3.5 sm:px-5">
            <p className="text-sm font-semibold">Subscribe to a table</p>
            <p className="text-xs text-muted-foreground">
              Type in the code another host gave you. Their new bets post here right away as exact
              copies (same wager, timer and options). They call the result, and you can wash it if
              it looks wrong. Free for now.
            </p>
            <form
              className="flex flex-wrap gap-2"
              onSubmit={(e) => {
                e.preventDefault();
                subscribe();
              }}
            >
              <Input
                value={code}
                onChange={(e) => {
                  setCode(e.target.value.toUpperCase());
                  setError(null);
                }}
                maxLength={CODE_LENGTH + 2}
                placeholder="ABC123"
                aria-label="Quick Picks code"
                className="h-10 w-36 bg-sidebar font-mono uppercase tracking-widest"
              />
              <Button type="submit" size="sm" className="h-10" disabled={!code.trim()}>
                Subscribe
              </Button>
            </form>
            {error && (
              <p role="alert" className="text-xs font-medium text-loss">
                {error}
              </p>
            )}
            {!subs.some((t) => t.id === "t_arena") && (
              <p className="text-xs text-muted-foreground">
                Try the sample stadium feed:{" "}
                <button
                  type="button"
                  className="font-mono font-semibold text-primary underline underline-offset-2"
                  onClick={() => {
                    setCode(SAMPLE_CODE);
                    setError(null);
                  }}
                >
                  {SAMPLE_CODE}
                </button>
              </p>
            )}
            {subs.length > 0 && (
              <ul className="space-y-1.5 pt-1">
                <AnimatedList>
                  {subs.map((t) => (
                    <AnimatedItem as="li" key={t.id}>
                      <div className="flex items-center gap-2 rounded-lg border bg-sidebar px-3 py-2">
                        <span className="text-lg">{t.emoji}</span>
                        <span className="min-w-0 flex-1 break-words text-sm font-semibold">
                          {t.name}
                        </span>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => actions.unsubscribe(table.id, t.id)}
                          aria-label={`Unsubscribe from ${t.name}`}
                        >
                          <X /> Unsubscribe
                        </Button>
                      </div>
                    </AnimatedItem>
                  ))}
                </AnimatedList>
              </ul>
            )}
            <p className="text-xs text-muted-foreground">
              New to this?{" "}
              <Link
                to="/how-to-play"
                hash="quick-picks"
                className="text-primary underline underline-offset-2"
              >
                How Quick Picks work
              </Link>
            </p>
          </div>
        </>
      )}
    </SettingsSection>
  );
}

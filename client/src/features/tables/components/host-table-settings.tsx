/** The Host panel's table name and icon, switches, Quick Picks and deletion. */
import { useEffect, useState } from "react";
import { Check, Copy, Radio, RefreshCw, Trash2, Zap } from "lucide-react";
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
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { SettingRow, SettingsSection } from "@/components/settings";
import { TABLE_EMOJIS } from "@/features/tables/emojis";
import { cn } from "@/lib/utils";
import type { RemainderRule } from "@/features/tables/api";
import { tableNameProblem } from "@/features/tables/rules";
import { PanelProps, REMAINDER, SWITCHES, busy, hasOpenBets } from "./host-shared";

export function TableDetailsSection({ table, bets, tableActions }: PanelProps) {
  const [name, setName] = useState(table.name);
  const [emoji, setEmoji] = useState(table.emoji);
  const [problem, setProblem] = useState<string | null>(null);
  const [remainderBlocked, setRemainderBlocked] = useState(false);
  useEffect(() => {
    setName(table.name);
    setEmoji(table.emoji);
  }, [table.name, table.emoji]);
  const dirty = name.trim() !== table.name || emoji !== table.emoji;
  const disabled = busy(tableActions);
  const active = (bets ?? []).some((bet) => bet.status === "ACTIVE");
  const rule = REMAINDER.find((r) => r.id === table.remainderRule)!;
  const emojis = TABLE_EMOJIS.includes(table.emoji) ? TABLE_EMOJIS : [table.emoji, ...TABLE_EMOJIS];
  return (
    <SettingsSection title="Table details" description="Only hosts can change these.">
      {SWITCHES.map((sw) => (
        <SettingRow
          key={sw.id}
          icon={sw.icon}
          htmlFor={`switch-${sw.id}`}
          label={sw.label}
          description={sw.description}
          control={
            <Switch
              id={`switch-${sw.id}`}
              checked={sw.isOn(table)}
              disabled={disabled}
              onCheckedChange={(on) =>
                tableActions.start({ kind: "settings", tableId: table.id, [sw.id]: on })
              }
            />
          }
        />
      ))}
      <div className="space-y-1.5 px-4 py-3.5 sm:px-5">
        <label htmlFor="remainder-rule" className="text-sm font-semibold">
          Leftover cents go to
        </label>
        <Select
          value={table.remainderRule}
          onValueChange={(value) =>
            tableActions.start({
              kind: "remainder",
              tableId: table.id,
              rule: value as RemainderRule,
            })
          }
          onOpenChange={(open) => {
            if (open && active) setRemainderBlocked(true);
          }}
          disabled={disabled}
          {...(active ? { open: false } : {})}
        >
          <SelectTrigger id="remainder-rule" className="h-10 bg-sidebar">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {REMAINDER.map((r) => (
              <SelectItem key={r.id} value={r.id}>
                {r.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <p className="text-xs text-muted-foreground">
          {rule.description} For a single bet's payout when its pot doesn't split evenly to the
          cent. Each bet keeps the rule it had when its picks locked.
        </p>
        <AlertDialog open={remainderBlocked} onOpenChange={setRemainderBlocked}>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Can't change this right now</AlertDialogTitle>
              <AlertDialogDescription>
                A bet at this table is still taking picks. Try again once every active bet has
                locked.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogAction>OK</AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
      <form
        className="space-y-3 px-4 py-3.5 sm:px-5"
        onSubmit={(event) => {
          event.preventDefault();
          const invalid = tableNameProblem(name);
          setProblem(invalid);
          if (invalid || !dirty) return;
          tableActions.start({
            kind: "profile",
            tableId: table.id,
            ...(name.trim() !== table.name ? { name: name.trim() } : {}),
            ...(emoji !== table.emoji ? { emoji } : {}),
          });
        }}
      >
        <Input
          value={name}
          maxLength={80}
          onChange={(e) => setName(e.target.value)}
          aria-label="Table name"
          className="h-11 bg-sidebar"
        />
        <div className="flex flex-wrap gap-1.5" role="radiogroup" aria-label="Table icon">
          {emojis.map((e) => (
            <button
              key={e}
              type="button"
              role="radio"
              aria-checked={emoji === e}
              onClick={() => setEmoji(e)}
              aria-label={`Use ${e}`}
              className={cn(
                "flex size-9 items-center justify-center rounded-lg border bg-sidebar text-lg transition-colors",
                emoji === e ? "border-primary bg-primary/15" : "hover:border-primary/50",
              )}
            >
              {e}
            </button>
          ))}
        </div>
        {problem && (
          <p role="alert" className="text-xs text-loss">
            {problem}
          </p>
        )}
        <Button size="sm" type="submit" disabled={!dirty || disabled}>
          Save changes
        </Button>
      </form>
    </SettingsSection>
  );
}

/** Quick Picks sharing. Subscribing to another table's code isn't available yet. */
export function QuickPicksSection({ table, tableActions }: PanelProps) {
  const [copied, setCopied] = useState(false);
  const disabled = busy(tableActions);
  const on = table.quickPicksEnabled;
  return (
    <SettingsSection
      title="Quick Picks"
      description="Advanced. Share this table's bets with other tables. Every table still only bets among itself; points never move between tables."
    >
      <SettingRow
        icon={Zap}
        htmlFor="switch-quick-picks"
        label="Turn on Quick Picks"
        description="Turning it off stops sharing and disconnects subscribers."
        control={
          <Switch
            id="switch-quick-picks"
            checked={on}
            disabled={disabled}
            onCheckedChange={(enabled) =>
              tableActions.start({ kind: "quickpicks", tableId: table.id, enabled })
            }
          />
        }
      />
      {on && (
        <>
          <SettingRow
            icon={Radio}
            htmlFor="switch-sharing"
            label="Share this table's bets"
            description="Other hosts with your code get copies of bets started here."
            control={
              <Switch
                id="switch-sharing"
                checked={table.quickPickSharing}
                disabled={disabled}
                onCheckedChange={(sharing) =>
                  tableActions.start({ kind: "sharing", tableId: table.id, sharing })
                }
              />
            }
          />
          {table.quickPickSharing && table.quickPickCode && (
            <div className="space-y-2 px-4 py-3.5 sm:px-5">
              <div className="flex flex-wrap items-center gap-2">
                <span className="scoreboard rounded-lg border bg-sidebar px-3 py-1.5 text-xl tracking-[0.3em]">
                  {table.quickPickCode}
                </span>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => {
                    void navigator.clipboard?.writeText(table.quickPickCode!);
                    setCopied(true);
                    setTimeout(() => setCopied(false), 1500);
                  }}
                >
                  {copied ? <Check /> : <Copy />} {copied ? "Copied" : "Copy"}
                </Button>
                <AlertDialog>
                  <AlertDialogTrigger asChild>
                    <Button size="sm" variant="ghost" disabled={disabled}>
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
                      <AlertDialogAction
                        onClick={() => tableActions.start({ kind: "rotate", tableId: table.id })}
                      >
                        New code
                      </AlertDialogAction>
                    </AlertDialogFooter>
                  </AlertDialogContent>
                </AlertDialog>
              </div>
            </div>
          )}
          <div className="space-y-1 px-4 py-3.5 sm:px-5">
            <p className="text-sm font-semibold">Subscribe to a table</p>
            <p className="text-xs text-muted-foreground">
              Coming soon: enter another host's code to get their bets posted here.
            </p>
          </div>
        </>
      )}
    </SettingsSection>
  );
}

/** Deleting is final; the chat stays readable for 90 days. */
export function DeleteTableSection({ table, bets, tableActions }: PanelProps) {
  const allowed = !hasOpenBets(bets);
  return (
    <SettingsSection
      title="Delete table"
      description={
        allowed
          ? "Everyone loses access. The chat stays readable for 90 days. This can't be undone."
          : "Finish every active and pending bet and settle points before deleting the table."
      }
    >
      <div className="px-4 py-3.5 sm:px-5">
        <AlertDialog>
          <AlertDialogTrigger asChild>
            <Button size="sm" variant="destructive" disabled={!allowed || busy(tableActions)}>
              <Trash2 /> Delete table
            </Button>
          </AlertDialogTrigger>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Delete {table.name}?</AlertDialogTitle>
              <AlertDialogDescription>
                Everyone loses access to this table. Its chat stays readable for 90 days. This can't
                be undone.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>Cancel</AlertDialogCancel>
              <AlertDialogAction
                onClick={() => tableActions.start({ kind: "delete", tableId: table.id })}
              >
                Delete
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
    </SettingsSection>
  );
}

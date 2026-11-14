import { useMemo, useState } from "react";
import { ArrowLeft, Eye, Send } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { Switch } from "@/components/ui/switch";
import { ScrollContainer } from "@/components/scroll-container";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { Section } from "@/features/bets/components/form-parts";
import { U2PickEditor } from "@/features/bets/components/u2pick-editor";
import { MODES, type ModeDef } from "@/features/bets/modes";
import type { SettlerRule } from "@/features/bets/types";
import { cleanOptions, customBetProblem } from "@/features/bets/u2pick";
import {
  canStep,
  PICK_WINDOW_DEFAULT,
  PICK_WINDOWS,
  stepWager,
  WAGER_DEFAULT_CENTS,
  WAGER_MAX_CENTS,
  WAGER_MIN_CENTS,
  WAGER_STEPS_CENTS,
} from "@/features/bets/wager";
import { formatCents } from "@/lib/money";
import { cn } from "@/lib/utils";
import type { BackendBet, BackendTable, BackendUser } from "./backend-api";
import { BetFacts } from "./backend-bet-details";
import type { BetActions } from "./backend-bet-controls";
import { BetTicket } from "./backend-ticket";
import { proposeBlock } from "./ticket-model";

const LEAGUE_BADGES = [
  { id: "nfl", label: "NFL", className: "border-primary/30 bg-primary/10 text-primary" },
  {
    id: "nba",
    label: "NBA",
    className: "border-status-pending/30 bg-status-pending/10 text-status-pending",
  },
];

/** Propose a bet: choose a mode, set it up, preview the ticket, send. Only U2Pick is available until sports bets exist. */
export function ProposeBetSheet({
  open,
  onOpenChange,
  table,
  user,
  expire,
  peopleKey,
  actions,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  table: BackendTable;
  user: BackendUser;
  expire: () => void;
  peopleKey: readonly string[];
  actions: BetActions;
}) {
  const [mode, setMode] = useState<ModeDef | null>(null);
  const [question, setQuestion] = useState("");
  const [options, setOptions] = useState(["Yes", "No"]);
  const [settledBy, setSettledBy] = useState<SettlerRule>("hosts");
  const [forFun, setForFun] = useState(false);
  const [wager, setWager] = useState(WAGER_DEFAULT_CENTS);
  const [timer, setTimer] = useState(PICK_WINDOW_DEFAULT);
  const [previewing, setPreviewing] = useState(false);
  const block = proposeBlock(table, user.id);
  const busy = actions.busy || !!actions.pending || !!actions.storageError;
  const problem = mode ? customBetProblem(question, options) : null;

  const previewBet: BackendBet = useMemo(() => {
    const now = Date.now();
    return {
      id: "00000000-0000-4000-8000-000000000000",
      tableId: table.id,
      proposerId: user.id,
      modeId: "u2pick",
      config: {},
      question: question.trim() || "Your question shows up here",
      choices: cleanOptions(options),
      wagerCents: forFun ? 0 : wager,
      forFun,
      status: "ACTIVE",
      locksAt: new Date(now + timer * 1000).toISOString(),
      createdAt: new Date(now).toISOString(),
      pointsSettled: false,
      pickCount: 0,
      myPick: null,
      myParticipation: "not_joined",
      settledBy,
    };
    // Rebuilt whenever the preview opens, so its clock starts fresh.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [previewing, table.id, user.id, question, options, forFun, wager, timer, settledBy]);

  function reset() {
    setMode(null);
    setQuestion("");
    setOptions(["Yes", "No"]);
    setSettledBy("hosts");
    setForFun(false);
    setWager(WAGER_DEFAULT_CENTS);
    setTimer(PICK_WINDOW_DEFAULT);
    setPreviewing(false);
  }

  function submit() {
    if (!mode || problem || block || busy) return;
    actions.start({
      kind: "propose",
      tableId: table.id,
      proposal: {
        question: question.trim(),
        choices: cleanOptions(options),
        wagerCents: forFun ? 0 : wager,
        durationSec: timer,
        forFun,
        settledBy,
      },
    });
    onOpenChange(false);
    reset();
  }

  return (
    <Sheet
      open={open}
      onOpenChange={(next) => {
        onOpenChange(next);
        if (!next) reset();
      }}
    >
      <SheetContent side="right" className="flex w-full flex-col gap-0 p-0 sm:max-w-lg">
        <SheetHeader className="border-b px-5 py-4">
          <div className="flex items-center gap-2">
            {mode && (
              <Button
                variant="ghost"
                size="icon"
                className="-ml-2 size-8"
                onClick={() => (previewing ? setPreviewing(false) : setMode(null))}
                aria-label={previewing ? "Back to setup" : "Back to modes"}
              >
                <ArrowLeft />
              </Button>
            )}
            <SheetTitle className="font-display text-2xl font-extrabold uppercase">
              {previewing ? "Preview ticket" : mode ? mode.name : "Propose a bet"}
            </SheetTitle>
          </div>
          <SheetDescription>
            {mode ? mode.description : "Choose a game mode to send a ticket to your tablemates."}
          </SheetDescription>
        </SheetHeader>

        <ScrollContainer
          key={previewing ? "preview" : (mode?.id ?? "modes")}
          isVisible={false}
          className="min-h-0 flex-1 px-5 py-5"
        >
          {previewing ? (
            <div className="space-y-4">
              <BetTicket
                bet={previewBet}
                table={table}
                user={user}
                expire={expire}
                peopleKey={peopleKey}
                className="max-w-none"
              />
              <BetFacts
                bet={previewBet}
                table={table}
                user={user}
                expire={expire}
                peopleKey={peopleKey}
              />
            </div>
          ) : !mode ? (
            <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
              {MODES.map((m) => {
                const Icon = m.icon;
                const off = !m.custom || !!block;
                return (
                  <Button
                    variant="outline"
                    key={m.id}
                    disabled={off}
                    onClick={() => setMode(m)}
                    className="group flex h-auto flex-col items-start gap-3 whitespace-normal rounded-xl border bg-surface p-4 text-left transition-all enabled:hover:border-primary enabled:hover:bg-surface-raised disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    <span className="flex w-full items-center justify-between gap-2">
                      <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/15 text-primary transition-colors group-hover:bg-primary group-hover:text-primary-foreground">
                        <Icon className="size-5" />
                      </span>
                      {!m.custom && (
                        <span className="flex flex-wrap justify-end gap-1">
                          {LEAGUE_BADGES.map((l) => (
                            <span
                              key={l.id}
                              className={cn(
                                "rounded-full border px-2 py-0.5 font-mono text-[10px] font-bold",
                                l.className,
                              )}
                            >
                              {l.label}
                            </span>
                          ))}
                        </span>
                      )}
                    </span>
                    <span>
                      <span className="block font-display text-lg font-bold uppercase leading-none">
                        {m.name}
                      </span>
                      <span className="mt-1 block text-xs text-muted-foreground">
                        {!m.custom ? "Coming soon" : (block ?? m.tagline)}
                      </span>
                    </span>
                  </Button>
                );
              })}
            </div>
          ) : (
            <div className="space-y-6">
              <U2PickEditor
                question={question}
                onQuestionChange={setQuestion}
                options={options}
                onOptionsChange={setOptions}
                settledBy={settledBy}
                onSettledByChange={setSettledBy}
              />

              <Section label="Wager (points each)">
                <div className="space-y-3">
                  <div className="flex items-start justify-between gap-4 rounded-xl border bg-surface p-3">
                    <span className="min-w-0">
                      <span className="block text-sm font-semibold">4fun</span>
                      <span className="block text-xs text-muted-foreground">
                        No points at stake and nothing tracked. Just bragging rights.
                      </span>
                    </span>
                    <Switch checked={forFun} onCheckedChange={setForFun} aria-label="4fun bet" />
                  </div>
                  <div
                    className={cn(
                      "space-y-2.5 rounded-xl border bg-surface p-3 transition-opacity",
                      forFun && "pointer-events-none opacity-40",
                    )}
                    aria-disabled={forFun || undefined}
                  >
                    <div className="flex items-center justify-between gap-3">
                      <span
                        className="scoreboard text-3xl leading-none text-primary"
                        aria-live="polite"
                        aria-label="Wager in points"
                      >
                        {formatCents(wager)}
                      </span>
                      <button
                        type="button"
                        onClick={() => setWager(WAGER_MIN_CENTS)}
                        disabled={forFun || wager === WAGER_MIN_CENTS}
                        className="rounded-md px-2 py-1 font-mono text-[10px] font-bold uppercase tracking-wider text-muted-foreground transition-colors hover:text-foreground disabled:opacity-40"
                      >
                        Reset to {formatCents(WAGER_MIN_CENTS)}
                      </button>
                    </div>
                    <div className="grid grid-cols-3 gap-2">
                      {WAGER_STEPS_CENTS.map((step) => (
                        <button
                          key={step}
                          type="button"
                          onClick={() => setWager((w) => stepWager(w, step))}
                          disabled={forFun || !canStep(wager, step)}
                          aria-label={`Add ${formatCents(step)} points`}
                          className="rounded-lg border bg-background py-2 scoreboard text-base transition-colors hover:border-primary disabled:opacity-40 disabled:hover:border-border"
                        >
                          +{formatCents(step)}
                        </button>
                      ))}
                    </div>
                    <p className="font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
                      Min {formatCents(WAGER_MIN_CENTS)} · Max {formatCents(WAGER_MAX_CENTS)}
                    </p>
                  </div>
                </div>
              </Section>

              <Section label="Pick window">
                <SegmentedTabs<string>
                  size="sm"
                  ariaLabel="Pick window"
                  tabs={PICK_WINDOWS.map((t) => ({ id: String(t.sec), label: t.label }))}
                  value={String(timer)}
                  onChange={(v) => setTimer(Number(v))}
                />
              </Section>
              <p className="text-xs text-muted-foreground">
                Sending a bet doesn't place your pick; pick on the ticket like everyone else.
              </p>
            </div>
          )}
        </ScrollContainer>

        {mode && (
          <div className="space-y-2 border-t bg-sidebar p-4">
            {(problem ?? block) && (
              <p className="text-center text-xs text-muted-foreground">{problem ?? block}</p>
            )}
            <Button
              variant="hero"
              size="lg"
              className="w-full"
              onClick={() => (previewing ? submit() : setPreviewing(true))}
              disabled={!!problem || !!block || (previewing && busy)}
            >
              {previewing ? (
                <>
                  <Send /> Send · {forFun ? "4fun" : `${formatCents(wager)} pts`}
                </>
              ) : (
                <>
                  <Eye /> Preview ticket
                </>
              )}
            </Button>
          </div>
        )}
      </SheetContent>
    </Sheet>
  );
}

import { useEffect, useMemo, useState } from "react";
import { ArrowLeft, Eye, Info, Send } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { ScrollContainer } from "@/components/scroll-container";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { Section, ValueSlider } from "./form-parts";
import { U2PickEditor } from "./u2pick-editor";
import { JackblackSetup, JackblackWorstCase } from "./jackblack-setup";
import { HoldemSetup, HoldemWorstCase } from "./holdem-setup";
import {
  DEFAULT_HOLDEM_RULES,
  holdemRulesFromConfig,
  holdemRulesProblem,
  type HoldemRules,
} from "@/features/bets/holdem";
import {
  DEFAULT_RULES,
  rulesFromConfig,
  rulesProblem,
  type JackblackRules,
} from "@/features/bets/jackblack";
import { MODES, type ModeDef, useModeAvailability } from "@/features/bets/modes";
import { ModeGrid } from "./mode-grid";
import { ModeInfo } from "./mode-info";
import { GameNow } from "./game-now";
import { SportsSetup } from "./sports-setup";
import { useSportsDraft } from "@/features/bets/use-sports-draft";
import type { SettlerRule } from "@/features/bets/types";
import { cleanOptions, customBetProblem } from "@/features/bets/u2pick";
import {
  PICK_WINDOW_DEFAULT,
  PICK_WINDOWS,
  WAGER_DEFAULT_CENTS,
  WAGER_MAX_CENTS,
} from "@/features/bets/wager";
import { formatCents } from "@/lib/money";
import { cn } from "@/lib/utils";
import type { Bet } from "@/features/bets/api";
import type { Table } from "@/features/tables";
import type { User } from "@/features/people";
import { BetFacts } from "./bet-details";
import type { BetActions } from "./bet-controls";
import { BetTicket } from "./ticket";
import {
  holdemBlock,
  jackblackBlock,
  proposeBlock,
  u2pickBlock,
} from "@/features/bets/ticket-model";

/**
 * Propose a bet: choose a mode (`ModeGrid`), set it up, preview the ticket, send. The server says which modes can
 * be proposed; the rest show "Coming soon".
 */
export function ProposeBetSheet({
  open,
  onOpenChange,
  table,
  user,
  expire,
  peopleKey,
  actions,
  from,
}: {
  /** Resend: a finished bet whose terms fill the form (the proposer can change them before sending). */
  from?: Bet | undefined;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  table: Table;
  user: User;
  expire: () => void;
  peopleKey: readonly string[];
  actions: BetActions;
}) {
  const [mode, setMode] = useState<ModeDef | null>(null);
  const [question, setQuestion] = useState("");
  const [options, setOptions] = useState(["Yes", "No"]);
  const [settledBy, setSettledBy] = useState<SettlerRule>("hosts");
  const [wager, setWager] = useState(WAGER_DEFAULT_CENTS);
  // The slider's 0 is a 4fun bet: no points at stake and nothing tracked.
  const forFun = wager === 0;
  const [timer, setTimer] = useState(PICK_WINDOW_DEFAULT);
  const [previewing, setPreviewing] = useState(false);
  // The mode's info (the (i) beside its name), shown in place of the setup.
  const [about, setAbout] = useState(false);
  const [rules, setRules] = useState<JackblackRules>(DEFAULT_RULES);
  const [holdemRules, setHoldemRules] = useState<HoldemRules>(DEFAULT_HOLDEM_RULES);
  const sports = useSportsDraft(user, mode, open, from);

  // Resend: fill the form with a finished bet's terms once the sheet opens with it.
  useEffect(() => {
    if (!open || !from) return;
    const def = MODES.find((m) => m.id === from.modeId);
    if (!def) return;
    setMode(def);
    setAbout(false);
    setPreviewing(false);
    setWager(from.forFun ? 0 : from.wagerCents);
    const window = (Date.parse(from.locksAt) - Date.parse(from.createdAt)) / 1000;
    setTimer(
      PICK_WINDOWS.reduce((best, w) =>
        Math.abs(w.sec - window) < Math.abs(best.sec - window) ? w : best,
      ).sec,
    );
    if (def.custom) {
      setQuestion(from.question);
      setOptions(from.choices);
      setSettledBy(from.settledBy ?? "hosts");
    }
    if (def.cardGame === "jackblack") setRules(rulesFromConfig(from.config));
    if (def.cardGame === "holdem") setHoldemRules(holdemRulesFromConfig(from.config));
    // Only when a new bet is handed over.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, from?.id]);
  const block = proposeBlock(table, user.id);
  const availability = useModeAvailability();
  const busy = actions.busy || !!actions.pending || !!actions.storageError;
  const problem = !mode
    ? null
    : mode.cardGame === "jackblack"
      ? (jackblackBlock(table, user.id) ?? rulesProblem(rules))
      : mode.cardGame === "holdem"
        ? (holdemBlock(table, user.id) ?? holdemRulesProblem(holdemRules))
        : mode.custom
          ? (u2pickBlock(table) ?? customBetProblem(question, options))
          : sports.problem;

  function pickMode(next: ModeDef) {
    setMode(next);
    setAbout(false);
    sports.startMode(next);
  }

  const previewBet: Bet = useMemo(() => {
    const now = Date.now();
    return {
      id: "00000000-0000-4000-8000-000000000000",
      tableId: table.id,
      proposerId: user.id,
      ...(sports.previewTerms() ?? {
        modeId: "u2pick" as const,
        config: {},
        question: question.trim() || "Your question shows up here",
        choices: cleanOptions(options),
      }),
      wagerCents: wager,
      forFun,
      status: "ACTIVE",
      locksAt: new Date(now + timer * 1000).toISOString(),
      createdAt: new Date(now).toISOString(),
      pointsSettled: false,
      pickCount: 0,
      myPick: null,
      myParticipation: "not_joined",
      participantIds: [],
      viewerCanCall: false,
      viewerCanOverride: false,
      settledBy,
    };
    // Rebuilt whenever the preview opens, so its clock starts fresh.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [previewing, table.id, user.id, question, options, forFun, wager, timer, settledBy, sports]);

  function reset() {
    setMode(null);
    setQuestion("");
    setOptions(["Yes", "No"]);
    setSettledBy("hosts");
    setWager(WAGER_DEFAULT_CENTS);
    setTimer(PICK_WINDOW_DEFAULT);
    setPreviewing(false);
    setAbout(false);
    sports.reset();
    setRules(DEFAULT_RULES);
    setHoldemRules(DEFAULT_HOLDEM_RULES);
  }

  function submit() {
    if (!mode || problem || block || busy) return;
    if (mode.cardGame) {
      actions.start(
        mode.cardGame === "holdem"
          ? {
              kind: "propose-holdem",
              tableId: table.id,
              rules: holdemRules,
              wagerCents: wager,
              durationSec: timer,
              forFun,
            }
          : {
              kind: "propose-jackblack",
              tableId: table.id,
              rules,
              wagerCents: wager,
              durationSec: timer,
              forFun,
            },
      );
      onOpenChange(false);
      reset();
      return;
    }
    const sportsProposal = sports.proposal(wager, timer, forFun);
    if (sportsProposal) {
      actions.start({ kind: "propose-sports", tableId: table.id, proposal: sportsProposal });
      onOpenChange(false);
      reset();
      return;
    }
    actions.start({
      kind: "propose",
      tableId: table.id,
      proposal: {
        question: question.trim(),
        choices: cleanOptions(options),
        wagerCents: wager,
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
                onClick={() =>
                  about ? setAbout(false) : previewing ? setPreviewing(false) : setMode(null)
                }
                aria-label={about || previewing ? "Back to setup" : "Back to modes"}
              >
                <ArrowLeft />
              </Button>
            )}
            <SheetTitle className="font-display text-2xl font-extrabold uppercase">
              {previewing ? "Preview ticket" : mode ? mode.name : "Propose a bet"}
            </SheetTitle>
            {mode && !previewing && (
              <Button
                variant="ghost"
                size="icon"
                className={cn("size-8", about ? "text-primary" : "text-muted-foreground")}
                onClick={() => setAbout((open) => !open)}
                aria-label={`How ${mode.name} works`}
                aria-pressed={about}
              >
                <Info />
              </Button>
            )}
          </div>
          <SheetDescription className={cn(!mode && "sr-only")}>
            {mode ? mode.tagline : "Choose a mode to set up a bet."}
          </SheetDescription>
        </SheetHeader>

        <ScrollContainer
          key={previewing ? "preview" : about ? "about" : (mode?.id ?? "modes")}
          isVisible={false}
          className="min-h-0 flex-1 px-5 py-5"
        >
          {mode && about ? (
            <ModeInfo mode={mode} />
          ) : previewing ? (
            <div className="space-y-4">
              <BetTicket
                bet={previewBet}
                table={table}
                user={user}
                expire={expire}
                peopleKey={peopleKey}
                className="max-w-none"
              />
              {sports.sportsMode && sports.game && (
                <GameNow
                  game={sports.game}
                  mode={sports.sportsMode}
                  players={sports.picked}
                  stat={sports.playerMode ? sports.statKey : null}
                  statLabel={sports.statLabel}
                />
              )}
              <BetFacts
                bet={previewBet}
                table={table}
                user={user}
                expire={expire}
                peopleKey={peopleKey}
              />
            </div>
          ) : !mode ? (
            <ModeGrid
              table={table}
              userId={user.id}
              availability={availability}
              block={block}
              onPick={pickMode}
            />
          ) : (
            <div className="space-y-6">
              {mode.cardGame === "jackblack" ? (
                <JackblackSetup rules={rules} onChange={setRules} />
              ) : mode.cardGame === "holdem" ? (
                <HoldemSetup rules={holdemRules} onChange={setHoldemRules} />
              ) : sports.sportsMode ? (
                <SportsSetup draft={sports} />
              ) : (
                <U2PickEditor
                  question={question}
                  onQuestionChange={setQuestion}
                  options={options}
                  onOptionsChange={setOptions}
                  settledBy={settledBy}
                  onSettledByChange={setSettledBy}
                />
              )}

              <Section
                label={
                  mode.cardGame === "jackblack"
                    ? "Ante (points a hand)"
                    : mode.cardGame === "holdem"
                      ? "Big blind (points)"
                      : "Wager (points each)"
                }
              >
                <div className="space-y-3">
                  <ValueSlider
                    value={wager}
                    min={0}
                    max={WAGER_MAX_CENTS}
                    step={10}
                    format={(v) => formatCents(v)}
                    sliderLabel="Wager in points"
                    note={wager === 0 ? "(4fun)" : undefined}
                    onChange={setWager}
                  />
                  {mode.cardGame === "jackblack" && !forFun && (
                    <JackblackWorstCase rules={rules} anteCents={wager} />
                  )}
                  {mode.cardGame === "holdem" && !forFun && (
                    <HoldemWorstCase bigBlindCents={wager} />
                  )}
                </div>
              </Section>

              <Section label={mode.cardGame ? "Join window" : "Pick window"}>
                <SegmentedTabs<string>
                  size="sm"
                  ariaLabel={mode.cardGame ? "Join window" : "Pick window"}
                  tabs={PICK_WINDOWS.map((t) => ({ id: String(t.sec), label: t.label }))}
                  value={String(timer)}
                  onChange={(v) => setTimer(Number(v))}
                />
              </Section>
            </div>
          )}
        </ScrollContainer>

        {mode && about && (
          <div className="border-t bg-sidebar p-4">
            <Button variant="outline" size="lg" className="w-full" onClick={() => setAbout(false)}>
              <ArrowLeft /> Back to setup
            </Button>
          </div>
        )}
        {mode && !about && (
          <div className="space-y-2 border-t bg-sidebar p-4">
            {(problem ?? block) && (
              <p className="text-center text-xs text-muted-foreground">{problem ?? block}</p>
            )}
            <Button
              variant="hero"
              size="lg"
              className="w-full"
              onClick={() => (previewing || mode.cardGame ? submit() : setPreviewing(true))}
              disabled={!!problem || !!block || ((previewing || !!mode.cardGame) && busy)}
            >
              {mode.cardGame ? (
                <>
                  <Send /> Open the table
                </>
              ) : previewing ? (
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

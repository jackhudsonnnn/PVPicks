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
import { Section, Stepper } from "./form-parts";
import { U2PickEditor } from "./u2pick-editor";
import { type ModeDef, useModeAvailability } from "@/features/bets/modes";
import { ModeGrid } from "./mode-grid";
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
import type { Bet } from "@/features/bets/api";
import type { Table } from "@/features/tables";
import type { User } from "@/features/people";
import { BetFacts } from "./bet-details";
import type { BetActions } from "./bet-controls";
import { BetTicket } from "./ticket";
import { proposeBlock, u2pickBlock } from "@/features/bets/ticket-model";
import {
  canPush,
  DEFAULT_LINE,
  isScoreMode,
  LINE_LIMITS,
  proposableGames,
  scoreTerms,
  stepLine,
  type ScoreMode,
} from "@/features/bets/sports-model";
import { tickerStatus, type TickerGame, useTicker } from "@/features/sports";
import { LoadingList } from "@/components/loading";

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
}: {
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
  const [forFun, setForFun] = useState(false);
  const [wager, setWager] = useState(WAGER_DEFAULT_CENTS);
  const [timer, setTimer] = useState(PICK_WINDOW_DEFAULT);
  const [previewing, setPreviewing] = useState(false);
  const [gameId, setGameId] = useState<string | null>(null);
  const [league, setLeague] = useState<TickerGame["league"]>("NBA");
  const [line, setLine] = useState(0);
  const [overtime, setOvertime] = useState(true);
  const ticker = useTicker(user.id);
  const games = proposableGames(ticker.data);
  const game = games.find((g) => g.id === gameId);
  const scoreMode: ScoreMode | null = mode && isScoreMode(mode.id) ? mode.id : null;
  const block = proposeBlock(table, user.id);
  const availability = useModeAvailability();
  const busy = actions.busy || !!actions.pending || !!actions.storageError;
  const problem = !mode
    ? null
    : mode.custom
      ? (u2pickBlock(table) ?? customBetProblem(question, options))
      : !game
        ? "Pick a game."
        : null;

  function pickGame(next: TickerGame) {
    setGameId(next.id);
    // A new league gets that league's default line; the same league keeps yours.
    if (scoreMode && (!game || game.league !== next.league))
      setLine(DEFAULT_LINE[next.league][scoreMode]);
  }

  function pickMode(next: ModeDef) {
    setMode(next);
    if (isScoreMode(next.id)) {
      const first = games.find((g) => g.league === league) ?? games[0];
      setGameId(first?.id ?? null);
      if (first) setLeague(first.league);
      setLine(DEFAULT_LINE[first?.league ?? league][next.id]);
    }
  }

  const previewBet: Bet = useMemo(() => {
    const now = Date.now();
    return {
      id: "00000000-0000-4000-8000-000000000000",
      tableId: table.id,
      proposerId: user.id,
      ...(scoreMode && game
        ? {
            modeId: scoreMode,
            config: { line },
            gameId: game.id,
            includesOvertime: overtime,
            ...scoreTerms(scoreMode, line, game.home.abbreviation, game.away.abbreviation),
          }
        : {
            modeId: "u2pick" as const,
            config: {},
            question: question.trim() || "Your question shows up here",
            choices: cleanOptions(options),
          }),
      wagerCents: forFun ? 0 : wager,
      forFun,
      status: "ACTIVE",
      locksAt: new Date(now + timer * 1000).toISOString(),
      createdAt: new Date(now).toISOString(),
      pointsSettled: false,
      pickCount: 0,
      myPick: null,
      myParticipation: "not_joined",
      viewerCanCall: false,
      viewerCanOverride: false,
      settledBy,
    };
    // Rebuilt whenever the preview opens, so its clock starts fresh.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    previewing,
    table.id,
    user.id,
    question,
    options,
    forFun,
    wager,
    timer,
    settledBy,
    scoreMode,
    game,
    line,
    overtime,
  ]);

  function reset() {
    setMode(null);
    setQuestion("");
    setOptions(["Yes", "No"]);
    setSettledBy("hosts");
    setForFun(false);
    setWager(WAGER_DEFAULT_CENTS);
    setTimer(PICK_WINDOW_DEFAULT);
    setPreviewing(false);
    setGameId(null);
    setOvertime(true);
  }

  function submit() {
    if (!mode || problem || block || busy) return;
    if (scoreMode && game) {
      actions.start({
        kind: "propose-sports",
        tableId: table.id,
        proposal: {
          modeId: scoreMode,
          gameId: game.id,
          line,
          includesOvertime: overtime,
          wagerCents: forFun ? 0 : wager,
          durationSec: timer,
          forFun,
        },
      });
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
            <ModeGrid table={table} availability={availability} block={block} onPick={pickMode} />
          ) : (
            <div className="space-y-6">
              {scoreMode ? (
                <>
                  <Section label="Game">
                    <SegmentedTabs<TickerGame["league"]>
                      size="sm"
                      ariaLabel="League"
                      tabs={[
                        { id: "NBA", label: "NBA" },
                        { id: "NFL", label: "NFL" },
                      ]}
                      value={league}
                      onChange={setLeague}
                    />
                    {ticker.data === null ? (
                      <p className="text-sm text-muted-foreground">
                        Live scores are unavailable, so games can't be picked right now.
                      </p>
                    ) : games.filter((g) => g.league === league).length === 0 ? (
                      ticker.isPending ? (
                        <LoadingList label="Loading games" rows={3} rowClassName="h-12" />
                      ) : (
                        <p className="text-sm text-muted-foreground">
                          No {league} games left today.
                        </p>
                      )
                    ) : (
                      <div className="grid gap-2" role="radiogroup" aria-label="Game">
                        {games
                          .filter((g) => g.league === league)
                          .map((g) => (
                            <button
                              key={g.id}
                              type="button"
                              role="radio"
                              aria-checked={g.id === gameId}
                              onClick={() => pickGame(g)}
                              className={cn(
                                "flex items-center justify-between rounded-lg border px-3 py-2.5 text-sm transition-colors",
                                g.id === gameId
                                  ? "border-primary bg-primary/10"
                                  : "bg-surface hover:border-muted-foreground",
                              )}
                            >
                              <span className="scoreboard text-lg">
                                {g.away.abbreviation} {g.status === "LIVE" && g.away.score}{" "}
                                <span className="text-muted-foreground">@</span>{" "}
                                {g.home.abbreviation} {g.status === "LIVE" && g.home.score}
                              </span>
                              <span className="flex items-center gap-2 font-mono text-[11px] text-muted-foreground">
                                {g.status === "LIVE" && (
                                  <span className="size-1.5 animate-pulse-dot rounded-full bg-loss" />
                                )}
                                {g.dev && (
                                  <span className="rounded bg-status-pending/20 px-1 font-bold text-status-pending">
                                    DEV
                                  </span>
                                )}
                                {tickerStatus(g)}
                              </span>
                            </button>
                          ))}
                      </div>
                    )}
                  </Section>
                  <Section label="Setup">
                    <div className="grid gap-1.5 text-xs font-medium text-muted-foreground">
                      {scoreMode === "total_disaster"
                        ? "Total points line"
                        : `${game?.home.abbreviation ?? "Home"} spread`}
                      <Stepper
                        value={line}
                        step={0.5}
                        min={LINE_LIMITS[scoreMode].min}
                        max={LINE_LIMITS[scoreMode].max}
                        label={
                          scoreMode === "total_disaster" ? "Total points line" : "Home team spread"
                        }
                        format={(v) =>
                          scoreMode === "give_and_take" && v > 0 ? `+${v}` : String(v)
                        }
                        onChange={(v) => setLine(stepLine(scoreMode, v, 0))}
                      />
                      {canPush(line) && (
                        <span className="font-normal">
                          A whole-number line can land exactly on the score; that washes the bet.
                        </span>
                      )}
                    </div>
                    <div className="mt-3 flex items-center justify-between gap-4 rounded-xl border bg-surface p-3">
                      <span className="min-w-0">
                        <span className="block text-sm font-semibold">Overtime counts</span>
                        <span className="block text-xs text-muted-foreground">
                          {overtime
                            ? "Points scored in overtime count."
                            : "Decided at the end of regulation."}
                        </span>
                      </span>
                      <Switch
                        checked={overtime}
                        onCheckedChange={setOvertime}
                        aria-label="Overtime counts"
                      />
                    </div>
                  </Section>
                </>
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

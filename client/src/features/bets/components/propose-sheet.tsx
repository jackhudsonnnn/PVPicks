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
import { Switch } from "@/components/ui/switch";
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
import {
  DEFAULT_LINE,
  isPlayerMode,
  isScoreMode,
  isSportsMode,
  LINE_LIMITS,
  playerProblem,
  playerTerms,
  proposableGames,
  scoreTerms,
  suggestedLine,
  stepLine,
  type PlayerMode,
  type ScoreMode,
  type SportsMode,
} from "@/features/bets/sports-model";
import { tickerStatus, type TickerGame, useGamePlayers, useTicker } from "@/features/sports";
import { PlayerSetup } from "./player-setup";
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
  const [gameId, setGameId] = useState<string | null>(null);
  const [league, setLeague] = useState<TickerGame["league"]>("NBA");
  const [line, setLine] = useState(0);
  const [overtime, setOvertime] = useState(true);
  const [stat, setStat] = useState<string | null>(null);
  const [playerIds, setPlayerIds] = useState<string[]>([]);
  const [rules, setRules] = useState<JackblackRules>(DEFAULT_RULES);
  const [holdemRules, setHoldemRules] = useState<HoldemRules>(DEFAULT_HOLDEM_RULES);
  const ticker = useTicker(user.id);
  const sportsMode: SportsMode | null = mode && isSportsMode(mode.id) ? mode.id : null;
  const scoreMode: ScoreMode | null = mode && isScoreMode(mode.id) ? mode.id : null;
  const playerMode: PlayerMode | null = mode && isPlayerMode(mode.id) ? mode.id : null;
  const leagues: TickerGame["league"][] = ["NBA", "NFL"];
  const games = proposableGames(ticker.data).filter((g) => leagues.includes(g.league));
  const game = games.find((g) => g.id === gameId);
  // A resent bet's game that can't take new bets any more (it's over or postponed).
  const lostGame = gameId !== null && ticker.data !== undefined && !game;

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
    if (isSportsMode(def.id) && from.gameId) {
      setGameId(from.gameId);
      setOvertime(from.includesOvertime ?? true);
      const config = from.config;
      setStat(typeof config["stat"] === "string" ? config["stat"] : null);
      setPlayerIds(Array.isArray(config["players"]) ? config["players"] : []);
      if (config["line"] !== undefined) setLine(Number(config["line"]));
    }
    // Only when a new bet is handed over.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, from?.id]);
  useEffect(() => {
    if (from?.gameId && game && game.id === from.gameId) setLeague(game.league);
  }, [from?.gameId, game]);
  // A live game's total line starts above the combined score (the server refuses one it has already passed) and
  // follows the score up while you set it.
  const totalFloor =
    scoreMode === "total_disaster" && game?.status === "LIVE"
      ? (game.away.score ?? 0) + (game.home.score ?? 0) + 0.5
      : null;
  useEffect(() => {
    if (totalFloor !== null && line < totalFloor) setLine(totalFloor);
  }, [totalFloor, line]);
  const roster = useGamePlayers(user.id, playerMode ? (game?.id ?? null) : null);
  const statKey = stat ?? roster.data?.stats[0]?.key ?? null;
  const picked = playerIds
    .map((id) => roster.data?.players.find((p) => p.id === id))
    .filter((p) => p !== undefined);
  const pickedStats = picked.map((p) => ({ stat: statKey ? (p.stats[statKey] ?? 0) : 0 }));
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
          : !game
            ? "Pick a game."
            : playerMode
              ? playerProblem(playerMode, pickedStats, line)
              : null;

  function pickGame(next: TickerGame) {
    setGameId(next.id);
    // A new league gets that league's default line; the same league keeps yours.
    if (scoreMode && (!game || game.league !== next.league))
      setLine(DEFAULT_LINE[next.league][scoreMode]);
    // Players and stats belong to a game, so a new game starts the player setup over.
    if (playerMode && next.id !== gameId) {
      setPlayerIds([]);
      setStat(null);
    }
  }

  /** A new league selects its first open game (or none), which starts the game's setup over like any new game. */
  function pickLeague(next: TickerGame["league"]) {
    setLeague(next);
    if (game?.league === next) return;
    const first = games.find((g) => g.league === next);
    if (first) pickGame(first);
    else {
      setGameId(null);
      setPlayerIds([]);
      setStat(null);
    }
  }

  function pickMode(next: ModeDef) {
    setMode(next);
    setAbout(false);
    setPlayerIds([]);
    setStat(null);
    if (isSportsMode(next.id)) {
      const allowed = ["NBA", "NFL"];
      const open = proposableGames(ticker.data).filter((g) => allowed.includes(g.league));
      const startLeague = allowed.includes(league) ? league : "NFL";
      const first = open.find((g) => g.league === startLeague) ?? open[0];
      setGameId(first?.id ?? null);
      setLeague(first?.league ?? startLeague);
      if (isScoreMode(next.id)) setLine(DEFAULT_LINE[first?.league ?? startLeague][next.id]);
    }
  }

  /**
   * New players or a new stat: start the line or target at their season average (before kickoff, when everyone is on
   * 0), and never at or below what they've already done.
   */
  function choosePlayers(ids: string[], nextStat = statKey) {
    setPlayerIds(ids);
    if (!playerMode || playerMode === "either_or" || !nextStat || ids.length === 0) return;
    const chosen = ids.flatMap((id) => roster.data?.players.find((p) => p.id === id) ?? []);
    setLine(
      suggestedLine(
        playerMode,
        chosen.map((p) => ({ current: p.stats[nextStat] ?? 0, average: p.averages?.[nextStat] })),
      ),
    );
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
        : sportsMode && game
          ? {
              modeId: sportsMode,
              config: {},
              gameId: game.id,
              ...(sportsMode === "scorcerer" ? {} : { includesOvertime: overtime }),
              ...playerTerms(
                sportsMode as PlayerMode | "scorcerer",
                roster.data?.stats.find((s) => s.key === statKey)?.label ?? "",
                picked,
                line,
                game.home.abbreviation,
                game.away.abbreviation,
                game.league,
              ),
            }
          : {
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
    sportsMode,
    game,
    line,
    overtime,
    statKey,
    playerIds,
  ]);

  function reset() {
    setMode(null);
    setQuestion("");
    setOptions(["Yes", "No"]);
    setSettledBy("hosts");
    setWager(WAGER_DEFAULT_CENTS);
    setTimer(PICK_WINDOW_DEFAULT);
    setPreviewing(false);
    setAbout(false);
    setGameId(null);
    setOvertime(true);
    setStat(null);
    setPlayerIds([]);
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
    if (sportsMode && game) {
      actions.start({
        kind: "propose-sports",
        tableId: table.id,
        proposal: {
          modeId: sportsMode,
          gameId: game.id,
          line:
            sportsMode === "either_or" || sportsMode === "scorcerer"
              ? null
              : sportsMode === "king_of_the_hill"
                ? Math.round(line)
                : line,
          stat: playerMode ? statKey : null,
          players: playerMode ? playerIds : [],
          includesOvertime: overtime,
          wagerCents: wager,
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
              {sportsMode && game && (
                <GameNow
                  game={game}
                  mode={sportsMode}
                  players={playerIds.flatMap(
                    (id) => roster.data?.players.find((p) => p.id === id) ?? [],
                  )}
                  stat={playerMode ? statKey : null}
                  statLabel={roster.data?.stats.find((s) => s.key === statKey)?.label ?? ""}
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
              ) : sportsMode ? (
                <>
                  <Section label="Game">
                    <SegmentedTabs<TickerGame["league"]>
                      size="sm"
                      ariaLabel="League"
                      tabs={leagues.map((l) => ({ id: l, label: l }))}
                      value={league}
                      onChange={pickLeague}
                    />
                    {lostGame && (
                      <p className="text-sm text-loss">
                        That game can't take new bets any more (it's over or postponed). Pick
                        another.
                      </p>
                    )}
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
                  {playerMode && game && (
                    <PlayerSetup
                      mode={playerMode}
                      roster={roster.data}
                      stat={statKey}
                      onStat={(next) => {
                        setStat(next);
                        choosePlayers(playerIds, next);
                      }}
                      playerIds={playerIds}
                      onPlayers={(ids) => choosePlayers(ids)}
                      line={line}
                      onLine={setLine}
                    />
                  )}
                  {scoreMode && (
                    <Section label="Setup">
                      <div className="grid gap-1.5 text-xs font-medium text-muted-foreground">
                        {scoreMode === "total_disaster"
                          ? "Total points line"
                          : `${game?.home.abbreviation ?? "Home"} spread`}
                        <ValueSlider
                          value={line}
                          // Lines end in .5 (never a push), so they move a whole point at a time.
                          step={1}
                          min={
                            scoreMode === "total_disaster" && totalFloor !== null
                              ? totalFloor
                              : LINE_LIMITS[scoreMode].min
                          }
                          max={LINE_LIMITS[scoreMode].max}
                          label={
                            scoreMode === "total_disaster"
                              ? "Total points line"
                              : "Home team spread"
                          }
                          format={(v) =>
                            scoreMode === "give_and_take" && v > 0 ? `+${v}` : String(v)
                          }
                          // Totals run into the hundreds; 399.5 covers any game. Spreads stay under 100.
                          sliderMax={scoreMode === "total_disaster" ? 399.5 : undefined}
                          onChange={(v) =>
                            setLine(Math.max(totalFloor ?? -Infinity, stepLine(scoreMode, v, 0)))
                          }
                        />
                        {totalFloor !== null && (
                          <span className="font-normal">
                            Now: {totalFloor - 0.5} combined. The line starts above that.
                          </span>
                        )}
                      </div>
                    </Section>
                  )}
                  {(scoreMode || (playerMode && game)) && (
                    <div className="flex items-center justify-between gap-4 rounded-xl border bg-surface p-3">
                      <span className="min-w-0">
                        <span className="block text-sm font-semibold">Overtime counts</span>
                        <span className="block text-xs text-muted-foreground">
                          {overtime
                            ? scoreMode
                              ? "Points scored in overtime count."
                              : "Stats in overtime count."
                            : "Decided at the end of regulation."}
                        </span>
                      </span>
                      <Switch
                        checked={overtime}
                        onCheckedChange={setOvertime}
                        aria-label="Overtime counts"
                      />
                    </div>
                  )}
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

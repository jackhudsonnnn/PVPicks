import { useMemo, useState } from "react";
import { ArrowLeft, Eye, Send } from "lucide-react";

import { SegmentedTabs } from "@/components/segmented-tabs";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useAppStore, useMe } from "@/features/store";
import { MODES, allPlayers, modeProblem, statsFor, type ModeDef } from "../modes";
import { Switch } from "@/components/ui/switch";
import { OVERTIME_DEFAULT, hasOvertimeOption, overtimeFor } from "../overtime";
import { makeBaseline } from "../info";
import { sampleSnapshot } from "../live-stats";
import { BetFacts, BetInfoSection } from "./bet-facts";
import { LEAGUES, SPORTS } from "../sports";
import { cleanOptions, customBetProblem } from "../u2pick";
import { Section, Stepper } from "./form-parts";
import { U2PickEditor } from "./u2pick-editor";
import { BetTicket } from "./bet-ticket";
import { PASS, type Bet, type BetConfig, type Game, type League, type SettlerRule } from "../types";
import { canProposeMode } from "@/features/table/hosts";
import { ScrollContainer } from "@/components/scroll-container";
import { formatPts } from "../ledger";
import { WAGER_DEFAULT, WAGER_MAX, WAGER_MIN, WAGER_STEPS, canStep, stepWager } from "../wager";

const TIMERS = [
  { label: "30s", sec: 30 },
  { label: "1m", sec: 60 },
  { label: "2m", sec: 120 },
  { label: "5m", sec: 300 },
  { label: "10m", sec: 600 },
];

export function ProposeBetSheet({
  tableId,
  open,
  onOpenChange,
}: {
  tableId: string;
  open: boolean;
  onOpenChange: (o: boolean) => void;
}) {
  const me = useMe();
  const { state, actions } = useAppStore();
  const table = state.tables.find((t) => t.id === tableId);
  const [mode, setMode] = useState<ModeDef | null>(null);
  const [game, setGame] = useState<Game>(state.games[0]!);
  const [config, setConfig] = useState<BetConfig>({});
  const [wager, setWager] = useState<number>(WAGER_DEFAULT);
  const [timer, setTimer] = useState(120);
  const [settledBy, setSettledBy] = useState<SettlerRule>("hosts");
  const [forFun, setForFun] = useState(false);
  const [overtimeOn, setOvertimeOn] = useState(OVERTIME_DEFAULT);
  const [previewing, setPreviewing] = useState(false);
  const overtime = mode ? overtimeFor(mode.id, overtimeOn) : undefined;

  const custom = !!mode?.custom;
  const baseQuestion = mode ? mode.question(config, game) : "";
  const rawOptions = Array.isArray(config["options"]) ? config["options"] : [];
  const choices = useMemo(
    () =>
      mode
        ? mode.custom
          ? cleanOptions(mode.choices(config, game))
          : mode.choices(config, game)
        : [],
    [mode, config, game],
  );
  const question = baseQuestion;
  const previewBet: Bet | null = useMemo(
    () =>
      mode
        ? {
            id: "preview",
            tableId,
            proposerId: me,
            modeId: mode.id,
            gameId: custom ? undefined : game.id,
            config,
            question: question || "Your question shows up here",
            choices,
            wager: forFun ? 0 : wager,
            durationSec: timer,
            closesInSec: timer,
            status: "active",
            participants: [{ userId: me, selection: PASS }],
            forFun: forFun || undefined,
            overtime,
            settledBy: custom ? settledBy : undefined,
            createdAt: "just now",
            baseline: custom
              ? undefined
              : makeBaseline({ modeId: mode.id, config }, sampleSnapshot(game), "just now"),
          }
        : null,
    [
      mode,
      tableId,
      me,
      custom,
      game,
      config,
      question,
      choices,
      forFun,
      wager,
      timer,
      overtime,
      settledBy,
    ],
  );
  const problem = !mode
    ? null
    : custom
      ? customBetProblem(baseQuestion, rawOptions)
      : modeProblem(mode.id, config);
  const league = game.league;

  function pickMode(m: ModeDef) {
    setMode(m);
    setConfig(m.defaults(game));
  }
  function pickGame(g: Game) {
    setGame(g);
    if (mode) setConfig(mode.defaults(g));
  }
  function pickLeague(l: League) {
    const first = state.games.find((g) => g.league === l);
    if (first) pickGame(first);
  }
  function setOptions(options: string[]) {
    setConfig({ ...config, options });
  }
  function reset() {
    setMode(null);
    setWager(WAGER_DEFAULT);
    setTimer(120);
    setSettledBy("hosts");
    setForFun(false);
    setOvertimeOn(OVERTIME_DEFAULT);
    setPreviewing(false);
  }
  function submit() {
    if (!mode || problem) return;
    const id = actions.propose({
      tableId,
      modeId: mode.id,
      gameId: custom ? undefined : game.id,
      settledBy: custom ? settledBy : undefined,
      config: custom ? { question: baseQuestion, options: choices } : config,
      question,
      choices,
      wager: forFun ? 0 : wager,
      forFun: forFun || undefined,
      overtime,
      durationSec: timer,
    });
    if (!id) return;

    onOpenChange(false);
    reset();
  }

  return (
    <Sheet
      open={open}
      onOpenChange={(o) => {
        onOpenChange(o);
        if (!o) reset();
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
                aria-label={previewing ? "Back to configuration" : "Back to modes"}
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
          {previewing && previewBet ? (
            <div className="space-y-4">
              <BetTicket bet={previewBet} preview className="max-w-none" />
              <BetFacts bet={previewBet} onNavigate={() => onOpenChange(false)} />
              <BetInfoSection bet={previewBet} />
            </div>
          ) : !mode ? (
            <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
              {MODES.map((m) => {
                const Icon = m.icon;
                const off = !!table && !canProposeMode(table, me, m.id);
                return (
                  <Button
                    variant="outline"
                    key={m.id}
                    disabled={off}
                    onClick={() => pickMode(m)}
                    className="group flex h-auto flex-col items-start gap-3 whitespace-normal rounded-xl border bg-surface p-4 text-left transition-all enabled:hover:border-primary enabled:hover:bg-surface-raised disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    <span className="flex w-full items-center justify-between gap-2">
                      <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/15 text-primary transition-colors group-hover:bg-primary group-hover:text-primary-foreground">
                        <Icon className="size-5" />
                      </span>
                      {!m.custom && (
                        <span className="flex flex-wrap justify-end gap-1">
                          {LEAGUES.map((l) => (
                            <span
                              key={l}
                              className={cn(
                                "rounded-full border px-2 py-0.5 font-mono text-[10px] font-bold",
                                l === "nfl"
                                  ? "border-primary/30 bg-primary/10 text-primary"
                                  : "border-status-pending/30 bg-status-pending/10 text-status-pending",
                              )}
                            >
                              {SPORTS[l].label}
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
                        {off ? "Turned off by a host" : m.tagline}
                      </span>
                    </span>
                  </Button>
                );
              })}
            </div>
          ) : (
            <div className="space-y-6">
              {custom ? (
                <U2PickEditor
                  question={String(config["question"] ?? "")}
                  onQuestionChange={(q) => setConfig({ ...config, question: q })}
                  options={rawOptions}
                  onOptionsChange={setOptions}
                  settledBy={settledBy}
                  onSettledByChange={setSettledBy}
                />
              ) : (
                <Section label="Game">
                  <SegmentedTabs
                    size="sm"
                    ariaLabel="League"
                    tabs={LEAGUES.map((l) => ({ id: l, label: SPORTS[l].label }))}
                    value={league}
                    onChange={pickLeague}
                  />
                  <div className="grid gap-2">
                    {state.games
                      .filter((g) => g.league === league)
                      .map((g) => (
                        <button
                          key={g.id}
                          onClick={() => pickGame(g)}
                          className={cn(
                            "flex items-center justify-between rounded-lg border px-3 py-2.5 text-sm transition-colors",
                            g.id === game.id
                              ? "border-primary bg-primary/10"
                              : "bg-surface hover:border-muted-foreground",
                          )}
                        >
                          <span className="scoreboard text-lg">
                            {g.away.abbr} {g.live && g.awayScore}{" "}
                            <span className="text-muted-foreground">@</span> {g.home.abbr}{" "}
                            {g.live && g.homeScore}
                          </span>
                          <span className="flex items-center gap-2 font-mono text-[11px] text-muted-foreground">
                            {g.live && (
                              <span className="size-1.5 animate-pulse-dot rounded-full bg-loss" />
                            )}
                            {g.clock}
                          </span>
                        </button>
                      ))}
                  </div>
                </Section>
              )}

              {(mode.fields.length > 0 || hasOvertimeOption(mode.id)) && (
                <Section label="Setup">
                  {mode.fields.length > 0 && (
                    <div className="grid gap-3 sm:grid-cols-2">
                      {mode.fields.map((f) => (
                        <div
                          key={f.key}
                          className="grid gap-1.5 text-xs font-medium text-muted-foreground"
                        >
                          {f.label}
                          {f.type === "player" && (
                            <Select
                              value={String(config[f.key])}
                              onValueChange={(v) => setConfig({ ...config, [f.key]: v })}
                            >
                              <SelectTrigger className="bg-surface text-foreground">
                                <SelectValue />
                              </SelectTrigger>
                              <SelectContent>
                                {allPlayers(game).map((p) => (
                                  <SelectItem key={p.id} value={p.id}>
                                    {p.name} · {p.pos}
                                  </SelectItem>
                                ))}
                              </SelectContent>
                            </Select>
                          )}
                          {f.type === "stat" && (
                            <Select
                              value={String(config[f.key])}
                              onValueChange={(v) => setConfig({ ...config, [f.key]: v })}
                            >
                              <SelectTrigger className="bg-surface text-foreground">
                                <SelectValue />
                              </SelectTrigger>
                              <SelectContent>
                                {statsFor(game).map((s) => (
                                  <SelectItem key={s} value={s}>
                                    {s}
                                  </SelectItem>
                                ))}
                              </SelectContent>
                            </Select>
                          )}
                          {f.type === "team" && (
                            <SegmentedTabs<"away" | "home">
                              size="sm"
                              ariaLabel={f.label}
                              tabs={(["away", "home"] as const).map((side) => ({
                                id: side,
                                label: game[side].abbr,
                              }))}
                              value={config[f.key] === "home" ? "home" : "away"}
                              onChange={(side) => setConfig({ ...config, [f.key]: side })}
                            />
                          )}
                          {f.type === "number" && (
                            <Stepper
                              value={Number(config[f.key])}
                              step={f.step}
                              min={f.min}
                              onChange={(v) => setConfig({ ...config, [f.key]: v })}
                            />
                          )}
                        </div>
                      ))}
                    </div>
                  )}
                  {hasOvertimeOption(mode.id) && (
                    <div className="flex items-center justify-between gap-4 rounded-xl border bg-surface p-3 mt-3">
                      <span className="min-w-0">
                        <span className="block text-sm font-semibold">Overtime counts</span>
                        <span className="block text-xs text-muted-foreground">
                          {overtimeOn
                            ? "Stats and scores in overtime count."
                            : "Decided at the end of regulation."}
                        </span>
                      </span>
                      <Switch
                        checked={overtimeOn}
                        onCheckedChange={setOvertimeOn}
                        aria-label="Overtime counts"
                      />
                    </div>
                  )}
                </Section>
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
                      >
                        {formatPts(wager)}
                      </span>
                      <button
                        type="button"
                        onClick={() => setWager(WAGER_MIN)}
                        disabled={forFun || wager === WAGER_MIN}
                        className="rounded-md px-2 py-1 font-mono text-[10px] font-bold uppercase tracking-wider text-muted-foreground transition-colors hover:text-foreground disabled:opacity-40"
                      >
                        Reset to {formatPts(WAGER_MIN)}
                      </button>
                    </div>
                    <div className="grid grid-cols-3 gap-2">
                      {WAGER_STEPS.map((step) => (
                        <button
                          key={step}
                          type="button"
                          onClick={() => setWager((w) => stepWager(w, step))}
                          disabled={forFun || !canStep(wager, step)}
                          aria-label={`Add ${formatPts(step)} points`}
                          className="rounded-lg border bg-background py-2 scoreboard text-base transition-colors hover:border-primary disabled:opacity-40 disabled:hover:border-border"
                        >
                          +{formatPts(step)}
                        </button>
                      ))}
                    </div>
                    <p className="font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
                      Min {formatPts(WAGER_MIN)} · Max {formatPts(WAGER_MAX)}
                    </p>
                  </div>
                </div>
              </Section>

              <Section label="Pick window">
                <SegmentedTabs<string>
                  size="sm"
                  ariaLabel="Pick window"
                  tabs={TIMERS.map((t) => ({ id: String(t.sec), label: t.label }))}
                  value={String(timer)}
                  onChange={(v) => setTimer(Number(v))}
                />
              </Section>
            </div>
          )}
        </ScrollContainer>

        {mode && (
          <div className="space-y-2 border-t bg-sidebar p-4">
            {problem && <p className="text-center text-xs text-muted-foreground">{problem}</p>}
            <Button
              variant="hero"
              size="lg"
              className="w-full"
              onClick={() => (previewing ? submit() : setPreviewing(true))}
              disabled={!!problem}
            >
              {previewing ? (
                <>
                  <Send /> Send · {forFun ? "4fun" : `${formatPts(wager)} pts`}
                </>
              ) : (
                <>
                  <Eye /> Preview Ticket
                </>
              )}
            </Button>
          </div>
        )}
      </SheetContent>
    </Sheet>
  );
}

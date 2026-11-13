import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { U2PickEditor } from "@/features/bets/components/u2pick-editor";
import { cleanOptions, customBetProblem, settlerRule } from "@/features/bets/u2pick";
import type { SettlerRule } from "@/features/bets/types";
import type { BackendBet, BackendTable, BackendUser } from "./backend-api";
import { ConfirmAction } from "./backend-confirm-action";
import { useBetActions } from "./bet-actions";
import { awaitingPointsSettlement, canCallResult } from "./bet-results";

export type BetActions = ReturnType<typeof useBetActions>;

export function BetMutationStatus({ actions }: { actions: BetActions }) {
  return (
    <div className="space-y-2">
      {actions.storageError && <p role="alert">{actions.storageError} Actions are disabled.</p>}
      {actions.message && <p role="alert">{actions.message}</p>}
      {actions.pending && (
        <div className="space-y-2 rounded-lg border bg-surface p-3">
          <p>
            A bet action has an unknown outcome for table {actions.pending.intent.tableId}. Retry
            the same action before making other changes. It may have been started in another tab.
          </p>
          <Button
            size="sm"
            disabled={actions.busy || !!actions.storageError}
            onClick={actions.retry}
          >
            Retry same action
          </Button>{" "}
          <ConfirmAction
            label="Clear saved action"
            title="Clear the saved action?"
            description="Check the original table's bet list and your pick first. The action may have succeeded."
            disabled={actions.busy || !!actions.storageError}
            onConfirm={actions.clearAfterReview}
          />
        </div>
      )}
    </div>
  );
}

export function ProposalForm({
  table,
  user,
  actions,
}: {
  table: BackendTable;
  user: BackendUser;
  actions: BetActions;
}) {
  const [question, setQuestion] = useState("");
  const [choices, setChoices] = useState(["", ""]);
  const [settledBy, setSettledBy] = useState<SettlerRule>("hosts");
  const [forFun, setForFun] = useState(false);
  const [wager, setWager] = useState("1.00");
  const [duration, setDuration] = useState("60");
  const [problem, setProblem] = useState<string | null>(null);
  const canBet = !table.disabledPermissions[user.id]?.includes("bets");
  const canPropose =
    canBet && table.u2pickEnabled && (!table.hostsOnlyBets || table.hostIds.includes(user.id));
  const disabled = !canPropose || actions.busy || !!actions.pending || !!actions.storageError;

  function submit(event: React.FormEvent) {
    event.preventDefault();
    const invalid = customBetProblem(question, choices);
    if (invalid) {
      setProblem(invalid);
      return;
    }
    const durationSec = Number(duration);
    if (!/^\d+$/.test(duration) || durationSec < 30 || durationSec > 600) {
      setProblem("Choose a pick window from 30 to 600 seconds.");
      return;
    }
    if (
      !forFun &&
      (!/^\d+(?:\.\d{1,2})?$/.test(wager) || Number(wager) < 0.1 || Number(wager) > 10)
    ) {
      setProblem("Choose a wager from 0.10 to 10.00 points, with at most two decimal places.");
      return;
    }
    setProblem(null);
    actions.start({
      kind: "propose",
      tableId: table.id,
      proposal: {
        question: question.trim(),
        choices: cleanOptions(choices),
        wagerCents: forFun ? 0 : Math.round(Number(wager) * 100),
        durationSec,
        forFun,
        settledBy,
      },
    });
  }

  return (
    <details className="rounded-lg border bg-surface p-4">
      <summary className="cursor-pointer font-semibold">Create a U2Pick</summary>
      {!canPropose && (
        <p>You cannot create a U2Pick with the current table permissions and settings.</p>
      )}
      <form className="mt-4 space-y-4" onSubmit={submit}>
        <fieldset disabled={disabled} className="space-y-4">
          <U2PickEditor
            question={question}
            onQuestionChange={setQuestion}
            options={choices}
            onOptionsChange={setChoices}
            settledBy={settledBy}
            onSettledByChange={setSettledBy}
          />
          <div className="flex items-center gap-2">
            <Switch
              id="u2pick-for-fun"
              checked={forFun}
              onCheckedChange={setForFun}
              disabled={disabled}
            />
            <Label htmlFor="u2pick-for-fun">For fun (no tracked wager)</Label>
          </div>
          {!forFun && (
            <div className="space-y-1">
              <Label htmlFor="u2pick-wager">Wager in points</Label>
              <Input
                id="u2pick-wager"
                inputMode="decimal"
                value={wager}
                onChange={(e) => setWager(e.target.value)}
              />
            </div>
          )}
          <div className="space-y-1">
            <Label htmlFor="u2pick-duration">Pick window in seconds (30–600)</Label>
            <Input
              id="u2pick-duration"
              inputMode="numeric"
              value={duration}
              onChange={(e) => setDuration(e.target.value)}
            />
          </div>
          <p className="text-sm text-muted-foreground">
            Creating a bet does not automatically place your pick.
          </p>
          {problem && <p role="alert">{problem}</p>}
          <Button type="submit" disabled={disabled}>
            Create bet
          </Button>
        </fieldset>
      </form>
    </details>
  );
}

export function PickControls({
  bet,
  tableId,
  actions,
  canBet,
}: {
  bet: BackendBet;
  tableId: string;
  actions: BetActions;
  canBet: boolean;
}) {
  const [choice, setChoice] = useState(bet.myPick ?? bet.choices[0] ?? "");
  useEffect(() => setChoice(bet.myPick ?? bet.choices[0] ?? ""), [bet.id, bet.myPick, bet.choices]);
  if (bet.modeId !== "u2pick" || bet.status !== "ACTIVE") return null;
  const disabled = !canBet || actions.busy || !!actions.pending || !!actions.storageError;
  return (
    <div className="space-y-2">
      {!canBet && <p>You do not have permission to pick at this table.</p>}
      <SegmentedTabs
        size="sm"
        ariaLabel="Your choice"
        tabs={bet.choices.map((option) => ({ id: option, label: option, disabled }))}
        value={choice}
        onChange={setChoice}
      />
      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          disabled={disabled || !choice || choice === bet.myPick}
          onClick={() => actions.start({ kind: "pick", tableId, betId: bet.id, choice })}
        >
          Save pick
        </Button>
        {bet.myPick && (
          <Button
            size="sm"
            variant="outline"
            disabled={disabled}
            onClick={() => actions.start({ kind: "clear", tableId, betId: bet.id })}
          >
            Clear my pick
          </Button>
        )}
      </div>
      <p className="text-muted-foreground">Clearing a joined pick leaves you as No guess.</p>
    </div>
  );
}

const wash = "wash";

export function ResultControls({
  bet,
  table,
  user,
  actions,
}: {
  bet: BackendBet;
  table: BackendTable;
  user: BackendUser;
  actions: BetActions;
}) {
  const [selected, setSelected] = useState("0");
  if (bet.modeId !== "u2pick" || bet.status !== "PENDING") return null;
  const rule = settlerRule(bet.settledBy ?? "hosts");
  if (!canCallResult(bet, table, user.id))
    return <p className="text-muted-foreground">Waiting for the result. {rule.description}</p>;
  const disabled = actions.busy || !!actions.pending || !!actions.storageError;
  const winningChoice = selected === wash ? null : (bet.choices[Number(selected)] ?? null);
  return (
    <div className="space-y-2">
      <p className="font-semibold">Call the result</p>
      <SegmentedTabs
        size="sm"
        ariaLabel="Result"
        tabs={[
          ...bet.choices.map((option, index) => ({ id: String(index), label: option, disabled })),
          { id: wash, label: "Wash", disabled },
        ]}
        value={selected}
        onChange={setSelected}
      />
      <ConfirmAction
        label="Call result"
        title={winningChoice === null ? "Wash this bet?" : `Call “${winningChoice}” as the result?`}
        description={
          winningChoice === null
            ? "Nobody wins or loses points. This can't be undone."
            : "Points move between players who picked. This can't be undone."
        }
        disabled={disabled}
        onConfirm={() =>
          actions.start({ kind: "resolve", tableId: table.id, betId: bet.id, winningChoice })
        }
      />
    </div>
  );
}

export function PointsSettlementControl({
  table,
  user,
  bets,
  actions,
}: {
  table: BackendTable;
  user: BackendUser;
  bets: BackendBet[];
  actions: BetActions;
}) {
  const waiting = awaitingPointsSettlement(bets);
  if (!table.hostIds.includes(user.id) || waiting === 0) return null;
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border bg-surface p-4">
      <p>
        {waiting} resolved {waiting === 1 ? "bet is" : "bets are"} waiting for points settlement.
      </p>
      <ConfirmAction
        label="Settle table points"
        title="Settle this table's points?"
        description="Every resolved bet at this table is settled together, and players get receipts for what they owe."
        disabled={actions.busy || !!actions.pending || !!actions.storageError}
        onConfirm={() => actions.start({ kind: "settle-points", tableId: table.id })}
      />
    </div>
  );
}

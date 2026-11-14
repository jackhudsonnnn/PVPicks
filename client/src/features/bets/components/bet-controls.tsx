import { Button } from "@/components/ui/button";
import type { BackendBet, BackendTable, BackendUser } from "./backend-api";
import { ConfirmAction } from "./backend-confirm-action";
import type { useBetActions } from "./bet-actions";
import { awaitingPointsSettlement } from "./bet-results";

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

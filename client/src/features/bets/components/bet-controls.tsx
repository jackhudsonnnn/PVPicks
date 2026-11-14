import { Button } from "@/components/ui/button";
import { ConfirmAction } from "@/components/confirm-action";
import type { useBetActions } from "@/features/bets/actions";

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

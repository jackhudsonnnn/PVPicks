import { Button } from "@/components/ui/button";
import { ConfirmAction } from "./confirm-action";

type Actions = {
  message: string | null;
  storageError: string | null;
  pending: unknown;
  busy: boolean;
  retry: () => void;
  clearAfterReview: () => void;
};

/**
 * The outcome of the last action, and, when its result is unknown, a way to retry it with the same key or clear
 * it after checking. While an action is unresolved, others of the same kind stay blocked. Every action area uses
 * this one box, so retries look and read the same everywhere.
 */
export function ActionStatus({
  actions,
  pendingText,
  checkText,
}: {
  actions: Actions;
  /** Shown while an action's outcome is unknown, e.g. "A table change may not have been saved." */
  pendingText: string;
  /** What to check before clearing, e.g. "Check your tables first." */
  checkText: string;
}) {
  // While this tab is sending the action, its saved copy is just bookkeeping: the outcome is only unknown once
  // the request ends without an answer (or the page loads with one left over).
  const unknown = !!actions.pending && !actions.busy;
  if (!actions.storageError && !actions.message && !unknown) return null;
  const blocked = actions.busy || !!actions.storageError;
  return (
    <div className="space-y-2">
      {actions.storageError && <p role="alert">{actions.storageError} Actions are disabled.</p>}
      {actions.message && <p role="alert">{actions.message}</p>}
      {unknown ? (
        <div className="space-y-2 rounded-lg border bg-surface p-3">
          <p>
            {pendingText} Retry the same action before making other changes; it may have been
            started in another tab.
          </p>
          <Button size="sm" disabled={blocked} onClick={actions.retry}>
            Retry same action
          </Button>{" "}
          <ConfirmAction
            label="Clear saved action"
            title="Clear the saved action?"
            description={`${checkText} It may already have gone through.`}
            disabled={blocked}
            onConfirm={actions.clearAfterReview}
          />
        </div>
      ) : null}
    </div>
  );
}

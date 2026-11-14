import { Button } from "@/components/ui/button";
import { ConfirmAction } from "./backend-confirm-action";

type Actions = {
  message: string | null;
  storageError: string | null;
  pending: unknown;
  busy: boolean;
  retry: () => void;
  clearAfterReview: () => void;
};

/**
 * The outcome of the last action, and, when its result is unknown, a way to retry it with the same key or
 * discard it after checking. While an action is unresolved, others of the same kind stay blocked.
 */
export function ActionStatus({
  actions,
  pendingText,
  checkText,
}: {
  actions: Actions;
  /** Shown while an action's outcome is unknown, e.g. "A table change may not have been saved." */
  pendingText: string;
  /** What to check before discarding, e.g. "Check your tables first." */
  checkText: string;
}) {
  if (!actions.storageError && !actions.message && !actions.pending) return null;
  return (
    <div className="space-y-2">
      {actions.storageError && <p role="alert">{actions.storageError} Actions are disabled.</p>}
      {actions.message && <p role="alert">{actions.message}</p>}
      {actions.pending ? (
        <div className="space-y-2 rounded-lg border bg-surface p-3">
          <p>{pendingText} Retry it before making other changes.</p>
          <Button size="sm" disabled={actions.busy} onClick={actions.retry}>
            Retry
          </Button>{" "}
          <ConfirmAction
            label="Discard"
            title="Discard the saved action?"
            description={`${checkText} It may already have gone through.`}
            disabled={actions.busy}
            onConfirm={actions.clearAfterReview}
          />
        </div>
      ) : null}
    </div>
  );
}

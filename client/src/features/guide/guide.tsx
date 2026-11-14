import { HowToPlay } from "@/features/guide/components/guide-page";
import { FeedbackSection } from "@/features/feedback/components/feedback-form";
import type { BackendUser } from "./backend-api";
import { ActionStatus } from "./backend-action-status";
import { FEEDBACK_SENT, useFeedbackActions } from "./feedback-actions";

/** The How to play guide, with its feedback form sending to the backend. */
export function BackendGuide({ user, expire }: { user: BackendUser; expire: () => void }) {
  const actions = useFeedbackActions(user.id, expire);
  const sent = actions.message === FEEDBACK_SENT && !actions.pending;
  return (
    <HowToPlay
      feedback={
        <FeedbackSection
          send={(input) => actions.start(input)}
          sent={sent}
          onSendMore={actions.dismiss}
          disabled={actions.busy || !!actions.pending || !!actions.storageError}
          status={
            sent ? null : (
              <ActionStatus
                actions={actions}
                pendingText="Your feedback may not have been sent."
                checkText="We can't show sent feedback yet, so retrying is safer."
              />
            )
          }
        />
      }
    />
  );
}

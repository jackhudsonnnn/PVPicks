import { HowToPlay } from "./guide-page";
import { FeedbackSection } from "./feedback-form";
import type { User } from "@/features/people";
import { ActionStatus } from "@/components/action-status";
import { useFeedbackActions } from "./feedback-actions";

/** The How to play guide, with its feedback form sending to the backend. */
export function GuidePage({ user, expire }: { user: User; expire: () => void }) {
  const actions = useFeedbackActions(user.id, expire);
  const sent = actions.succeeded && !actions.pending;
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

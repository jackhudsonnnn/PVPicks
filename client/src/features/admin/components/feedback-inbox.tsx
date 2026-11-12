import { EmptyState } from "@/components/empty-state";
import { dateTimeLabel } from "@/lib/time";
import { useState } from "react";
import { ScrollContainer } from "@/components/scroll-container";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { accountLabel } from "@/features/auth/demo-accounts";
import { FEEDBACK_KINDS, type FeedbackKind } from "@/features/feedback/feedback";
import { useFeedback } from "@/features/feedback/feedback-storage";

type Filter = "all" | FeedbackKind;

/** Feedback sent from the Guide, newest first. */
export function FeedbackInbox() {
  const { items } = useFeedback();
  const [filter, setFilter] = useState<Filter>("all");
  const shown = items.filter((f) => filter === "all" || f.kind === filter);
  return (
    <section className="space-y-3 rounded-2xl border bg-surface p-4 sm:p-5">
      <div>
        <h2 className="font-display text-2xl font-extrabold uppercase leading-none">
          Feedback inbox
        </h2>
        <p className="mt-1 text-xs text-muted-foreground">From the Guide's Feedback section.</p>
      </div>
      <SegmentedTabs<Filter>
        size="sm"
        ariaLabel="Feedback type"
        tabs={[
          { id: "all", label: "All", count: items.length },
          ...FEEDBACK_KINDS.map((k) => ({
            id: k.id,
            label: k.label,
            count: items.filter((f) => f.kind === k.id).length,
          })),
        ]}
        value={filter}
        onChange={setFilter}
      />
      {shown.length === 0 ? (
        <EmptyState className="py-8">No feedback yet.</EmptyState>
      ) : (
        <ScrollContainer isVisible={false} className="max-h-96">
          <ul className="grid gap-2">
            <AnimatedList key={filter}>
              {shown.map((f) => (
                <AnimatedItem as="li" key={f.id}>
                  <div className="space-y-1 rounded-xl border bg-sidebar p-3">
                    <p className="font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
                      {FEEDBACK_KINDS.find((k) => k.id === f.kind)?.label} ·{" "}
                      {accountLabel(f.account)} · {dateTimeLabel(f.createdAt)}
                    </p>
                    <p className="whitespace-pre-wrap break-words text-sm">{f.text}</p>
                    {f.link && (
                      <a
                        href={f.link}
                        target="_blank"
                        rel="noreferrer"
                        className="break-all text-xs font-semibold text-primary underline"
                      >
                        {f.link}
                      </a>
                    )}
                  </div>
                </AnimatedItem>
              ))}
            </AnimatedList>
          </ul>
        </ScrollContainer>
      )}
    </section>
  );
}

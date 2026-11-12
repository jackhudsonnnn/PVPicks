import { PageHeader } from "@/components/page-header";
import { useState } from "react";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { useReports } from "@/features/social/reports-storage";
import { useFeedback } from "@/features/feedback/feedback-storage";
import { ReportsQueue } from "./reports-queue";
import { FeedbackInbox } from "./feedback-inbox";

type Tab = "feedback" | "reports";

/** Guide feedback and safety reports in one place. */
export function ModerationPage() {
  const [tab, setTab] = useState<Tab>("feedback");
  const { items } = useFeedback();
  const { reports } = useReports();
  const open = reports.filter((r) => r.status === "open" || r.status === "reviewing").length;
  return (
    <div className="mx-auto max-w-4xl space-y-6 px-4 py-6 md:px-8 md:py-10">
      <PageHeader eyebrow="Monitor" title="Feedback" />
      <SegmentedTabs<Tab>
        ariaLabel="Feedback or reports"
        tabs={[
          { id: "feedback", label: "Feedback", count: items.length },
          { id: "reports", label: "Reports", count: open },
        ]}
        value={tab}
        onChange={setTab}
      />
      {tab === "feedback" ? <FeedbackInbox /> : <ReportsQueue />}
    </div>
  );
}

import { useCallback } from "react";
import { SHARED_KEYS } from "@/features/auth/storage-keys";
import { sampleReports } from "@/features/mock/sample-reports";
import { createLocalCollection } from "@/lib/local-collection";
import { newId } from "@/lib/ids";
import type { Report, ReportStatus } from "./safety";

/**
 * Reports are shared across demo accounts (saved in this browser) so a report filed as
 * Pro shows up in the Admin queue, like it would on a server.
 * TODO(backend): a server table with moderator-only access.
 */
const reports = createLocalCollection<Report>(SHARED_KEYS.reports, {
  seed: () => sampleReports(Date.now()),
});

/** Read and update the shared reports list; stays in sync across tabs and components. */
export function useReports() {
  const items = reports.useItems();

  const file = useCallback((r: Omit<Report, "id" | "createdAt" | "status">) => {
    const report: Report = { ...r, id: newId("r"), createdAt: Date.now(), status: "open" };
    reports.add(report);
    return report;
  }, []);

  const setStatus = useCallback((id: string, status: ReportStatus) => {
    reports.update(id, (r) => ({ ...r, status }));
  }, []);

  return { reports: items, file, setStatus };
}

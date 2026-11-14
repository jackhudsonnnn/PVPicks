import { z } from "zod";
import { BackendError, idSchema, mutationRequest, read, request } from "@/lib/api-client";

const person = z.object({
  id: idSchema,
  username: z.string().nullable(),
  status: z.enum(["ACTIVE", "FROZEN", "DELETED"]),
  strikes: z.number().int().min(0).max(3),
});
const reportSchema = z.object({
  id: idSchema,
  reason: z.enum(["harassment", "settlement", "spam", "offensive", "other"]),
  note: z.string(),
  status: z.enum(["OPEN", "RESOLVED", "DISMISSED"]),
  createdAt: z.string().datetime({ offset: true }),
  reporter: person,
  target: person,
  tableId: idSchema.nullable().optional(),
  tableName: z.string().nullable().optional(),
  messageSnapshot: z.string().nullable().optional(),
});

export type ModerationPerson = z.infer<typeof person>;
export type ModerationReport = z.infer<typeof reportSchema>;

export async function listReports(all: boolean, signal?: AbortSignal): Promise<ModerationReport[]> {
  return read(
    await request(`/admin/reports?status=${all ? "all" : "open"}`, signal ? { signal } : undefined),
    z.array(reportSchema),
  );
}

export async function listFlagged(signal?: AbortSignal): Promise<ModerationPerson[]> {
  return read(
    await request("/admin/users/flagged", signal ? { signal } : undefined),
    z.array(person),
  );
}

const valid = (id: string) => {
  if (!idSchema.safeParse(id).success) throw new BackendError("Not found.", 404);
};

/**
 * Admin actions. Each is one-shot on the server (a second strike or dismissal of the same report answers
 * "already handled"), so a fresh key per attempt is safe and there's no saved retry.
 */
export async function moderate(
  action: "dismiss" | "strike",
  reportId: string,
): Promise<{ strikes: number; frozen: boolean } | null> {
  valid(reportId);
  const response = await mutationRequest(
    `/admin/reports/${encodeURIComponent(reportId)}/${action}`,
    "POST",
    crypto.randomUUID(),
  );
  if (action === "dismiss" || response.status === 204) return null;
  return read(response, z.object({ strikes: z.number().int(), frozen: z.boolean() }));
}

export async function changeUser(action: "unfreeze" | "clear-strikes", userId: string) {
  valid(userId);
  await mutationRequest(
    `/admin/users/${encodeURIComponent(userId)}/${action}`,
    "POST",
    crypto.randomUUID(),
  );
}

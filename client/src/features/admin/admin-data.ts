import { useCallback, useEffect, useState } from "react";
import {
  DEMO_ACCOUNTS,
  PLAYER_ACCOUNTS,
  type PlayerAccountId,
} from "@/features/auth/demo-accounts";
import { SHARED_KEYS } from "@/features/auth/storage-keys";
import { seedFor, type SeedState } from "@/features/mock/profiles";
import { ME, USERS } from "@/features/mock/seed";
import { isRecord, readJson, writeJson } from "@/lib/storage";
import { applyAdminAction, type AdminAction, type AdminFlags } from "./user-audit";

/**
 * Where the admin pages get their data. Today: the three sample worlds, built once.
 * TODO(backend): admin API endpoints (analytics, user audit, admin actions) replace this file.
 */
export interface AdminWorld {
  id: PlayerAccountId;
  label: string;
  /** The world's own player (newbie_nate, inter_ivy, pro_pat). */
  playerId: string;
  data: SeedState;
}

let cache: Record<PlayerAccountId, AdminWorld> | null = null;

export function sampleWorlds(): Record<PlayerAccountId, AdminWorld> {
  cache ??= Object.fromEntries(
    PLAYER_ACCOUNTS.map((id) => [
      id,
      {
        id,
        label: DEMO_ACCOUNTS.find((a) => a.id === id)!.label,
        playerId: ME,
        data: seedFor(id),
      },
    ]),
  ) as Record<PlayerAccountId, AdminWorld>;
  return cache;
}

/** Everyone the admin can see, for names and avatars outside a single world. */
export const ADMIN_DIRECTORY = USERS;

/** Admin flags kept on this device until a server exists. */
export function useAdminFlags() {
  const [all, setAll] = useState<Record<string, AdminFlags>>({});
  useEffect(() => {
    setAll(readJson(SHARED_KEYS.adminFlags, {}, isRecord));
  }, []);
  const act = useCallback((userId: string, a: AdminAction) => {
    const prev = readJson<Record<string, AdminFlags>>(SHARED_KEYS.adminFlags, {}, isRecord);
    const next = { ...prev, [userId]: applyAdminAction(prev[userId] ?? {}, a) };
    writeJson(SHARED_KEYS.adminFlags, next);
    setAll(next);
  }, []);
  return { all, act };
}

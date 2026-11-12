import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { MotionConfig } from "motion/react";
import { useAppStore, useGetUser, useMe } from "@/features/store";
import { useAuth } from "@/features/auth/auth-provider";
import { SHARED_KEYS, accountStorageKeys } from "@/features/auth/storage-keys";
import { DEFAULT_INVITE_PREFS, type InvitePrefs } from "@/features/social/invites";
import { isRecord, readJson, writeJson } from "@/lib/storage";
import { setClock24h } from "@/lib/time";
import { DEFAULT_DISPLAY, MOTION_CONFIG, TEXT_SIZE_PX, type DisplayPrefs } from "./display";
import type { Profile } from "./profile";

const isProfile = (v: unknown): v is Profile =>
  isRecord(v) && typeof v["username"] === "string" && typeof v["hue"] === "number";

// Display settings stay with the device; profile and invite settings are saved per account.
// TODO(backend): profile and invite settings belong to the account on the server.

interface SettingsValue {
  display: DisplayPrefs;
  updateDisplay: (patch: Partial<DisplayPrefs>) => void;
  saveProfile: (p: Profile) => void;
  saveInvitePrefs: (p: InvitePrefs) => void;
}

const Ctx = createContext<SettingsValue | null>(null);

/** Needs to sit inside a provider keyed by account, so a new account starts fresh. */
export function SettingsProvider({ children }: { children: ReactNode }) {
  const { actions } = useAppStore();
  const { account } = useAuth();
  const keys = accountStorageKeys(account ?? "none");
  const [display, setDisplay] = useState<DisplayPrefs>(DEFAULT_DISPLAY);
  const loaded = useRef(false);
  const actionsRef = useRef(actions);
  actionsRef.current = actions;

  // Read saved settings after hydration so server and client render the same first frame.
  useEffect(() => {
    setDisplay({ ...DEFAULT_DISPLAY, ...readJson(SHARED_KEYS.display, {}, isRecord) });
    const profile = readJson<Profile | null>(keys.profile, null, isProfile);
    if (profile) actionsRef.current.updateProfile(profile);
    const invites = readJson<Partial<InvitePrefs> | null>(keys.tableInvites, null, isRecord);
    if (invites) actionsRef.current.setInvitePrefs({ ...DEFAULT_INVITE_PREFS, ...invites });
    loaded.current = true;
  }, [keys.profile, keys.tableInvites]);

  useEffect(() => {
    document.documentElement.style.fontSize = `${TEXT_SIZE_PX[display.textSize]}px`;
    setClock24h(display.clock24h);
    const mq = window.matchMedia("(prefers-color-scheme: light)");
    const rm = window.matchMedia("(prefers-reduced-motion: reduce)");
    const apply = () => {
      const root = document.documentElement;
      const light = display.theme === "light" || (display.theme === "device" && mq.matches);
      root.classList.toggle("light", light);
      // CSS-only animations (e.g. switch thumbs) follow the same setting as motion animations.
      const reduced = display.motion === "reduced" || (display.motion === "device" && rm.matches);
      root.classList.toggle("reduce-motion", reduced);
    };
    apply();
    mq.addEventListener("change", apply);
    rm.addEventListener("change", apply);
    if (loaded.current) writeJson(SHARED_KEYS.display, display);
    return () => {
      mq.removeEventListener("change", apply);
      rm.removeEventListener("change", apply);
    };
  }, [display]);

  const value: SettingsValue = {
    display,
    updateDisplay: (patch) => setDisplay((d) => ({ ...d, ...patch })),
    saveProfile: (p) => {
      actions.updateProfile(p);
      writeJson(keys.profile, p);
    },
    saveInvitePrefs: (p) => {
      actions.setInvitePrefs(p);
      writeJson(keys.tableInvites, p);
    },
  };

  return (
    <Ctx.Provider value={value}>
      <MotionConfig reducedMotion={MOTION_CONFIG[display.motion]}>{children}</MotionConfig>
    </Ctx.Provider>
  );
}

export function useSettings() {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useSettings must be used inside SettingsProvider");
  return ctx;
}

/** Your current username and avatar color. */
export function useMyProfile(): Profile {
  const u = useGetUser()(useMe());
  return { username: u.username, hue: u.hue };
}

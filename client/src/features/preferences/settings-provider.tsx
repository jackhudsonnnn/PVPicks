import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { MotionConfig } from "motion/react";
import { SHARED_KEYS } from "@/features/auth/storage-keys";
import { isRecord, readJson, writeJson } from "@/lib/storage";
import { setClock24h } from "@/lib/time";
import { DEFAULT_DISPLAY, MOTION_CONFIG, TEXT_SIZE_PX, type DisplayPrefs } from "./display";

// Display settings stay with the device (theme, text size, motion, clock), signed in or not.

interface SettingsValue {
  display: DisplayPrefs;
  updateDisplay: (patch: Partial<DisplayPrefs>) => void;
}

const Ctx = createContext<SettingsValue | null>(null);

/** Applies display preferences to the whole page and owns the app-wide MotionConfig. */
export function SettingsProvider({ children }: { children: ReactNode }) {
  const [display, setDisplay] = useState<DisplayPrefs>(DEFAULT_DISPLAY);
  const current = useRef(display);
  current.current = display;

  // Read saved settings after hydration so server and client render the same first frame.
  useEffect(() => {
    setDisplay({ ...DEFAULT_DISPLAY, ...readJson(SHARED_KEYS.display, {}, isRecord) });
  }, []);

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
    return () => {
      mq.removeEventListener("change", apply);
      rm.removeEventListener("change", apply);
    };
  }, [display]);

  const value: SettingsValue = {
    display,
    // Saved only on a user change, so applying (or re-applying) settings never overwrites what's stored.
    updateDisplay: (patch) => {
      const next = { ...current.current, ...patch };
      writeJson(SHARED_KEYS.display, next);
      setDisplay(next);
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

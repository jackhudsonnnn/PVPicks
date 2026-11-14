/** Display preferences, saved on this device. */

export type TextSize = "small" | "default" | "large";
export type ThemePref = "dark" | "light" | "device";
export type MotionPref = "device" | "reduced" | "full";

export interface DisplayPrefs {
  theme: ThemePref;
  textSize: TextSize;
  motion: MotionPref;
  clock24h: boolean;
}

export const DEFAULT_DISPLAY: DisplayPrefs = {
  theme: "dark",
  textSize: "default",
  motion: "device",
  clock24h: false,
};

/** Root font size; the whole app is sized in rem so this scales everything. */
export const TEXT_SIZE_PX: Record<TextSize, number> = { small: 15, default: 16, large: 18 };

/** Maps the setting to motion's MotionConfig `reducedMotion`. */
export const MOTION_CONFIG: Record<MotionPref, "user" | "always" | "never"> = {
  device: "user",
  reduced: "always",
  full: "never",
};

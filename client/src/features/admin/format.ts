import { MODES } from "@/features/bets";

/** Formatting for admin numbers. Points are integer cents shown as points with two decimals. */

export const points = (cents: number) =>
  `${cents < 0 ? "−" : ""}${(Math.abs(cents) / 100).toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;

export const signedPoints = (cents: number) => (cents > 0 ? `+${points(cents)}` : points(cents));

export const percent = (ratio: number | null, digits = 0) =>
  ratio === null ? "–" : `${(ratio * 100).toFixed(digits)}%`;

export const count = (value: number) => value.toLocaleString();

/** Milliseconds, switching to seconds at 10 s. */
export const ms = (value: number | null) =>
  value === null
    ? "–"
    : value >= 10_000
      ? `${(value / 1000).toFixed(1)} s`
      : `${Math.round(value)} ms`;

export const bytes = (value: number) => {
  const units = ["B", "KB", "MB", "GB"];
  let size = value;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit++;
  }
  return `${size.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
};

/** "3d 4h", "2h 5m", "45s". */
export const duration = (seconds: number) => {
  const d = Math.floor(seconds / 86_400);
  const h = Math.floor((seconds % 86_400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${seconds % 60}s`;
  return `${seconds}s`;
};

/** "just now", "5 min ago", "3 h ago", "2 d ago", or "–". */
export const ago = (iso: string | null, now = Date.now()) => {
  if (!iso) return "–";
  const seconds = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000));
  if (seconds < 45) return "just now";
  if (seconds < 3600) return `${Math.round(seconds / 60)} min ago`;
  if (seconds < 86_400) return `${Math.round(seconds / 3600)} h ago`;
  return `${Math.round(seconds / 86_400)} d ago`;
};

/** "in 12 min", "in under a minute": how long until a time a few minutes away. */
export const inMinutes = (iso: string, now = Date.now()) => {
  const minutes = Math.ceil((new Date(iso).getTime() - now) / 60_000);
  return minutes <= 1 ? "in under a minute" : `in ${minutes} min`;
};

/** A full local timestamp with seconds and zone, for screenshots that get passed around. */
export const stamp = (iso: string | number | Date) =>
  new Date(iso).toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    timeZoneName: "short",
  });

const MODE_NAMES = new Map<string, string>(MODES.map((mode) => [mode.id, mode.name]));

export const modeName = (mode: string) => MODE_NAMES.get(mode) ?? mode;

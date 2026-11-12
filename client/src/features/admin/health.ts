/**
 * Site health numbers. Simulated until real telemetry exists.
 * TODO: replace with a server metrics feed (latency, error rate, uptime).
 */
export interface HealthPoint {
  t: number;
  p50: number;
  p95: number;
  errors: number;
}

/** Adds one sample: p50 jitters around 80ms, p95 is always at least p50. Keeps the last `max`. */
export function healthTick(prev: HealthPoint[], rand: () => number, max = 40): HealthPoint[] {
  const t = (prev.at(-1)?.t ?? 0) + 1;
  const p50 = Math.round(60 + rand() * 40);
  const p95 = p50 + Math.round(40 + rand() * 120);
  const errors = rand() < 0.08 ? 1 : 0;
  return [...prev, { t, p50, p95, errors }].slice(-max);
}

export type HealthStatus = "healthy" | "degraded" | "down";

/** Status from recent samples: p95 over 400ms or 3+ errors = degraded; no samples = down. */
export function healthStatus(points: HealthPoint[]): HealthStatus {
  if (!points.length) return "down";
  const recent = points.slice(-10);
  const errors = recent.reduce((s, p) => s + p.errors, 0);
  const worst = Math.max(...recent.map((p) => p.p95));
  return errors >= 3 || worst > 400 ? "degraded" : "healthy";
}

export const percentile = (xs: number[], q: number) => {
  if (!xs.length) return 0;
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor(q * s.length))]!;
};

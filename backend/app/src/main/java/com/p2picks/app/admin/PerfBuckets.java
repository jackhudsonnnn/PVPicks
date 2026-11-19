package com.p2picks.app.admin;

/**
 * Latency histogram bounds in milliseconds. A sample lands in the first bucket whose bound it doesn't exceed;
 * the extra last bucket holds everything slower. Percentiles interpolate inside the bucket they fall in, so
 * they're estimates, accurate to the bucket width.
 */
final class PerfBuckets {
    static final int[] BOUNDS = {5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000};
    static final int SIZE = BOUNDS.length + 1;

    private PerfBuckets() {}

    static int index(long ms) {
        for (int i = 0; i < BOUNDS.length; i++) if (ms <= BOUNDS[i]) return i;
        return BOUNDS.length;
    }

    /**
     * The {@code q} quantile (0–1) of a histogram, or null when empty. Interpolates linearly inside the bucket;
     * the open last bucket reports the slowest sample seen ({@code max}).
     */
    static Integer quantile(long[] counts, double q, int max) {
        long total = 0;
        for (long count : counts) total += count;
        if (total == 0) return null;
        double rank = q * total;
        long seen = 0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 0) continue;
            if (seen + counts[i] >= rank) {
                if (i == BOUNDS.length) return max;
                double low = i == 0 ? 0 : BOUNDS[i - 1];
                double high = BOUNDS[i];
                double estimate = low + (high - low) * Math.max(0, rank - seen) / counts[i];
                return (int) Math.min(Math.round(estimate), max);
            }
            seen += counts[i];
        }
        return max;
    }
}

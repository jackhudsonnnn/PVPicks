package com.p2picks.app.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PerfBucketsTest {
    @Test
    void samplesLandInTheFirstBucketTheyFit() {
        assertThat(PerfBuckets.index(0)).isZero();
        assertThat(PerfBuckets.index(5)).isZero();
        assertThat(PerfBuckets.index(6)).isEqualTo(1);
        assertThat(PerfBuckets.index(10_000)).isEqualTo(PerfBuckets.BOUNDS.length - 1);
        assertThat(PerfBuckets.index(10_001)).isEqualTo(PerfBuckets.BOUNDS.length);
    }

    @Test
    void quantilesInterpolateInsideTheirBucket() {
        long[] counts = new long[PerfBuckets.SIZE];
        assertThat(PerfBuckets.quantile(counts, 0.5, 0)).isNull();
        counts[PerfBuckets.index(40)] = 100; // all in the 25–50 ms bucket
        assertThat(PerfBuckets.quantile(counts, 0.5, 49)).isEqualTo(38);
        assertThat(PerfBuckets.quantile(counts, 0.99, 49)).isEqualTo(49); // never above the slowest seen
        counts[PerfBuckets.SIZE - 1] = 100; // and as many slower than 10 s
        assertThat(PerfBuckets.quantile(counts, 0.99, 30_000)).isEqualTo(30_000);
        assertThat(PerfBuckets.quantile(counts, 0.25, 30_000)).isEqualTo(38);
    }
}

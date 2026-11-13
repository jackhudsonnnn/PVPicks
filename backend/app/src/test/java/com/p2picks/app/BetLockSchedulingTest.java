package com.p2picks.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BetLockSchedulingTest {
    @Test
    void schedulingIsDisabledUnlessExplicitlyEnabled() {
        BetLockWorker worker = mock(BetLockWorker.class);
        new ApplicationContextRunner().withUserConfiguration(BetLockScheduling.class)
                .withBean(BetLockWorker.class, () -> worker)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(BetLockScheduling.class);
                    verifyNoInteractions(worker);
                });
    }

    @Test
    void enabledSchedulingStartsImmediatelyAndRetriesAfterScanFailure() {
        BetLockWorker worker = mock(BetLockWorker.class);
        doThrow(new IllegalStateException("simulated scan failure")).doNothing().when(worker).poll();
        new ApplicationContextRunner().withUserConfiguration(BetLockScheduling.class)
                .withBean(BetLockWorker.class, () -> worker)
                .withPropertyValues("pvpicks.bet-lock-worker.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(BetLockScheduling.class);
                    verify(worker, timeout(8000).atLeast(2)).poll();
                });
    }
}

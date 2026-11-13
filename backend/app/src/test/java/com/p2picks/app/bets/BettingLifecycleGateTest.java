package com.p2picks.app.bets;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BettingLifecycleGateTest {
    @Test
    void mutationControllerIsNotRegisteredWithoutLifecycleReadiness() {
        new ApplicationContextRunner().withUserConfiguration(BetMutationController.class)
                .run(context -> assertThat(context).doesNotHaveBean(BetMutationController.class));
    }
}

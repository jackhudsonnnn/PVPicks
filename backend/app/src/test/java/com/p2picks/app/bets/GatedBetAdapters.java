package com.p2picks.app.bets;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Registers the gated bet HTTP adapter, which production leaves unregistered. */
@TestConfiguration(proxyBeanMethods = false)
public class GatedBetAdapters {
    @Bean
    BetMutationController testBetMutationController(U2PickProposalService proposals, BetPickService picks,
            U2PickResolutionService resolutions, BetReadController reads) {
        return new BetMutationController(proposals, picks, resolutions, reads);
    }
}

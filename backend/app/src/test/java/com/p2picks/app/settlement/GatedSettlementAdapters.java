package com.p2picks.app.settlement;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Registers the gated settlement HTTP adapters, which production leaves unregistered. */
@TestConfiguration(proxyBeanMethods = false)
public class GatedSettlementAdapters {
    @Bean
    PointsSettlementController testPointsSettlementController(TablePointsSettlementService settlements) {
        return new PointsSettlementController(settlements);
    }

    @Bean
    SettlementReceiptController testSettlementReceiptController(SettlementReceiptService receipts) {
        return new SettlementReceiptController(receipts);
    }

    @Bean
    SettlementGroupController testSettlementGroupController(SettlementGroupService groups, SettlementReviewService reviews) {
        return new SettlementGroupController(groups, reviews);
    }
}

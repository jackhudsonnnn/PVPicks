package com.p2picks.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

class BettingLifecycleGateTest {
    @Test
    void mutationControllerIsNotRegisteredWithoutLifecycleReadiness() {
        new ApplicationContextRunner().withUserConfiguration(
                        BetMutationController.class, PointsSettlementController.class, SettlementReceiptController.class,
                        SettlementGroupController.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(BetMutationController.class);
                    assertThat(context).doesNotHaveBean(PointsSettlementController.class);
                    assertThat(context).doesNotHaveBean(SettlementReceiptController.class);
                    assertThat(context).doesNotHaveBean(SettlementGroupController.class);
                });
    }

    @Test
    void pendingReviewListEnforcesIdentityAndMatchesContract() throws IOException {
        SettlementReviewService reviews = mock(SettlementReviewService.class);
        SettlementGroupController controller = new SettlementGroupController(mock(SettlementGroupService.class), reviews);
        Jwt jwt = mock(Jwt.class);
        when(reviews.listPending(jwt)).thenReturn(
                new SettlementReviewService.PendingReviews(false, false, List.of()),
                new SettlementReviewService.PendingReviews(true, false, List.of()),
                new SettlementReviewService.PendingReviews(true, true, List.of(
                        new SettlementReviewService.PendingReview(UUID.randomUUID(), UUID.randomUUID()))));
        assertThat(controller.pendingReviews(jwt).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.pendingReviews(jwt).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var response = controller.pendingReviews(jwt);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        var body = new ObjectMapper().valueToTree(response.getBody());
        assertThat(body.size()).isEqualTo(1);
        assertThat(OpenApiSchemas.load("PendingSettlementReview").validate(body.get(0))).isEmpty();
    }
}

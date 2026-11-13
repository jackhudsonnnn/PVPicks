package com.p2picks.app.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.DefaultHealthContributorRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

class AdminHealthControllerTest {

    @Test
    void reportsDownAndUnknownWithoutIndicatorDetails() {
        var contributors = new DefaultHealthContributorRegistry(Map.of(
                "db", (HealthIndicator) () -> Health.up().withDetail("host", "private").build(),
                "redis", (HealthIndicator) () -> Health.down().withDetail("error", "private").build()));
        var response = new AdminHealthController(contributors).health();
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).isNotNull().satisfies(snapshot -> {
            assertThat(snapshot.postgres()).isEqualTo(AdminHealthController.CheckStatus.UP);
            assertThat(snapshot.redis()).isEqualTo(AdminHealthController.CheckStatus.DOWN);
            assertThat(snapshot.kafka()).isEqualTo(AdminHealthController.KafkaStatus.NOT_CHECKED);
        });
        contributors.unregisterContributor("redis");
        contributors.registerContributor("redis", (HealthIndicator) () -> Health.unknown().build());
        assertThat(new AdminHealthController(contributors).health().getBody().redis())
                .isEqualTo(AdminHealthController.CheckStatus.UNKNOWN);
    }

    @Test
    void missingOrUnsupportedIndicatorsFailExplicitly() {
        var contributors = new DefaultHealthContributorRegistry();
        assertThatThrownBy(() -> new AdminHealthController(contributors).health())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db");
        contributors.registerContributor("db", (HealthIndicator) () -> Health.status("CUSTOM").build());
        assertThatThrownBy(() -> new AdminHealthController(contributors).health())
                .isInstanceOf(IllegalArgumentException.class);
    }
}

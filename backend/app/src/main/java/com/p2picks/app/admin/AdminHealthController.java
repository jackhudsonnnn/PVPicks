package com.p2picks.app.admin;

import java.time.Instant;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AdminHealthController {
    private final HealthContributorRegistry contributors;

    AdminHealthController(HealthContributorRegistry contributors) {
        this.contributors = contributors;
    }

    @GetMapping("/admin/health")
    ResponseEntity<Snapshot> health() {
        CheckStatus postgres = status("db");
        CheckStatus redis = status("redis");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Snapshot(Instant.now(), postgres, redis, KafkaStatus.NOT_CHECKED));
    }

    private CheckStatus status(String name) {
        HealthContributor contributor = contributors.getContributor(name);
        if (!(contributor instanceof HealthIndicator indicator)) {
            throw new IllegalStateException("Missing or unsupported health contributor: " + name);
        }
        return CheckStatus.valueOf(indicator.getHealth(false).getStatus().getCode());
    }

    enum CheckStatus {
        UP, DOWN, OUT_OF_SERVICE, UNKNOWN
    }

    enum KafkaStatus {
        NOT_CHECKED
    }

    record Snapshot(Instant checkedAt, CheckStatus postgres, CheckStatus redis, KafkaStatus kafka) {}
}

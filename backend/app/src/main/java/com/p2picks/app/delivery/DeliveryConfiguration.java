package com.p2picks.app.delivery;

import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class DeliveryConfiguration {
    @Bean
    NewTopic outboxTopic() {
        // Keys are aggregate identities, so one aggregate's events share a partition.
        return TopicBuilder.name(OutboxRelay.TOPIC).partitions(3).replicas(1).build();
    }

    /** Polls the outbox. Disabled with PVPICKS_OUTBOX_RELAY_ENABLED=false; tests call the relay directly. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "pvpicks.outbox-relay.enabled", havingValue = "true")
    static class RelayScheduling {
        private static final Logger log = LoggerFactory.getLogger(RelayScheduling.class);
        private final OutboxRelay relay;

        RelayScheduling(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelay = 500)
        void poll() {
            try {
                relay.publishPending();
            } catch (RuntimeException exception) {
                // Repeats every poll while Kafka is down, so keep the stack trace at debug level.
                log.warn("Outbox relay failed; unpublished events will be retried: {}", exception.getMessage());
                log.debug("Outbox relay failure", exception);
            }
        }
    }

    /**
     * Deletes old published outbox rows daily. Disabled with PVPICKS_OUTBOX_CLEANUP_ENABLED=false; retention
     * defaults to seven days (pvpicks.outbox-cleanup.retention).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "pvpicks.outbox-cleanup.enabled", havingValue = "true")
    static class CleanupScheduling {
        private static final Logger log = LoggerFactory.getLogger(CleanupScheduling.class);
        private final OutboxCleanup cleanup;
        private final Duration retention;

        CleanupScheduling(OutboxCleanup cleanup, @Value("${pvpicks.outbox-cleanup.retention:P7D}") Duration retention) {
            this.cleanup = cleanup;
            this.retention = retention;
        }

        @Scheduled(cron = "${pvpicks.outbox-cleanup.cron:0 30 4 * * *}")
        void clean() {
            try {
                int deleted = cleanup.deletePublishedOlderThan(retention);
                if (deleted > 0) log.info("Deleted {} published outbox rows older than {}", deleted, retention);
            } catch (RuntimeException exception) {
                log.warn("Outbox cleanup failed; it will run again at the next scheduled time", exception);
            }
        }
    }
}

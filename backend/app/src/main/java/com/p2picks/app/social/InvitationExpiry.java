package com.p2picks.app.social;

import com.p2picks.app.common.MembershipLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Marks table requests expired 30 days after they were sent (domain rules, "Membership limits"). Housekeeping only:
 * reads, the request cap and responses already treat older pending requests as expired, so this can run late (or
 * on several instances at once) without changing what anyone sees.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "pvpicks.invitation-expiry.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
class InvitationExpiry {
    private static final Logger log = LoggerFactory.getLogger(InvitationExpiry.class);

    private final JdbcTemplate jdbc;

    InvitationExpiry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    void expire() {
        int expired = jdbc.update("UPDATE table_invites i SET status = 'EXPIRED' WHERE " + MembershipLimits.LAPSED);
        if (expired > 0) log.info("Expired {} table requests older than {} days", expired, MembershipLimits.REQUEST_DAYS);
    }
}

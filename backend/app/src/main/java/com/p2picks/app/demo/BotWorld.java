package com.p2picks.app.demo;

import com.p2picks.app.demo.DemoRoster.DemoUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The demo world as the bots see it on one tick: the main players' tables, who's at them, and the open bets. */
final class BotWorld {
    private BotWorld() {}

    record Member(UUID table, UUID user, boolean host, boolean canBet, boolean canText, boolean canReact,
            boolean canGif) {}

    record Table(UUID id, boolean hostsOnlyBets, boolean u2pickEnabled, String jackblackAccess,
            String holdemAccess) {}

    record World(UUID pro, Set<UUID> mainIds, Map<UUID, Table> tables, List<Member> members,
            Set<UUID> proTables, Set<UUID> botIds) {
        List<Member> botsIn(UUID table, Map<UUID, DemoUser> bots) {
            return members.stream().filter(m -> m.table().equals(table) && bots.containsKey(m.user())).toList();
        }
    }

    record OpenBet(UUID id, UUID table, String mode, BigDecimal line, List<String> choices, Instant locksAt) {}
}

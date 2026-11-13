package com.p2picks.modules.bets.api;

import static com.p2picks.modules.bets.api.U2PickSettler.Rule.ANYONE;
import static com.p2picks.modules.bets.api.U2PickSettler.Rule.HOSTS;
import static com.p2picks.modules.bets.api.U2PickSettler.Rule.ME;
import static com.p2picks.modules.bets.api.U2PickSettler.Rule.NON_PARTICIPANTS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class U2PickSettlerTest {
    private static final UUID HOST = new UUID(0, 1);
    private static final UUID PROPOSER = new UUID(0, 2);
    private static final UUID PLAYER = new UUID(0, 3);
    private static final UUID WATCHER = new UUID(0, 4);
    private static final UUID FROZEN = new UUID(0, 5);
    private static final Set<UUID> MEMBERS = Set.of(HOST, PROPOSER, PLAYER, WATCHER);
    private static final Set<UUID> HOST_IDS = Set.of(HOST);
    private static final Set<UUID> POT = Set.of(PROPOSER, PLAYER);

    @Test
    void rulesRequireEligibleMembershipAndAuthorizeTheSpecifiedPeople() {
        assertThat(U2PickSettler.canSettle(ANYONE, WATCHER, PROPOSER, MEMBERS, HOST_IDS, POT)).isTrue();
        assertThat(U2PickSettler.canSettle(ANYONE, FROZEN, PROPOSER, MEMBERS, HOST_IDS, POT)).isFalse();
        assertThat(U2PickSettler.canSettle(HOSTS, HOST, PROPOSER, MEMBERS, HOST_IDS, POT)).isTrue();
        assertThat(U2PickSettler.canSettle(HOSTS, PROPOSER, PROPOSER, MEMBERS, HOST_IDS, POT)).isFalse();
        assertThat(U2PickSettler.canSettle(ME, PROPOSER, PROPOSER, MEMBERS, HOST_IDS, POT)).isTrue();
        assertThat(U2PickSettler.canSettle(ME, HOST, PROPOSER, MEMBERS, HOST_IDS, POT)).isFalse();
        assertThat(U2PickSettler.canSettle(ME, PROPOSER, PROPOSER, Set.of(HOST), HOST_IDS, POT)).isFalse();
    }

    @Test
    void nonParticipantsIncludeNoGuessButNotPlayersInThePot() {
        assertThat(U2PickSettler.canSettle(NON_PARTICIPANTS, WATCHER, PROPOSER, MEMBERS, HOST_IDS, POT)).isTrue();
        assertThat(U2PickSettler.canSettle(NON_PARTICIPANTS, PROPOSER, PROPOSER, MEMBERS, HOST_IDS, POT)).isFalse();
        assertThat(U2PickSettler.canSettle(NON_PARTICIPANTS, HOST, PROPOSER, MEMBERS, HOST_IDS, POT)).isTrue();
    }

    @Test
    void hostsCanSettleWhenAllEligibleMembersAreInPotEvenWithFrozenNonParticipants() {
        Set<UUID> allEligibleInPot = Set.of(HOST, PROPOSER, PLAYER);
        assertThat(U2PickSettler.canSettle(NON_PARTICIPANTS, HOST, PROPOSER, allEligibleInPot,
                HOST_IDS, allEligibleInPot)).isTrue();
        assertThat(U2PickSettler.canSettle(NON_PARTICIPANTS, PLAYER, PROPOSER, allEligibleInPot,
                HOST_IDS, allEligibleInPot)).isFalse();
        assertThat(U2PickSettler.canSettle(NON_PARTICIPANTS, FROZEN, PROPOSER, allEligibleInPot,
                HOST_IDS, allEligibleInPot)).isFalse();
    }
}

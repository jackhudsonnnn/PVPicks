package com.p2picks.app.bets;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class U2PickSettler {
    public enum Rule { ANYONE, NON_PARTICIPANTS, HOSTS, ME }

    private U2PickSettler() {}

    // Eligible members are active player accounts; in-pot users have an actual pick, not No guess.
    public static boolean canSettle(Rule rule, UUID actor, UUID proposer, Set<UUID> eligibleMembers,
            Set<UUID> hosts, Set<UUID> inPot) {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(proposer, "proposer");
        Objects.requireNonNull(eligibleMembers, "eligibleMembers");
        Objects.requireNonNull(hosts, "hosts");
        Objects.requireNonNull(inPot, "inPot");
        if (!eligibleMembers.contains(actor)) return false;
        return switch (rule) {
            case ANYONE -> true;
            case HOSTS -> hosts.contains(actor);
            case ME -> proposer.equals(actor);
            case NON_PARTICIPANTS -> !inPot.contains(actor)
                    || (eligibleMembers.stream().allMatch(inPot::contains) && hosts.contains(actor));
        };
    }
}

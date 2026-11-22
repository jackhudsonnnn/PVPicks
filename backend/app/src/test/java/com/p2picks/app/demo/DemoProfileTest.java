package com.p2picks.app.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DemoProfileTest {
    @Test
    void archetypesChangeOnlyTheirParametersFromTheDefault() {
        DemoProfile lurker = DemoProfile.Archetype.LURKER.profile();
        assertThat(lurker.joinBetChance()).isEqualTo(0.2);
        assertThat(lurker.followBackChance()).as("untouched").isEqualTo(DemoProfile.DEFAULT.followBackChance());
        assertThat(DemoProfile.Archetype.CASUAL.profile()).isEqualTo(DemoProfile.DEFAULT);
        assertThat(DemoProfile.DEFAULT.with(p -> p.joinBetChance(0.9)).joinBetChance()).isEqualTo(0.9);
    }

    @Test
    void parametersAreValidated() {
        assertThatThrownBy(() -> DemoProfile.DEFAULT.with(p -> p.joinBetChance(1.5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DemoProfile.DEFAULT.with(p -> p.wager(500, 100)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRosterIsStableUniqueAndValid() {
        List<DemoRoster.DemoUser> users = DemoRoster.users();
        assertThat(users).hasSize(DemoRoster.SIZE).isEqualTo(DemoRoster.users());
        assertThat(new HashSet<>(users.stream().map(DemoRoster.DemoUser::username).toList())).hasSize(DemoRoster.SIZE);
        assertThat(users).allSatisfy(user -> {
            assertThat(user.username()).matches("[a-z0-9_]{3,15}").isNotIn(DemoRoster.MAIN);
            assertThat(user.hue()).isBetween(1, 5);
            assertThat(user.id()).isEqualTo(DemoRoster.idFor(user.username()));
        });
        Map<DemoProfile.Archetype, Long> counts = users.stream()
                .collect(Collectors.groupingBy(DemoRoster.DemoUser::archetype, Collectors.counting()));
        assertThat(counts.keySet()).as("every archetype appears").containsExactlyInAnyOrder(DemoProfile.Archetype.values());
        assertThat(users.stream().map(DemoRoster.DemoUser::profile).map(Function.identity()).distinct().count())
                .as("jitter varies profiles").isGreaterThan(DemoRoster.SIZE / 2);
        assertThat(DemoRoster.regulars()).hasSize(DemoRoster.REGULARS)
                .noneMatch(user -> user.archetype() == DemoProfile.Archetype.MANIAC);
        assertThat(DemoRoster.crowd()).hasSize(DemoRoster.CROWD)
                .allMatch(user -> user.archetype() == DemoProfile.Archetype.MANIAC);
    }

    @Test
    void maniacsAreAggressiveButDontFloodModeration() {
        DemoProfile maniac = DemoProfile.Archetype.MANIAC.profile();
        DemoProfile degen = DemoProfile.Archetype.DEGEN.profile();
        assertThat(maniac.joinBetChance()).isGreaterThan(degen.joinBetChance());
        assertThat(maniac.proposalsPerHour()).isGreaterThan(degen.proposalsPerHour());
        assertThat(maniac.messagesPerHour()).isGreaterThan(degen.messagesPerHour());
        assertThat(maniac.reactionMaxSeconds()).isLessThan(DemoProfile.DEFAULT.reactionMaxSeconds());
        assertThat(maniac.blockChance()).isZero();
        assertThat(maniac.reportChance()).isZero();
    }
}

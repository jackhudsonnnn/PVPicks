package com.p2picks.modules.bets.internal;

import static com.p2picks.modules.bets.internal.PayoutCalculator.RemainderMode.EARLIEST;
import static com.p2picks.modules.bets.internal.PayoutCalculator.RemainderMode.HOST;
import static com.p2picks.modules.bets.internal.PayoutCalculator.RemainderMode.PROPOSER;
import static com.p2picks.modules.bets.internal.PayoutCalculator.RemainderMode.RANDOM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.p2picks.modules.bets.internal.PayoutCalculator.Participant;
import com.p2picks.modules.bets.internal.PayoutCalculator.Remainder;
import com.p2picks.modules.bets.internal.PayoutCalculator.Result;
import com.p2picks.modules.bets.internal.PayoutCalculator.WashReason;
import com.p2picks.shared.kernel.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayoutCalculatorTest {
    private static final UUID BET = id(1);
    private static final List<String> CHOICES = List.of("pass", "run", "neither");
    private static final Remainder EARLIEST_RULE = new Remainder(EARLIEST, null);

    @Test
    void splitsPotAndExcludesNoGuessButNotSportsPass() {
        Result result = settle(List.of(p(1, "pass"), p(2, "pass"), p(3, "run"), p(4, null)),
                100, false, "pass", EARLIEST_RULE);
        assertThat(deltas(result)).containsExactlyInAnyOrderEntriesOf(Map.of(
                id(1), new Money(50), id(2), new Money(50), id(3), new Money(-100)));
        assertThat(deltas(settle(List.of(p(1, "pass"), p(2, "pass"), p(3, "run")),
                100, false, "run", EARLIEST_RULE)))
                .containsEntry(id(3), new Money(200));
    }

    @Test
    void washesInvalidOutcomeInsufficientPlayersSameSideAndUnpickedWinner() {
        List<Participant> both = List.of(p(1, "pass"), p(2, "run"));
        assertThat(settle(both, 100, false, null, EARLIEST_RULE))
                .isEqualTo(new Result.Washed(WashReason.INVALID_OUTCOME));
        assertThat(settle(both, 100, false, "outside", EARLIEST_RULE))
                .isEqualTo(new Result.Washed(WashReason.INVALID_OUTCOME));
        assertThat(settle(List.of(p(1, "pass"), p(2, null)), 100, false, "pass", EARLIEST_RULE))
                .isEqualTo(new Result.Washed(WashReason.INSUFFICIENT_PLAYERS));
        assertThat(settle(List.of(p(1, "pass"), p(2, "pass")), 100, false, "pass", EARLIEST_RULE))
                .isEqualTo(new Result.Washed(WashReason.SAME_SIDE));
        assertThat(settle(both, 100, false, "neither", EARLIEST_RULE))
                .isEqualTo(new Result.Washed(WashReason.NO_WINNER));
    }

    @Test
    void assignsRemainderToSnapshottedHostProposerOrEarliestWinner() {
        List<Participant> players = List.of(p(1, "pass"), p(2, "pass"), p(3, "pass"), p(4, "run"));
        assertThat(deltas(settle(players, 100, false, "pass", new Remainder(HOST, id(5)))))
                .containsEntry(id(5), new Money(1));
        assertThat(deltas(settle(players, 100, false, "pass", new Remainder(PROPOSER, id(4)))))
                .containsEntry(id(4), new Money(-99));
        assertThat(deltas(settle(players, 100, false, "pass", EARLIEST_RULE)))
                .containsEntry(id(1), new Money(34))
                .containsEntry(id(2), new Money(33));
    }

    @Test
    void randomRemainderMatchesPrototypeAndNeverPicksAWinnerTwice() {
        List<Participant> players = new ArrayList<>();
        for (int i = 1; i <= 7; i++) players.add(p(i, "pass"));
        players.add(p(8, "run"));
        players.add(p(9, "run"));
        Result result = settle(players, 100, false, "pass", new Remainder(RANDOM, null));
        Map<UUID, Money> amounts = deltas(result);
        for (int i : List.of(1, 2, 3, 7)) assertThat(amounts.get(id(i))).isEqualTo(new Money(29));
        for (int i : List.of(4, 5, 6)) assertThat(amounts.get(id(i))).isEqualTo(new Money(28));
        assertThat(result).isEqualTo(settle(players, 100, false, "pass", new Remainder(RANDOM, null)));
    }

    @Test
    void fourFunResolvesOrWashesNormallyWithoutTrackedDeltas() {
        assertThat(deltas(settle(List.of(p(1, "pass"), p(2, "run")), 0, true, "pass", EARLIEST_RULE)))
                .isEmpty();
        assertThat(settle(List.of(p(1, "pass"), p(2, "pass")), 0, true, "pass", EARLIEST_RULE))
                .isEqualTo(new Result.Washed(WashReason.SAME_SIDE));
    }

    @Test
    void omitsZeroValueWinnersFromLedgerDeltas() {
        List<Participant> players = new ArrayList<>();
        for (int i = 1; i <= 11; i++) players.add(p(i, "pass"));
        players.add(p(12, "run"));
        Map<UUID, Money> amounts = deltas(settle(players, 10, false, "pass", EARLIEST_RULE));
        assertThat(amounts).hasSize(11).doesNotContainKey(id(11)).containsEntry(id(12), new Money(-10));
    }

    @Test
    void omitsLoserWhenRemainderCancelsTheirWager() {
        List<Participant> players = new ArrayList<>();
        for (int i = 1; i <= 15; i++) players.add(p(i, "pass"));
        players.add(p(16, "run"));
        Map<UUID, Money> amounts = deltas(settle(players, 10, false, "pass", new Remainder(PROPOSER, id(16))));
        assertThat(amounts).isEmpty();
    }

    @Test
    void rejectsInvalidInputInsteadOfTreatingItAsAValidPayout() {
        List<Participant> valid = List.of(p(1, "pass"), p(2, "run"));
        assertThatThrownBy(() -> new Remainder(HOST, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Remainder(PROPOSER, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settle(valid, 9, false, "pass", EARLIEST_RULE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settle(valid, 1001, false, "pass", EARLIEST_RULE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settle(List.of(p(1, "pass"), p(1, "run")), 100, false, "pass", EARLIEST_RULE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settle(List.of(p(1, "unknown")), 100, false, "pass", EARLIEST_RULE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PayoutCalculator.settle(BET, valid, new Money(100), false,
                List.of("pass", "No guess"), "pass", EARLIEST_RULE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allRemainderModesConserveCentsAcrossManyPots() {
        Random random = new Random(921);
        List<Remainder> rules = List.of(new Remainder(HOST, id(100)),
                new Remainder(PROPOSER, id(2)), EARLIEST_RULE, new Remainder(RANDOM, null));
        for (int trial = 0; trial < 500; trial++) {
            int winners = 1 + random.nextInt(48);
            int losers = 1 + random.nextInt(50 - winners);
            long wager = 10 + random.nextInt(991);
            List<Participant> players = new ArrayList<>();
            for (int i = 0; i < winners; i++) players.add(p(i + 1, "pass"));
            for (int i = 0; i < losers; i++) players.add(p(winners + i + 1, "run"));
            for (Remainder rule : rules) {
                Map<UUID, Money> amounts = deltas(settle(players, wager, false, "pass", rule));
                assertThat(amounts.values().stream().mapToLong(Money::cents).sum()).isZero();
                assertThat(amounts.values()).noneMatch(amount -> amount.cents() == 0);
            }
        }
    }

    private static Result settle(
            List<Participant> players, long wager, boolean forFun, String winner, Remainder remainder) {
        return PayoutCalculator.settle(BET, players, new Money(wager), forFun, CHOICES, winner, remainder);
    }

    private static Map<UUID, Money> deltas(Result result) {
        assertThat(result).isInstanceOf(Result.Resolved.class);
        return ((Result.Resolved) result).deltas();
    }

    private static Participant p(long id, String choice) {
        return new Participant(id(id), choice);
    }

    private static UUID id(long number) {
        return new UUID(0, number);
    }
}

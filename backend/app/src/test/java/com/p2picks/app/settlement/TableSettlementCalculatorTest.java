package com.p2picks.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.p2picks.app.settlement.TableSettlementCalculator.BetDeltas;
import com.p2picks.app.settlement.TableSettlementCalculator.Line;
import com.p2picks.app.settlement.TableSettlementCalculator.Plan;
import com.p2picks.shared.kernel.Money;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TableSettlementCalculatorTest {
    private static final UUID HOST = id(1);
    private static final UUID A = id(2);
    private static final UUID B = id(3);

    @Test
    void netsEligibleResultsAndRoutesEveryReceiptThroughHost() {
        BetDeltas first = bet(10, Map.of(HOST, m(50), A, m(50), B, m(-100)));
        BetDeltas second = bet(11, Map.of(HOST, m(-200), A, m(300), B, m(-100)));
        Plan plan = TableSettlementCalculator.calculate(HOST, List.of(first, second));

        assertThat(plan.betIds()).containsExactly(id(10), id(11));
        assertThat(plan.lines()).containsExactly(new Line(B, HOST, m(200)), new Line(HOST, A, m(350)));
        assertThat(plan.hostNet()).isEqualTo(m(-150));
    }

    @Test
    void hostCanSettleEvenWhenNotInAnyBet() {
        Plan plan = TableSettlementCalculator.calculate(HOST, List.of(bet(10, Map.of(A, m(75), B, m(-75)))));
        assertThat(plan.lines()).containsExactly(new Line(B, HOST, m(75)), new Line(HOST, A, m(75)));
        assertThat(plan.hostNet()).isEqualTo(m(0));
    }

    @Test
    void zeroNetResultsStillProduceAPlanWithAllBetIdsAndNoReceipts() {
        Plan plan = TableSettlementCalculator.calculate(HOST, List.of(
                bet(10, Map.of(A, m(100), B, m(-100))),
                bet(11, Map.of(A, m(-100), B, m(100))),
                bet(12, Map.of())));
        assertThat(plan.betIds()).containsExactly(id(10), id(11), id(12));
        assertThat(plan.lines()).isEmpty();
        assertThat(plan.hostNet()).isEqualTo(m(0));
    }

    @Test
    void rejectsUnbalancedDuplicateOrAbsentBetResults() {
        BetDeltas valid = bet(10, Map.of(A, m(1), B, m(-1)));
        assertThatThrownBy(() -> TableSettlementCalculator.calculate(HOST, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TableSettlementCalculator.calculate(HOST, List.of(valid, valid)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TableSettlementCalculator.calculate(
                HOST, List.of(bet(11, Map.of(A, m(1))))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsIntermediateTotalsBeyondLongWhenFinalNetsFit() {
        BetDeltas first = bet(10, Map.of(A, m(Long.MAX_VALUE), B, m(-Long.MAX_VALUE)));
        BetDeltas second = bet(11, Map.of(A, m(1), B, m(-1)));
        BetDeltas third = bet(12, Map.of(A, m(-1), B, m(1)));
        Plan plan = TableSettlementCalculator.calculate(HOST, List.of(first, second, third));
        assertThat(plan.lines()).containsExactly(
                new Line(B, HOST, m(Long.MAX_VALUE)), new Line(HOST, A, m(Long.MAX_VALUE)));
    }

    @Test
    void rejectsAmountsThatCannotFitInReceiptOrHostNet() {
        BetDeltas first = bet(10, Map.of(A, m(Long.MAX_VALUE), B, m(-Long.MAX_VALUE)));
        BetDeltas second = bet(11, Map.of(A, m(1), B, m(-1)));
        assertThatThrownBy(() -> TableSettlementCalculator.calculate(HOST, List.of(first, second)))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> TableSettlementCalculator.calculate(
                HOST, List.of(bet(12, Map.of(A, m(Long.MIN_VALUE), B, m(Long.MAX_VALUE), HOST, m(1))))))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> TableSettlementCalculator.calculate(
                A, List.of(first, second))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void generatedPlansPreserveEachPersonsNetAndHaveUniqueCounterparties() {
        Random random = new Random(842);
        for (int trial = 0; trial < 200; trial++) {
            int people = 2 + random.nextInt(11);
            int count = 1 + random.nextInt(8);
            long[] totals = new long[people];
            List<BetDeltas> bets = new ArrayList<>();
            for (int bet = 0; bet < count; bet++) {
                Map<UUID, Money> amounts = new HashMap<>();
                long sum = 0;
                for (int person = 0; person < people - 1; person++) {
                    long value = random.nextInt(2001) - 1000;
                    amounts.put(id(person + 1), m(value));
                    totals[person] += value;
                    sum += value;
                }
                amounts.put(id(people), m(-sum));
                totals[people - 1] -= sum;
                bets.add(bet(bet + 10, amounts));
            }
            Plan plan = TableSettlementCalculator.calculate(HOST, bets);
            assertThat(plan.hostNet()).isEqualTo(m(totals[0]));
            long[] fromReceipts = new long[people];
            Set<UUID> counterparties = new HashSet<>();
            for (Line line : plan.lines()) {
                assertThat(line.amount().cents()).isPositive();
                boolean hostPays = line.payerId().equals(HOST);
                assertThat(hostPays ^ line.payeeId().equals(HOST)).isTrue();
                UUID other = hostPays ? line.payeeId() : line.payerId();
                assertThat(counterparties.add(other)).isTrue();
                int index = (int) other.getLeastSignificantBits() - 1;
                fromReceipts[index] += hostPays ? line.amount().cents() : -line.amount().cents();
            }
            for (int person = 1; person < people; person++) {
                assertThat(fromReceipts[person]).isEqualTo(totals[person]);
            }
            assertThat(plan.betIds()).hasSize(count);
        }
    }

    private static BetDeltas bet(long id, Map<UUID, Money> deltas) {
        return new BetDeltas(id(id), deltas);
    }

    private static Money m(long cents) {
        return new Money(cents);
    }

    private static UUID id(long number) {
        return new UUID(0, number);
    }
}

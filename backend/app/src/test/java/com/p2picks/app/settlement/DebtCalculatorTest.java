package com.p2picks.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.p2picks.app.settlement.DebtCalculator.OpenReceipt;
import com.p2picks.app.settlement.DebtCalculator.TableBalance;
import com.p2picks.shared.kernel.Money;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DebtCalculatorTest {
    private static final UUID ME = id(1);

    @Test
    void sumsNegativeTableTrueBalancesAndPersonNetsWithoutCrossOffsets() {
        Money debt = DebtCalculator.calculate(ME,
                List.of(table(10, 100, 350), table(11, 10000, 0), table(12, -1, 0)),
                List.of(receipt(20, 2, -500), receipt(21, 2, 175),
                        receipt(22, 3, 10000), receipt(23, 4, -10)));
        assertThat(debt).isEqualTo(m(586));
        assertThat(DebtCalculator.canChangeCap(debt)).isFalse();
    }

    @Test
    void netsOpenReceiptsAcrossTablesForEachPersonAndAllowsZeroDebtCapChanges() {
        Money debt = DebtCalculator.calculate(ME, List.of(table(10, 200, 200)),
                List.of(receipt(20, 2, -100), receipt(21, 2, 100), receipt(22, 3, 200)));
        assertThat(debt).isEqualTo(m(0));
        assertThat(DebtCalculator.canChangeCap(debt)).isTrue();
    }

    @Test
    void comparesExactCapAndExemptsFourFunOrDisabledCap() {
        assertThat(DebtCalculator.canRiskWager(m(100), m(200), m(100), false)).isTrue();
        assertThat(DebtCalculator.canRiskWager(m(100), m(200), m(101), false)).isFalse();
        assertThat(DebtCalculator.canRiskWager(m(100), m(0), m(100), true)).isTrue();
        assertThat(DebtCalculator.canRiskWager(m(Long.MAX_VALUE), null, m(1), false)).isTrue();
        assertThat(DebtCalculator.canRiskWager(m(Long.MAX_VALUE), m(Long.MAX_VALUE), m(1), false)).isFalse();
        assertThatThrownBy(() -> DebtCalculator.canRiskWager(m(0), m(-1), m(1), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateInputsAndInvalidAmounts() {
        TableBalance table = table(10, 0, 0);
        OpenReceipt receipt = receipt(20, 2, -1);
        assertThatThrownBy(() -> DebtCalculator.calculate(ME, List.of(table, table), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DebtCalculator.calculate(ME, List.of(), List.of(receipt, receipt)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DebtCalculator.calculate(ME, List.of(), List.of(receipt(21, 1, -1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> table(10, 0, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receipt(20, 2, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DebtCalculator.canChangeCap(m(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handlesIntermediateOverflowAndRejectsUnrepresentableDebt() {
        Money debt = DebtCalculator.calculate(ME, List.of(), List.of(
                receipt(20, 2, Long.MAX_VALUE), receipt(21, 2, 1), receipt(22, 2, -1)));
        assertThat(debt).isEqualTo(m(0));
        assertThatThrownBy(() -> DebtCalculator.calculate(ME, List.of(table(10, Long.MIN_VALUE, 0)), List.of()))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> DebtCalculator.calculate(ME, List.of(table(10, -Long.MAX_VALUE, 1)), List.of()))
                .isInstanceOf(ArithmeticException.class);
    }

    private static TableBalance table(long id, long result, long pending) {
        return new TableBalance(id(id), BigInteger.valueOf(result), BigInteger.valueOf(pending));
    }

    private static OpenReceipt receipt(long id, long other, long amount) {
        return new OpenReceipt(id(id), id(other), m(amount));
    }

    private static Money m(long cents) {
        return new Money(cents);
    }

    private static UUID id(long number) {
        return new UUID(0, number);
    }
}

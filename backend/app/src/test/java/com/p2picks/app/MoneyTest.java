package com.p2picks.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.p2picks.shared.kernel.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void convertsDecimalPointsExactlyIncludingNegativeDeltas() {
        Money amount = Money.fromPoints(new BigDecimal("-12.340"));

        assertThat(amount.cents()).isEqualTo(-1234);
        assertThat(amount.toPoints()).isEqualTo(new BigDecimal("-12.34"));
    }

    @Test
    void rejectsFractionsOfACentAndOutOfRangeValues() {
        assertThatThrownBy(() -> Money.fromPoints(new BigDecimal("0.001")))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.fromPoints(new BigDecimal("92233720368547758.08")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void arithmeticIsExactAndRejectsOverflow() {
        assertThat(new Money(100).plus(new Money(-25))).isEqualTo(new Money(75));
        assertThat(new Money(100).minus(new Money(125))).isEqualTo(new Money(-25));
        assertThatThrownBy(() -> new Money(Long.MAX_VALUE).plus(new Money(1)))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> new Money(Long.MIN_VALUE).minus(new Money(1)))
                .isInstanceOf(ArithmeticException.class);
    }
}

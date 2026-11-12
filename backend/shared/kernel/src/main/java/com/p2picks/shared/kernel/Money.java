package com.p2picks.shared.kernel;

import java.math.BigDecimal;
import java.util.Objects;

public record Money(long cents) {

    public static Money fromPoints(BigDecimal points) {
        Objects.requireNonNull(points, "points");
        return new Money(points.movePointRight(2).longValueExact());
    }

    public BigDecimal toPoints() {
        return BigDecimal.valueOf(cents, 2);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(cents, other.cents));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(cents, other.cents));
    }
}

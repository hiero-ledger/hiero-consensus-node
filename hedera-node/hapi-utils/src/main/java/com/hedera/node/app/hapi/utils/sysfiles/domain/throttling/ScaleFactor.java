// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.sysfiles.domain.throttling;

import com.google.common.base.MoreObjects;
import com.hedera.node.app.hapi.utils.sysfiles.ParsingUtils;

public record ScaleFactor(int numerator, int denominator) implements Comparable<ScaleFactor> {
    public static final ScaleFactor ONE_TO_ONE = new ScaleFactor(1, 1);

    public static ScaleFactor from(String literal) {
        return ParsingUtils.fromTwoPartDelimited(
                literal,
                ":",
                (n, d) -> {
                    if (n < 0 || d < 0) {
                        throw new IllegalArgumentException("Negative number in scale literal '" + literal + "'");
                    }
                    if (d == 0) {
                        throw new IllegalArgumentException("Division by zero in scale literal '" + literal + "'");
                    }
                },
                Integer::parseInt,
                Integer::parseInt,
                ScaleFactor::new);
    }

    public int scaling(int nominalOps) {
        final int maxUnscaledOps = Integer.MAX_VALUE / numerator;
        if (nominalOps > maxUnscaledOps) {
            return Integer.MAX_VALUE / denominator;
        }
        return Math.max(1, nominalOps * numerator / denominator);
    }

    /**
     * Returns the scale factor as an approximate 1:n split of capacity, rounding up.
     * @return the approximate capacity split
     */
    public int asApproxCapacitySplit() {
        return (denominator + numerator - 1) / numerator;
    }

    @Override
    public int compareTo(final ScaleFactor that) {
        return Integer.compare(this.numerator * that.denominator, that.numerator * this.denominator);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(ScaleFactor.class)
                .add("scale", numerator + ":" + denominator)
                .toString();
    }

    @Override
    public int hashCode() {
        var result = Integer.hashCode(numerator);
        return 31 * result + Integer.hashCode(denominator);
    }
}

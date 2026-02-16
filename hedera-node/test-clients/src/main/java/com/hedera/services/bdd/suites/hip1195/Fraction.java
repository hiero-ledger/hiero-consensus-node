// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195;

import java.math.BigDecimal;
import java.math.BigInteger;

public record Fraction(long numerator, long denominator) {
    /**
     * Converts a decimal price (one whole input token costs {@code price} whole output tokens)
     * into a fraction with both sides denominated in the base units of the respective tokens.
     *
     * @param price the user price in whole tokens
     * @param inputTokenDecimals number of decimals for the input token
     * @param outputTokenDecimals number of decimals for the output token
     * @return the price as a fraction in least terms, denominated in base units
     */
    public static Fraction from(BigDecimal price, int inputTokenDecimals, int outputTokenDecimals) {
        var numerator = LambdaplexVerbs.toBigInteger(price, outputTokenDecimals);
        var denominator = BigInteger.TEN.pow(inputTokenDecimals);
        final var gcd = numerator.gcd(denominator);
        if (!gcd.equals(BigInteger.ZERO)) {
            numerator = numerator.divide(gcd);
            denominator = denominator.divide(gcd);
        }
        return new Fraction(numerator.longValueExact(), denominator.longValueExact());
    }

    public BigInteger biNumerator() {
        return BigInteger.valueOf(numerator);
    }

    public BigInteger biDenominator() {
        return BigInteger.valueOf(denominator);
    }
}

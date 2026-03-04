// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import java.math.BigInteger;

/**
 * Converts between whole-unit and subunit denominations for a token
 * with a configurable number of decimal places.
 */
public final class DenominationConverter {

    private final long subunitsPerWholeUnit;
    private final int decimals;

    /**
     * Creates a converter for a token with the given number of decimal places.
     *
     * @param decimals the number of decimal places (must be between 0 and 18 inclusive)
     * @throws IllegalArgumentException if decimals is outside the valid range
     */
    public DenominationConverter(final int decimals) {
        if (decimals < 0 || decimals > 18) {
            throw new IllegalArgumentException("decimals must be in range [0, 18], but was " + decimals);
        }
        this.decimals = decimals;
        long result = 1L;
        for (int i = 0; i < decimals; i++) {
            result *= 10L;
        }
        this.subunitsPerWholeUnit = result;
    }

    /**
     * Returns the number of subunits in one whole unit.
     *
     * @return the subunit-to-whole-unit conversion factor
     */
    public long subunitsPerWholeUnit() {
        return subunitsPerWholeUnit;
    }

    /**
     * Returns the number of decimal places this converter was configured with.
     *
     * @return the decimal places
     */
    public int decimals() {
        return decimals;
    }

    /**
     * Returns the number of weibars (10⁻¹⁸ of a whole unit) per subunit (10⁻ᵈᵉᶜⁱᵐᵃˡˢ of a whole unit).
     * This is the conversion factor for translating wei-denominated values (as used in Ethereum
     * transactions) to subunit-denominated values.
     *
     * @return {@code BigInteger.valueOf(10^(18 - decimals))}
     */
    public BigInteger weibarsPerSubunit() {
        return BigInteger.TEN.pow(18 - decimals);
    }

    /**
     * Rounds the given subunit amount down to the nearest whole unit boundary.
     *
     * @param subunits the amount in subunits (must be non-negative)
     * @return the largest multiple of {@link #subunitsPerWholeUnit()} that is less than or equal to the input
     * @throws IllegalArgumentException if subunits is negative
     */
    public long roundToWholeUnit(final long subunits) {
        if (subunits < 0) {
            throw new IllegalArgumentException("subunits must be non-negative, but was " + subunits);
        }
        return (subunits / subunitsPerWholeUnit) * subunitsPerWholeUnit;
    }
}

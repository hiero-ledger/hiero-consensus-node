// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

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
     * Rounds the given subunit amount down to the nearest whole unit boundary.
     *
     * @param subunits the amount in subunits
     * @return the largest multiple of {@link #subunitsPerWholeUnit()} that is less than or equal to the input
     */
    public long roundToWholeUnit(final long subunits) {
        return (subunits / subunitsPerWholeUnit) * subunitsPerWholeUnit;
    }
}

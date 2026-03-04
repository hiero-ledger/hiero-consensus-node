// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import com.hedera.node.app.spi.fees.util.FeeUtils;
import java.math.BigInteger;

/**
 * Converts between whole-unit and subunit denominations for a token
 * with a configurable number of decimal places.
 */
public final class DenominationConverter {

    /**
     * Maximum number of decimal places supported. Matches the 18-decimal precision of Ethereum wei.
     */
    public static final int MAX_DECIMALS = 18;

    private static final long DECIMAL_BASE = 10L;

    private final long subunitsPerWholeUnit;
    private final int decimals;

    /**
     * Creates a converter for a token with the given number of decimal places.
     *
     * @param decimals the number of decimal places (must be between 0 and {@value MAX_DECIMALS} inclusive)
     * @throws IllegalArgumentException if decimals is outside the valid range
     */
    public DenominationConverter(final int decimals) {
        if (decimals < 0 || decimals > MAX_DECIMALS) {
            throw new IllegalArgumentException(
                    "decimals must be in range [0, " + MAX_DECIMALS + "], but was " + decimals);
        }
        this.decimals = decimals;
        long result = 1L;
        for (int i = 0; i < decimals; i++) {
            result *= DECIMAL_BASE;
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
        return BigInteger.TEN.pow(MAX_DECIMALS - decimals);
    }

    /**
     * Scales a fee amount computed in default tinybars (10^-8 HBAR) to the
     * configured native coin subunits (10^-decimals HBAR).
     *
     * @param defaultTinybars the fee amount in default tinybars
     * @return the fee amount scaled to the configured denomination
     */
    public long scaleToSubunits(final long defaultTinybars) {
        return FeeUtils.scaleToSubunits(defaultTinybars, subunitsPerWholeUnit);
    }

    /**
     * Rounds the given subunit amount down to the nearest whole unit boundary.
     *
     * @param subunits the amount in subunits (must be non-negative)
     * @return the largest multiple of {@link #subunitsPerWholeUnit()} that is &le; the input
     * @throws IllegalArgumentException if subunits is negative
     */
    public long roundToWholeUnit(final long subunits) {
        if (subunits < 0) {
            throw new IllegalArgumentException("subunits must be non-negative, but was " + subunits);
        }
        return (subunits / subunitsPerWholeUnit) * subunitsPerWholeUnit;
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions.matchers;

import com.hedera.services.bdd.suites.contract.traceability.TraceabilitySuite;
import org.testcontainers.shaded.org.hamcrest.Description;
import org.testcontainers.shaded.org.hamcrest.TypeSafeMatcher;

/**
 * Used in assertions to check if two gas values are equal within a certain range.
 * <p>
 * Depending on the addresses used in {@link TraceabilitySuite},
 * the hard-coded gas values may vary slightly from the observed results.
 * For example, the actual sidecar may have an intrinsic gas cost differing
 * from that of the expected sidecar by a value of {@code 12 * X},
 * where {@code X} is the difference in the number of zero bytes in the
 * transaction payload used between the actual and expected transactions
 * (because the payload includes addresses with different numbers of zeros
 * in their hex encoding). So we allow for a variation of up to {@code 32L} gas.
 * </p>
 *
 * @author vyanev
 */
public class GasMatcher extends TypeSafeMatcher<Long> {

    /**
     * The expected gas value
     */
    private final Long expectedGas;

    /**
     * Flag indicating whether to ignore gas completely
     */
    private final boolean ignoreGas;

    /**
     * The maximum allowed difference between the actual and expected gas values
     */
    private final long maxDelta;

    /**
     * @param expectedGas the expected gas
     * @param ignoreGas flag indicating whether to ignore gas completely
     */
    public GasMatcher(final Long expectedGas, final boolean ignoreGas, final long maxDelta) {
        super(Long.class);
        this.expectedGas = expectedGas;
        this.ignoreGas = ignoreGas;
        this.maxDelta = maxDelta;
    }

    /**
     * @param actualGas the actual gas
     * @return {@code true} if the actual gas is within 32 units of the expected gas
     */
    @Override
    public boolean matchesSafely(final Long actualGas) {
        if (ignoreGas) {
            return true;
        }
        return Math.abs(actualGas - expectedGas) <= maxDelta;
    }

    /**
     * @param description {@link Description} of the expected gas
     */
    @Override
    public void describeTo(final Description description) {
        if (ignoreGas) {
            description.appendText("any gas");
        } else {
            description.appendText("gas within %d units of %d".formatted(maxDelta, expectedGas));
        }
    }
}

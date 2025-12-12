// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.measurement;

/**
 * Base interface for a metric measurement that holds a measurement values(s) per unique combination of dynamic labels.
 * A metric cannot have two measurements with the same set of labels (including empty set of labels).
 * <p>
 * Measurements are mutable and extensions must provide methods to update and get its value(s).
 * Measurement can also be reset to it's initial state.
 * Implementations are expected to be thread-safe and handle concurrent updates atomically,
 * but not all implementations can provide this guarantee.
 */
public interface Measurement {

    /**
     * Resets the measurement to its initial state.
     * Implementations should ensure that any internal state is cleared or set back to default values.
     */
    void reset();
}

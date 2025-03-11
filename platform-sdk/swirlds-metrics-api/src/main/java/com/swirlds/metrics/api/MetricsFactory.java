// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Factory for basic {@link Metric}-implementations
 */
public interface MetricsFactory {

    /**
     * Creates a {@link Counter}
     *
     * @param config the configuration
     * @return the new {@code Counter}
     * @throws IllegalArgumentException if {@code config} is {@code null}
     */
    @NonNull
    Counter createCounter(@NonNull final Counter.Config config);

    /**
     * Creates a {@link DoubleAccumulator}
     *
     * @param config the configuration
     * @return the new {@code DoubleAccumulator}
     * @throws IllegalArgumentException if {@code config} is {@code null}
     */
    @NonNull
    DoubleAccumulator createDoubleAccumulator(@NonNull final DoubleAccumulator.Config config);

    /**
     * Creates a {@link DoubleGauge}
     *
     * @param config the configuration
     * @return the new {@code DoubleGauge}
     * @throws IllegalArgumentException if {@code config} is {@code null}
     */
    @NonNull
    DoubleGauge createDoubleGauge(@NonNull final DoubleGauge.Config config);

    /**
     * Creates a {@link IntegerAccumulator}
     *
     * @param config the configuration
     * @return the new {@code IntegerAccumulator}
     * @throws IllegalArgumentException if {@code config} is {@code null}
     */
    @NonNull
    IntegerAccumulator createIntegerAccumulator(@NonNull IntegerAccumulator.Config config);

    /**
     * Creates a {@link IntegerGauge}
     *
     * @param config the configuration
     * @return the new {@code IntegerGauge}
     * @throws IllegalArgumentException if {@code config} is {@code null}
     */
    @NonNull
    IntegerGauge createIntegerGauge(@NonNull final IntegerGauge.Config config);

    /**
     * Creates a {@link LongAccumulator}
     *
     * @param config the configuration
     * @return the new {@code LongAccumulator}
     * @throws IllegalArgumentException if {@code config} is {@code null}
     */
    @NonNull
    LongAccumulator createLongAccumulator(@NonNull LongAccumulator.Config config);

    /**
     * Creates a {@link LongGauge}
     *
     * @param config the configuration
     * @return the new {@code LongGauge}
     * @throws IllegalArgumentException if {@code config} is {@code null}
     */
    @NonNull
    LongGauge createLongGauge(@NonNull final LongGauge.Config config);
}

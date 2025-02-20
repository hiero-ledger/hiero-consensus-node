// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.hasher;

import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;

/**
 * Encapsulates metrics for state hashing.
 */
public class StateHasherMetrics {

    private static final RunningAverageMetric.Config STATE_HASHING_TIME_CONFIG = new RunningAverageMetric.Config(
                    "platform", "sigStateHash")
            .withDescription("average time it takes to hash a SignedState (in milliseconds)")
            .withUnit("ms");
    private final RunningAverageMetric stateHashingTime;

    /**
     * Constructor.
     *
     * @param metrics the metrics object
     */
    public StateHasherMetrics(@NonNull final Metrics metrics) {
        stateHashingTime = metrics.getOrCreate(STATE_HASHING_TIME_CONFIG);
    }
    /**
     * Report the time taken to hash a state.
     *
     * @param hashingTime the time taken to hash a state
     */
    public void reportHashingTime(@NonNull final Duration hashingTime) {
        stateHashingTime.update(hashingTime.toMillis());
    }
}

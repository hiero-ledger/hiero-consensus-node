// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

import static com.swirlds.metrics.api.FloatFormats.FORMAT_10_2;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_10_3;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_16_2;

import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.common.units.TimeUnit;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;

/**
 * Encapsulates various signed state metrics.
 */
public class SignedStateMetrics {

    private static final String CATEGORY = "platform";
    private static final String MILLISECONDS = TimeUnit.UNIT_MILLISECONDS.getAbbreviation();

    private static final RunningAverageMetric.Config UNSIGNED_STATES_CONFIG = new RunningAverageMetric.Config(
                    CATEGORY, "unsignedStates")
            .withDescription("Average Number Of Unsigned States Awaiting Signatures")
            .withUnit("count")
            .withFormat(FORMAT_10_2);
    private final RunningAverageMetric unsignedStates;

    private static final RunningAverageMetric.Config AVERAGE_TIME_TO_FULLY_SIGN_STATE = new RunningAverageMetric.Config(
                    CATEGORY, "averageTimeToFullySignState")
            .withDescription("The average time spent waiting for enough state signatures to fully sign a state.")
            .withUnit(MILLISECONDS)
            .withFormat(FORMAT_10_2);
    private final RunningAverageMetric averageTimeToFullySignState;

    private static final Counter.Config TOTAL_NEVER_SIGNED_STATES_CONFIG = new Counter.Config(
                    CATEGORY, "totalNeverSignedStates")
            .withDescription("total number of states that did not receive enough signatures in the allowed time")
            .withUnit("count");
    private final Counter totalNeverSignedStates;

    private static final SpeedometerMetric.Config STATES_SIGNED_PER_SECOND_CONFIG = new SpeedometerMetric.Config(
                    CATEGORY, "sstatesSigned_per_sec")
            .withDescription("the number of states completely signed per second")
            .withFormat(FORMAT_16_2)
            .withUnit("hz");
    private final SpeedometerMetric statesSignedPerSecond;

    private static final SpeedometerMetric.Config STATE_SIGNATURES_GATHERED_PER_SECOND_CONFIG =
            new SpeedometerMetric.Config(CATEGORY, "stateSignaturesGathered_per_sec")
                    .withDescription("the number of state signatures gathered from other nodes per second")
                    .withFormat(FORMAT_16_2)
                    .withUnit("hz");
    private final SpeedometerMetric stateSignaturesGatheredPerSecond;

    private static final RunningAverageMetric.Config STATE_SIGNATURE_AGE_CONFIG = new RunningAverageMetric.Config(
                    CATEGORY, "stateSignatureAge")
            .withDescription("the average difference in round number between state "
                    + "signatures and the most recent immutable state. Negative numbers mean"
                    + "the are being received early, large positive numbers mean "
                    + "signatures are being received late.")
            .withFormat(FORMAT_10_3)
            .withUnit("rounds");
    private final RunningAverageMetric stateSignatureAge;

    /**
     * Get a metric tracking unsigned states.
     */
    public RunningAverageMetric getUnsignedStatesMetric() {
        return unsignedStates;
    }

    /**
     * Get a metric tracking average state signing time in milliseconds.
     */
    public RunningAverageMetric getAverageTimeToFullySignStateMetric() {
        return averageTimeToFullySignState;
    }

    /**
     * Get a metric tracking the total number of unsigned states that were skipped.
     */
    public Counter getTotalUnsignedStatesMetric() {
        return totalNeverSignedStates;
    }

    /**
     * Get a metric tracking the total number of states signed per second.
     */
    public SpeedometerMetric getStatesSignedPerSecondMetric() {
        return statesSignedPerSecond;
    }

    /**
     * Get a metric tracking the total number of state signatures gathered from other nodes per second.
     */
    public SpeedometerMetric getStateSignaturesGatheredPerSecondMetric() {
        return stateSignaturesGatheredPerSecond;
    }

    /**
     * Get a metric tracking the average difference in round number between signature transactions and the most recent
     * immutable state.
     */
    public RunningAverageMetric getStateSignatureAge() {
        return stateSignatureAge;
    }

    /**
     * Register all metrics with a registry.
     *
     * @param metrics a reference to the metrics-system
     */
    public SignedStateMetrics(final Metrics metrics) {
        unsignedStates = metrics.getOrCreate(UNSIGNED_STATES_CONFIG);
        averageTimeToFullySignState = metrics.getOrCreate(AVERAGE_TIME_TO_FULLY_SIGN_STATE);
        totalNeverSignedStates = metrics.getOrCreate(TOTAL_NEVER_SIGNED_STATES_CONFIG);
        statesSignedPerSecond = metrics.getOrCreate(STATES_SIGNED_PER_SECOND_CONFIG);
        stateSignaturesGatheredPerSecond = metrics.getOrCreate(STATE_SIGNATURES_GATHERED_PER_SECOND_CONFIG);
        stateSignatureAge = metrics.getOrCreate(STATE_SIGNATURE_AGE_CONFIG);
    }
}

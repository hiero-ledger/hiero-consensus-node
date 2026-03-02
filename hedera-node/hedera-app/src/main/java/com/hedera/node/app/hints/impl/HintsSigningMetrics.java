// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static java.util.Objects.requireNonNull;

import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.consensus.metrics.RunningAverageMetric;

/**
 * Metrics for hinTS block-hash signing attempts.
 */
@Singleton
public class HintsSigningMetrics {
    private static final String CATEGORY = "app";

    private final Counter signaturesProduced;
    private final Counter attemptsWithoutSignature;
    private final RunningAverageMetric successfulAttemptMillisAvg;

    @Inject
    public HintsSigningMetrics(@NonNull final Metrics metrics) {
        requireNonNull(metrics);
        signaturesProduced = metrics.getOrCreate(new Counter.Config(CATEGORY, "hintsSignaturesProduced")
                .withDescription("Number of aggregated hinTS signatures produced"));
        attemptsWithoutSignature = metrics.getOrCreate(new Counter.Config(CATEGORY, "hintsSigningNoSigCompletions")
                .withDescription("Number of hinTS signing attempts completed without producing a signature"));
        successfulAttemptMillisAvg =
                metrics.getOrCreate(new RunningAverageMetric.Config(CATEGORY, "hintsSigningSuccessMsAvg")
                        .withDescription("Average milliseconds for successful hinTS signing attempts to complete")
                        .withUnit("ms")
                        .withFormat("%,13.3f"));
    }

    /**
     * Records a successful signing attempt completion and its elapsed duration.
     *
     * @param elapsedMillis the elapsed time in milliseconds
     */
    public void recordSignatureProduced(final long elapsedMillis) {
        signaturesProduced.increment();
        successfulAttemptMillisAvg.update(Math.max(0L, elapsedMillis));
    }

    /**
     * Records completion of a signing attempt without an aggregated signature.
     */
    public void recordAttemptCompletedWithoutSignature() {
        attemptsWithoutSignature.increment();
    }
}

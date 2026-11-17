// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.temporal.ChronoUnit;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.metrics.RunningAverageMetric;
import org.hiero.consensus.metrics.extensions.LatencyGauges;

/**
 * Metrics for hinTS block-hash signing attempts.
 * <p>
 * The latency metrics measured from this node's own partial signature submission only include signing attempts where
 * the measured event happened <i>after</i> the submission. When a slow node receives enough signatures before it has
 * produced the block (or just a peer's signature), the event is instead counted by
 * {@link #recordThresholdBeforeSubmit()} or {@link #recordPeerSignatureBeforeSubmit(long)}, so that these cases do not
 * drag the averages towards zero.
 */
@Singleton
public class HintsSigningMetrics {
    private static final Logger log = LogManager.getLogger(HintsSigningMetrics.class);

    private static final String CATEGORY = "app";
    private static final String MICROS_FORMAT = "%,13.3f";
    private static final String MICROS_UNIT = "microseconds";
    private static final double NANOS_PER_MICRO = 1_000.0;

    private final Counter signaturesProduced;
    private final Counter attemptsWithoutSignature;
    private final RunningAverageMetric successfulAttemptMillisAvg;
    private final RunningAverageMetric submitToProofReadyMicrosAvg;
    private final RunningAverageMetric submitToFirstPeerSignatureMicrosAvg;
    private final RunningAverageMetric peerLeadMicrosAvg;
    private final Counter thresholdBeforeSubmit;
    private final Counter peerSignatureBeforeSubmit;

    @Nullable
    private final LatencyGauges submitToProofReadyLatency;

    /**
     * Creates the metrics, including percentile gauges for the latency to an aggregated signature if given a
     * configuration that includes the platform's metrics settings.
     *
     * @param metrics the metrics to register with
     * @param platformConfig if not null, the platform configuration to register latency percentile gauges with
     */
    @Inject
    public HintsSigningMetrics(
            @NonNull final Metrics metrics,
            @Nullable @Named(HintsModule.PLATFORM_CONFIG) final Configuration platformConfig) {
        this(metrics, platformConfig, Time.getCurrent());
    }

    @VisibleForTesting
    HintsSigningMetrics(
            @NonNull final Metrics metrics, @Nullable final Configuration platformConfig, @NonNull final Time time) {
        requireNonNull(metrics);
        requireNonNull(time);
        signaturesProduced = metrics.getOrCreate(new Counter.Config(CATEGORY, "hintsSignaturesProduced")
                .withDescription("Number of aggregated hinTS signatures produced"));
        attemptsWithoutSignature = metrics.getOrCreate(new Counter.Config(CATEGORY, "hintsSigningNoSigCompletions")
                .withDescription("Number of hinTS signing attempts completed without producing a signature"));
        successfulAttemptMillisAvg =
                metrics.getOrCreate(new RunningAverageMetric.Config(CATEGORY, "hintsSigningSuccessMsAvg")
                        .withDescription("Average milliseconds for successful hinTS signing attempts to complete")
                        .withUnit("ms")
                        .withFormat("%,13.3f"));
        submitToProofReadyMicrosAvg =
                metrics.getOrCreate(new RunningAverageMetric.Config(CATEGORY, "hintsSubmitToProofReadyUsAvg")
                        .withDescription("Average latency (µs) from this node submitting its hinTS partial signature "
                                + "to having an aggregated signature for the block proof")
                        .withUnit(MICROS_UNIT)
                        .withFormat(MICROS_FORMAT));
        submitToFirstPeerSignatureMicrosAvg =
                metrics.getOrCreate(new RunningAverageMetric.Config(CATEGORY, "hintsSubmitToFirstPeerSigUsAvg")
                        .withDescription("Average latency (µs) from this node submitting its hinTS partial signature "
                                + "to receiving the first valid partial signature from another node")
                        .withUnit(MICROS_UNIT)
                        .withFormat(MICROS_FORMAT));
        peerLeadMicrosAvg = metrics.getOrCreate(new RunningAverageMetric.Config(CATEGORY, "hintsPeerLeadUsAvg")
                .withDescription("Average time (µs) by which another node's hinTS partial signature for a block "
                        + "arrived before this node submitted its own (i.e., how far this node lags its peers)")
                .withUnit(MICROS_UNIT)
                .withFormat(MICROS_FORMAT));
        thresholdBeforeSubmit = metrics.getOrCreate(new Counter.Config(CATEGORY, "hintsThresholdBeforeSubmit")
                .withDescription("Number of blocks whose hinTS signature was aggregated from other nodes' partial "
                        + "signatures before this node submitted its own"));
        peerSignatureBeforeSubmit = metrics.getOrCreate(new Counter.Config(CATEGORY, "hintsPeerSigBeforeSubmit")
                .withDescription("Number of blocks for which another node's hinTS partial signature arrived before "
                        + "this node submitted its own"));
        submitToProofReadyLatency = registerSubmitToProofReadyLatency(metrics, platformConfig, time);
    }

    /**
     * Registers percentile gauges (by default p50, p90, p99, p99.9, and the max over the last 60 seconds) for the
     * latency from this node submitting its partial signature to having an aggregated signature. Since these are only
     * diagnostics, a configuration they cannot be registered with leaves them out instead of failing startup.
     */
    private static @Nullable LatencyGauges registerSubmitToProofReadyLatency(
            @NonNull final Metrics metrics, @Nullable final Configuration platformConfig, @NonNull final Time time) {
        if (platformConfig == null) {
            return null;
        }
        try {
            return LatencyGauges.register(
                    metrics,
                    platformConfig,
                    time,
                    new LatencyGauges.Config(CATEGORY, "hintsSubmitToProofReadyUs")
                            .withUnit(ChronoUnit.MICROS)
                            .withDescription("latency (µs) from this node submitting its hinTS partial signature to "
                                    + "having an aggregated signature for the block proof"));
        } catch (IllegalArgumentException e) {
            log.warn("Not registering hinTS signing latency percentile gauges", e);
            return null;
        }
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

    /**
     * Records the latency from this node submitting its partial signature to the aggregated signature being ready.
     *
     * @param elapsedNanos the elapsed time in nanoseconds
     */
    public void recordSubmitToProofReady(final long elapsedNanos) {
        final long nanos = Math.max(0L, elapsedNanos);
        submitToProofReadyMicrosAvg.update(nanos / NANOS_PER_MICRO);
        if (submitToProofReadyLatency != null) {
            submitToProofReadyLatency.record(nanos);
        }
    }

    /**
     * Records that the aggregated signature was ready before this node submitted its own partial signature.
     */
    public void recordThresholdBeforeSubmit() {
        thresholdBeforeSubmit.increment();
    }

    /**
     * Records the latency from this node submitting its partial signature to the first peer partial signature.
     *
     * @param elapsedNanos the elapsed time in nanoseconds
     */
    public void recordSubmitToFirstPeerSignature(final long elapsedNanos) {
        submitToFirstPeerSignatureMicrosAvg.update(Math.max(0L, elapsedNanos) / NANOS_PER_MICRO);
    }

    /**
     * Records that a peer's partial signature arrived before this node submitted its own, and by how much.
     *
     * @param leadNanos how long before this node's submission the peer partial signature arrived, in nanoseconds
     */
    public void recordPeerSignatureBeforeSubmit(final long leadNanos) {
        peerSignatureBeforeSubmit.increment();
        peerLeadMicrosAvg.update(Math.max(0L, leadNanos) / NANOS_PER_MICRO);
    }
}

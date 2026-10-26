// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.impl.signature;

import static org.hiero.consensus.model.test.fixtures.roster.RosterWrapperHistoryFactory.createRosterWrapperHistory;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.base.crypto.SigningSchema;
import org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler;
import org.hiero.consensus.benchmark.tools.histogram.LatencyRecorder;
import org.hiero.consensus.event.DefaultIntakeEventCounter;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSourceBuilder;
import org.hiero.consensus.metrics.config.MetricsConfig_;
import org.hiero.consensus.metrics.platform.DefaultMetricsProvider;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.test.fixtures.roster.RosterWithKeys;
import org.hiero.consensus.model.test.fixtures.roster.RosterWrapperFactory;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Measures the latency of {@link DefaultEventSignatureValidator#validateSignature(PlatformEvent)}, which verifies the
 * signature of every event a node receives from its peers.
 *
 * <p>The events are generated and signed once per trial, with keys and a graph derived from fixed seeds. The validator
 * does not change the events, so every invocation verifies the same events. All signatures are valid, as they are in
 * production, and the validator uses the production verifier, {@link CryptoUtils#verifySignature}, which creates a new
 * verifier for the creator's public key on every call. The validator's other collaborators are the production ones as
 * well: the platform metrics of {@link DefaultMetricsProvider}, started as on a node, and a
 * {@link DefaultIntakeEventCounter}. Only the metrics output is disabled, so that the benchmark opens no Prometheus port
 * and writes no CSV file.
 *
 * <p>One invocation validates all events. The primary score is the mean time per event. A {@link LatencyRecorder}
 * also times every call, with one timestamp per call ({@link LatencyRecorder#lap()}), so each latency also contains
 * the counting and the blackhole of the previous call. {@link LatencyProfiler} reports the percentiles and the
 * allocation per call.
 *
 * <p>Each invocation's setup ends with {@code System.gc()}, so usually no garbage collection falls into the measured
 * calls. A pass during which one does is discarded and replaced by the next invocation.
 *
 * <p>The calibration detected no warmup in the mean, but in the first 5 s of a fork, p99.9 was up to 31% higher than
 * later. A warmup iteration of 5 s keeps these slow calls out of the measured iterations.
 *
 * <p>Run with {@code -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler}.
 *
 * <h2>Calibration</h2>
 * <ul>
 *   <li>Date / commit: 2026-10-22, 3e9c98d32d</li>
 *   <li>Machine: hl-cn-benchmark-lin, AS-2115GT-HNTR, AMD EPYC 9254 24-Core Processor, JDK 25.0.2, -XX:ActiveProcessorCount=12 -Xms2g -Xmx2g -XX:+AlwaysPreTouch</li>
 *   <li>Parameter sets: signingSchema=ED25519 / signingSchema=RSA</li>
 *   <li>Steady state after: 0 s / 0 s</li>
 *   <li>Noise (latency.mean, CV): 0.19% / 0.17% per 5 s iteration, 0.02% / 0.15% between forks, - / - between runs (1 of 1 / 1 of 1 runs quiet)</li>
 *   <li>Expected detectable regression: 0.08% / 0.30% (95 % CI half-width of one run)</li>
 *   <li>Samples per iteration: 1.5 M / 1.3 M; expected stable: mean, p50, p90, p99, p99.9 / mean, p50, p90, p99, p99.9</li>
 *   <li>Runtime: 2.4 / 3.0 min per parameter set</li>
 *   <li>Raw data: https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/39719427212</li>
 * </ul>
 */
@State(Scope.Thread)
@Fork(
        value = 1,
        jvmArgsAppend = {"-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"})
@Warmup(iterations = 1, time = 5)
@Measurement(iterations = 2, time = 70)
public class EventSignatureValidatorBenchmark {

    /** The seed of the random number generators. The same seed produces the same keys and events. */
    private static final long SEED = 0;

    /**
     * The number of nodes, and therefore of distinct public keys. In alternating local runs with 4, 10, and 30 nodes,
     * the latency of Ed25519 did not change; with RSA, 30 nodes had a p50 about 0.35% higher, a constant offset.
     */
    private static final int NUMBER_OF_NODES = 4;

    /** The number of events validated in one invocation. */
    private static final int NUMBER_OF_EVENTS = 10_000;

    /** The signing schema of the nodes' keys. */
    @Param({"ED25519", "RSA"})
    public SigningSchema signingSchema;

    /** The generated, signed events. */
    private List<PlatformEvent> events;

    /** The provider of the validator's metrics, running for the whole trial. */
    private DefaultMetricsProvider metricsProvider;

    /** The validator under test. */
    private DefaultEventSignatureValidator validator;

    /** The number of events the validator accepted in the current invocation. */
    private int acceptedEvents;

    /** The latency recorder for the benchmark. */
    private LatencyRecorder recorder;

    /**
     * Generates the keys and the signed events, and creates the validator. This method is called once per trial.
     */
    @Setup(Level.Trial)
    public void generateEvents() {
        final RosterWithKeys rosterWithKeys = RosterWrapperFactory.randomRosterWithKeys(
                new Random(SEED), NUMBER_OF_NODES, WeightGenerators.GAUSSIAN, signingSchema);
        final GeneratorEventGraphSource generator = GeneratorEventGraphSourceBuilder.builder()
                .rosterWithKeys(rosterWithKeys)
                .seed(SEED)
                .realSignatures(true)
                .build();
        events = generator.nextEvents(NUMBER_OF_EVENTS);

        final Configuration configuration = new TestConfigBuilder()
                .withValue(MetricsConfig_.DISABLE_METRICS_OUTPUT, true)
                .getOrCreateConfig();
        final RosterWrapper roster = rosterWithKeys.roster();
        metricsProvider = new DefaultMetricsProvider(configuration);
        validator = new DefaultEventSignatureValidator(
                metricsProvider.createPlatformMetrics(roster.nodeIdAtIndex(0)),
                Time.getCurrent(),
                CryptoUtils::verifySignature,
                createRosterWrapperHistory(0L, roster),
                new DefaultIntakeEventCounter(roster));
        metricsProvider.start();
    }

    /**
     * Stops the metrics provider. This method is called once per trial.
     */
    @TearDown(Level.Trial)
    public void stopMetrics() {
        metricsProvider.stop();
    }

    /**
     * Prepares the benchmark invocation. This method is called once per invocation.
     */
    @Setup(Level.Invocation)
    public void prepareInvocation() {
        acceptedEvents = 0;
        recorder = LatencyProfiler.recorder();

        // Collect the garbage of the previous invocation now, so that it is not collected during the measured calls.
        System.gc();
    }

    /**
     * Verifies that the validator accepted every event. This method is called once per invocation.
     */
    @TearDown(Level.Invocation)
    public void verifyInvocation() {
        if (acceptedEvents != NUMBER_OF_EVENTS) {
            throw new IllegalStateException(
                    "Expected " + NUMBER_OF_EVENTS + " accepted events, but got " + acceptedEvents);
        }
        recorder = null;
    }

    /**
     * Validates the signatures of all events and records the latency of each call.
     *
     * @param bh the blackhole to consume the results and prevent dead code elimination
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @OperationsPerInvocation(NUMBER_OF_EVENTS)
    public void validateSignatures(final Blackhole bh) {
        recorder.startMeasurement();
        for (final PlatformEvent event : events) {
            final PlatformEvent validated = validator.validateSignature(event);
            // One timestamp per call: each latency also contains the bookkeeping of the previous call.
            recorder.lap();
            if (validated == event) {
                acceptedEvents++;
            }
            bh.consume(validated);
        }
        recorder.stopMeasurement();
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.impl.signature;

import static org.hiero.consensus.benchmark.tools.event.EmpiricalDistribution.constant;
import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_FIRST;
import static org.hiero.consensus.model.test.fixtures.roster.RosterWrapperFactory.randomRosterWithKeys;
import static org.hiero.consensus.model.test.fixtures.roster.RosterWrapperHistoryFactory.createRosterWrapperHistory;
import static org.hiero.consensus.test.fixtures.WeightGenerators.GAUSSIAN;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.base.crypto.SigningSchema;
import org.hiero.consensus.benchmark.tools.event.EventShape;
import org.hiero.consensus.benchmark.tools.event.EventStreamGenerator;
import org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler;
import org.hiero.consensus.benchmark.tools.histogram.LatencyRecorder;
import org.hiero.consensus.event.DefaultIntakeEventCounter;
import org.hiero.consensus.metrics.config.MetricsConfig_;
import org.hiero.consensus.metrics.platform.DefaultMetricsProvider;
import org.hiero.consensus.model.event.EventHashFactory;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.test.fixtures.roster.RosterWithKeys;
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
 * <p>{@link EventStreamGenerator} generates and signs the events once per trial, from fixed seeds. Each has one other
 * parent and one transaction of 445 bytes, and is hashed with SHA-256, as in production after the event cutover. Only
 * the hash is signed, so the shape of the events does not change the validator's work. The validator does not change
 * the events, so every invocation verifies the same events. All signatures are valid, as they are in
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
 * <p>The calibration detected no warmup in the mean, but in the first 5 s of a fork, p99.9 was up to 34% higher than
 * later. A warmup iteration of 5 s keeps these slow calls out of the measured iterations.
 *
 * <p>Run with {@code -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler}.
 *
 * <h2>Calibration</h2>
 * <ul>
 *   <li>Date / commit: 2026-10-24, 5f2e94701c</li>
 *   <li>Machine: hl-cn-benchmark-lin, AS-2115GT-HNTR, AMD EPYC 9254 24-Core Processor, JDK 25.0.2, -XX:ActiveProcessorCount=12 -Xms2g -Xmx2g -XX:+AlwaysPreTouch</li>
 *   <li>Parameter sets: signingSchema=ED25519 / signingSchema=RSA</li>
 *   <li>Steady state after: 0 s / 0 s</li>
 *   <li>Noise (latency.mean, CV): 0.21% / 0.18% per 5 s iteration, 0.12% / 0.23% between forks, 0.00% / 0.00% between runs (3 of 3 / 3 of 3 runs quiet)</li>
 *   <li>Expected detectable regression: 0.25% / 0.45% (95 % CI half-width of one run)</li>
 *   <li>Samples per iteration: 1.5 M / 1.3 M; expected stable: mean, p50, p90, p99, p99.9 / mean, p50, p90, p99, p99.9</li>
 *   <li>Runtime: 2.4 / 2.9 min per parameter set</li>
 *   <li>Raw data: https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/40138349924, https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/40138351818, https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/40138353526</li>
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
     * The number of nodes, and therefore of distinct public keys: 32, as in the other consensus-layer benchmarks. One
     * count is enough. In alternating local runs with 4, 10, and 30 nodes, the latency of Ed25519 did not change; with
     * RSA, 30 nodes had a p50 about 0.35% higher, a constant offset.
     */
    private static final int NUMBER_OF_NODES = 32;

    /** The number of events validated in one invocation. */
    private static final int NUMBER_OF_EVENTS = 10_000;

    /** The shape of the events: one other parent and one transaction of 445 bytes. */
    private static final EventShape SHAPE = new EventShape(constant(1), constant(1), constant(445));

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
        // Every event birth round is past the cutover, so every event is hashed with SHA-256.
        EventHashFactory.initialize(0);
        final RosterWithKeys rosterWithKeys =
                randomRosterWithKeys(new Random(SEED), NUMBER_OF_NODES, GAUSSIAN, signingSchema);
        final RosterWrapper roster = rosterWithKeys.roster();
        events = EventStreamGenerator.generateSignedEvents(
                SHAPE, roster, NUMBER_OF_EVENTS, SEED, rosterWithKeys.privateKeys());

        final Configuration configuration = new TestConfigBuilder()
                .withValue(MetricsConfig_.DISABLE_METRICS_OUTPUT, true)
                .getOrCreateConfig();
        metricsProvider = new DefaultMetricsProvider(configuration);
        validator = new DefaultEventSignatureValidator(
                metricsProvider.createPlatformMetrics(roster.nodeIdAtIndex(0)),
                Time.getCurrent(),
                CryptoUtils::verifySignature,
                createRosterWrapperHistory(ROUND_FIRST, roster),
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

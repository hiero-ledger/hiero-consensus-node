// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;

import com.swirlds.base.test.fixtures.time.FakeTime;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import org.hiero.consensus.test.fixtures.Randotron;
import org.hiero.otter.fixtures.Capability;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.chaosbot.ChaosBot;
import org.hiero.otter.fixtures.chaosbot.ChaosBotConfiguration;
import org.hiero.otter.fixtures.internal.simulator.SimulatorTimeManager;

/**
 * A test environment for the Falcon framework.
 *
 * <p>This class implements the {@link TestEnvironment} interface and provides methods to access the
 * network, time manager, etc. for tests running on the Falcon framework.
 *
 * <p>In addition to the functionality of {@link TestEnvironment}, it exposes the creation-to-consensus latency and the
 * event throughput of the network via {@link #consensusLatency()}.
 */
public class FalconTestEnvironment implements TestEnvironment {

    /** Capabilities supported by the Falcon test environment */
    private static final Set<Capability> CAPABILITIES = unmodifiableSet(EnumSet.of(Capability.DETERMINISTIC_EXECUTION));

    /** Default granularity of the simulation */
    public static final Duration DEFAULT_GRANULARITY = Duration.ofMillis(10);

    private final FalconNetwork network;
    private final SimulatorTimeManager timeManager;
    private final TransactionGenerator transactionGenerator;

    /**
     * Constructor of {@link FalconTestEnvironment} using the {@link #DEFAULT_GRANULARITY}.
     *
     * @param randomSeed the seed for the random number generator used in the test environment
     */
    public FalconTestEnvironment(final long randomSeed) {
        this(randomSeed, DEFAULT_GRANULARITY);
    }

    /**
     * Constructor of {@link FalconTestEnvironment}.
     *
     * <p>This constructor is public so that a test can create several environments, for example one per point of a
     * parameter sweep. An environment created this way must be {@link #destroy() destroyed} by its creator.
     *
     * @param randomSeed the seed for the random number generator used in the test environment
     * @param granularity the amount of simulated time that passes with each tick; every timestamp in the simulation is
     * quantized to it. Must be positive.
     * @throws IllegalArgumentException if {@code granularity} is not positive
     */
    public FalconTestEnvironment(final long randomSeed, @NonNull final Duration granularity) {
        requireNonNull(granularity);
        if (granularity.isNegative() || granularity.isZero()) {
            throw new IllegalArgumentException("Granularity must be positive, but was " + granularity);
        }
        final Randotron randotron = Randotron.create(randomSeed);
        final FakeTime time = new FakeTime(randotron.nextInstant(), Duration.ZERO);
        timeManager = new SimulatorTimeManager(time, granularity);
        transactionGenerator = new FalconTransactionGenerator();
        network = new FalconNetwork(randotron, timeManager, transactionGenerator);
        timeManager.addTimeTickReceiver(network);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Set<Capability> capabilities() {
        return CAPABILITIES;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network network() {
        return network;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public TimeManager timeManager() {
        return timeManager;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public TransactionGenerator transactionGenerator() {
        return transactionGenerator;
    }

    /**
     * Returns the creation-to-consensus latency and the event throughput of the network, measured in simulated time
     * from the moment the network was started until now.
     *
     * @return a snapshot of the latency and throughput measured so far
     * @throws IllegalStateException if the network has not been started yet
     */
    @NonNull
    public ConsensusLatencyResult consensusLatency() {
        return network.latencyRecorder().snapshot(timeManager.now());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ChaosBot createChaosBot(@NonNull final ChaosBotConfiguration configuration) {
        throw new UnsupportedOperationException("ChaosBot is not supported in FalconTestEnvironment");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Path outputDirectory() {
        throw new UnsupportedOperationException("OutputDirectory is not supported in FalconTestEnvironment");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() {
        network.destroy();
    }
}

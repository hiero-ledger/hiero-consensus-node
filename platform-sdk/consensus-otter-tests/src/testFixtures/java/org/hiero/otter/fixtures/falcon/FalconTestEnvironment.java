// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static java.util.Collections.unmodifiableSet;

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
 */
public class FalconTestEnvironment implements TestEnvironment {

    /** Capabilities supported by the Falcon test environment */
    private static final Set<Capability> CAPABILITIES = unmodifiableSet(EnumSet.of(Capability.DETERMINISTIC_EXECUTION));

    /** Default granularity of the simulation */
    static final Duration GRANULARITY = Duration.ofMillis(10);

    private final FalconNetwork network;
    private final SimulatorTimeManager timeManager;
    private final TransactionGenerator transactionGenerator;

    /**
     * Constructor of {@link FalconTestEnvironment}
     *
     * @param randomSeed the seed for the random number generator used in the test environment
     */
    public FalconTestEnvironment(final long randomSeed) {
        final Randotron randotron = Randotron.create(randomSeed);
        final FakeTime time = new FakeTime(randotron.nextInstant(), Duration.ZERO);
        timeManager = new SimulatorTimeManager(time, GRANULARITY);
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

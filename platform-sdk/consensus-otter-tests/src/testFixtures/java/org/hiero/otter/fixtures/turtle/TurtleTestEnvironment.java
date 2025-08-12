// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer.registerMerkleStateRootClassIds;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.utility.RuntimeObjectRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.otter.fixtures.Capability;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.logging.internal.InMemorySubscriptionManager;

/**
 * A test environment for the Turtle framework.
 *
 * <p>This class implements the {@link TestEnvironment} interface and provides methods to access the
 * network, time manager, etc. for tests running on the Turtle framework.
 */
public class TurtleTestEnvironment implements TestEnvironment {

    private static final Logger log = LogManager.getLogger(TurtleTestEnvironment.class);

    static final Duration GRANULARITY = Duration.ofMillis(10);
    static final Duration AVERAGE_NETWORK_DELAY = Duration.ofMillis(200);
    static final Duration STANDARD_DEVIATION_NETWORK_DELAY = Duration.ofMillis(10);

    private final TurtleNetwork network;
    private final TurtleTransactionGenerator transactionGenerator;
    private final TurtleTimeManager timeManager;

    /**
     * Constructor for the {@link TurtleTestEnvironment} class.
     *
     * @param randomSeed the seed for the PRNG; if {@code 0}, a random seed will be generated
     */
    public TurtleTestEnvironment(final long randomSeed) {
        final Path rootOutputDirectory = Path.of("build", "turtle");
        try {
            if (Files.exists(rootOutputDirectory)) {
                FileUtils.deleteDirectory(rootOutputDirectory);
            }
            Files.createDirectories(rootOutputDirectory);
        } catch (final IOException ex) {
            log.warn("Failed to delete directory: {}", rootOutputDirectory, ex);
        }

        final TurtleLogging logging = new TurtleLogging(rootOutputDirectory);

        final Randotron randotron = randomSeed == 0L ? Randotron.create() : Randotron.create(randomSeed);

        final FakeTime time = new FakeTime(randotron.nextInstant(), Duration.ZERO);

        RuntimeObjectRegistry.reset();
        RuntimeObjectRegistry.initialize(time);

        try {
            final ConstructableRegistry registry = ConstructableRegistry.getInstance();
            registry.reset();
            registry.registerConstructables("");
            registerMerkleStateRootClassIds();
        } catch (final ConstructableRegistryException e) {
            throw new RuntimeException(e);
        }

        timeManager = new TurtleTimeManager(time, GRANULARITY);

        transactionGenerator = new TurtleTransactionGenerator(randotron);
        network = new TurtleNetwork(randotron, timeManager, logging, rootOutputDirectory, transactionGenerator);

        timeManager.addTimeTickReceiver(network);
    }

    /**
     * Checks if the Turtle test environment supports the given capabilities.
     *
     * @param requiredCapabilities the list of capabilities required by the test
     * @return {@code true} if the Turtle test environment supports the required capabilities, {@code false} otherwise
     */
    public static boolean supports(@NonNull final List<Capability> requiredCapabilities) {
        return requiredCapabilities.isEmpty();
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
    public void destroy() throws InterruptedException {
        InMemorySubscriptionManager.INSTANCE.reset();
        network.destroy();
        ConstructableRegistry.getInstance().reset();
        RuntimeObjectRegistry.reset();
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static java.util.Collections.unmodifiableSet;
import static org.hiero.otter.fixtures.util.EnvironmentUtils.getDefaultOutputDirectory;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.utility.RuntimeObjectRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.consensus.test.fixtures.Randotron;
import org.hiero.otter.fixtures.Capability;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.chaosbot.ChaosBot;
import org.hiero.otter.fixtures.chaosbot.ChaosBotConfiguration;
import org.hiero.otter.fixtures.logging.internal.InMemorySubscriptionManager;
import org.hiero.otter.fixtures.turtle.logging.TurtleLogClock;
import org.hiero.otter.fixtures.turtle.logging.TurtleLogging;

/**
 * A test environment for the Turtle framework.
 *
 * <p>This class implements the {@link TestEnvironment} interface and provides methods to access the
 * network, time manager, etc. for tests running on the Turtle framework.
 */
public class TurtleTestEnvironment implements TestEnvironment {

    static {
        // Set custom clock property BEFORE any Log4j2 initialization
        // This ensures the TurtleClock is used for all log timestamps
        System.setProperty("log4j.Clock", TurtleLogClock.class.getName());
    }

    private static final Logger log = LogManager.getLogger(TurtleTestEnvironment.class);

    private static final String ENV_NAME = "turtle";

    /** Capabilities supported by the Turtle test environment */
    private static final Set<Capability> CAPABILITIES = unmodifiableSet(EnumSet.of(Capability.DETERMINISTIC_EXECUTION));

    static final Duration GRANULARITY = Duration.ofMillis(10);

    private final Path rootOutputDirectory;
    private final TurtleNetwork network;
    private final TurtleTransactionGenerator transactionGenerator;
    private final TurtleTimeManager timeManager;

    /**
     * Constructor with default values for using a random seed and random node-ids
     */
    public TurtleTestEnvironment() {
        this(0L, true, getDefaultOutputDirectory(ENV_NAME));
    }

    /**
     * Constructor for the {@link TurtleTestEnvironment} class.
     *
     * @param randomSeed the seed for the PRNG; if {@code 0}, a random seed will be generated
     * @param useRandomNodeIds {@code true} if the node IDs should be selected randomly; {@code false} otherwise
     */
    public TurtleTestEnvironment(final long randomSeed, final boolean useRandomNodeIds) {
        this(randomSeed, useRandomNodeIds, getDefaultOutputDirectory(ENV_NAME));
    }

    /**
     * Constructor for the {@link TurtleTestEnvironment} class.
     *
     * @param randomSeed the seed for the PRNG; if {@code 0}, a random seed will be generated
     * @param useRandomNodeIds {@code true} if the node IDs should be selected randomly; {@code false} otherwise
     * @param rootOutputDirectory the root output directory for Turtle logs
     */
    public TurtleTestEnvironment(
            final long randomSeed, final boolean useRandomNodeIds, final Path rootOutputDirectory) {

        this.rootOutputDirectory = rootOutputDirectory;

        try {
            if (Files.exists(rootOutputDirectory)) {
                FileUtils.deleteDirectory(rootOutputDirectory);
            }
            Files.createDirectories(rootOutputDirectory);
        } catch (final IOException ex) {
            log.warn("Failed to delete directory: {}", rootOutputDirectory, ex);
        }

        final Randotron randotron = randomSeed == 0L ? Randotron.create() : Randotron.create(randomSeed);

        final FakeTime time = new FakeTime(randotron.nextInstant(), Duration.ZERO);

        // Set the fake time for turtle nodes BEFORE configuring logging
        TurtleLogClock.setFakeTime(time);

        final TurtleLogging logging = new TurtleLogging(rootOutputDirectory);

        RuntimeObjectRegistry.reset();
        RuntimeObjectRegistry.initialize(time);

        try {
            final ConstructableRegistry registry = ConstructableRegistry.getInstance();
            registry.reset();
            registry.registerConstructables("");
        } catch (final ConstructableRegistryException e) {
            throw new RuntimeException(e);
        }

        timeManager = new TurtleTimeManager(time, GRANULARITY);

        transactionGenerator = new TurtleTransactionGenerator(randotron);
        network = new TurtleNetwork(
                randotron, timeManager, logging, rootOutputDirectory, transactionGenerator, useRandomNodeIds);

        timeManager.addTimeTickReceiver(network);
    }

    /**
     * Checks if the Turtle test environment supports the given capabilities.
     *
     * @param requiredCapabilities the list of capabilities required by the test
     * @return {@code true} if the Turtle test environment supports the required capabilities, {@code false} otherwise
     */
    public static boolean supports(@NonNull final List<Capability> requiredCapabilities) {
        return CAPABILITIES.containsAll(requiredCapabilities);
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
        throw new UnsupportedOperationException("ChaosBot is not supported in TurtleTestEnvironment");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Path outputDirectory() {
        return rootOutputDirectory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() {
        InMemorySubscriptionManager.INSTANCE.reset();
        network.destroy();
        ConstructableRegistry.getInstance().reset();
        RuntimeObjectRegistry.reset();
    }
}

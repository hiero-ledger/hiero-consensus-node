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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionGenerator;

/**
 * A test environment for the Turtle framework.
 *
 * <p>This class implements the {@link TestEnvironment} interface and provides methods to access the
 * network, time manager, etc. for tests running on the Turtle framework.
 */
public class TurtleTestEnvironment implements TestEnvironment {

    private static final Logger log = LogManager.getLogger(TurtleTestEnvironment.class);

    static final String APP_NAME = "otter";
    static final String SWIRLD_NAME = "123";

    static final Duration GRANULARITY = Duration.ofMillis(10);
    static final Duration AVERAGE_NETWORK_DELAY = Duration.ofMillis(200);
    static final Duration STANDARD_DEVIATION_NETWORK_DELAY = Duration.ofMillis(10);

    private final TurtleNetwork network;
    private final TurtleTransactionGenerator generator;
    private final TurtleTimeManager timeManager;

    /**
     * Constructor for the {@link TurtleTestEnvironment} class.
     */
    public TurtleTestEnvironment() {
        final Path rootOutputDirectory = Path.of("build", "turtle");
        try {
            if (Files.exists(rootOutputDirectory)) {
                FileUtils.deleteDirectory(rootOutputDirectory);
            }
            Files.createDirectories(rootOutputDirectory);
        } catch (IOException ex) {
            log.warn("Failed to delete directory: {}", rootOutputDirectory, ex);
        }

        final TurtleLogging logging = new TurtleLogging(rootOutputDirectory);

        final Randotron randotron = Randotron.create();

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

        network = new TurtleNetwork(randotron, timeManager, logging, rootOutputDirectory);

        generator = new TurtleTransactionGenerator(network, randotron);

        timeManager.addTimeTickReceiver(network);
        timeManager.addTimeTickReceiver(generator);
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
    public TransactionGenerator generator() {
        return generator;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() throws InterruptedException {
        generator.stop();
        network.destroy();
        ConstructableRegistry.getInstance().reset();
        RuntimeObjectRegistry.reset();
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import com.swirlds.common.io.utility.FileUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.otter.fixtures.Capability;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.internal.RegularTimeManager;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;

/**
 * Implementation of {@link TestEnvironment} for tests running on a container network.
 */
public class ContainerTestEnvironment implements TestEnvironment {

    public static final Set<Capability> CAPABILITIES = Set.of(Capability.RECONNECT);
    private static final Logger log = LogManager.getLogger(ContainerTestEnvironment.class);

    private final ContainerNetwork network;
    private final RegularTimeManager timeManager = new RegularTimeManager();
    private final ContainerTransactionGenerator transactionGenerator = new ContainerTransactionGenerator();

    /**
     * Constructor for the {@link ContainerTestEnvironment} class.
     */
    public ContainerTestEnvironment() {
        final Path rootOutputDirectory = Path.of("build", "container");
        try {
            if (Files.exists(rootOutputDirectory)) {
                FileUtils.deleteDirectory(rootOutputDirectory);
            }
            Files.createDirectories(rootOutputDirectory);
        } catch (final IOException ex) {
            log.warn("Failed to delete directory: {}", rootOutputDirectory, ex);
        }
        network = new ContainerNetwork(timeManager, transactionGenerator, rootOutputDirectory);
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
        network.destroy();
    }
}

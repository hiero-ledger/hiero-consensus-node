// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static org.assertj.core.api.Fail.fail;

import com.swirlds.common.io.utility.FileUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.hiero.otter.fixtures.Capability;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.internal.RegularTimeManager;

/**
 * Implementation of {@link TestEnvironment} for tests running on a container network.
 */
public class ContainerTestEnvironment implements TestEnvironment {

    private static final Set<Capability> CAPABILITIES = Set.of(Capability.RECONNECT, Capability.BACK_PRESSURE);

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
            fail("Failed to delete directory: {}", rootOutputDirectory, ex);
        }
        network = new ContainerNetwork(timeManager, transactionGenerator, rootOutputDirectory);
    }

    /**
     * Checks if the container test environment supports the given capabilities.
     *
     * @param requiredCapabilities the list of capabilities required by the test
     * @return {@code true} if the container test environment supports the required capabilities, {@code false} otherwise
     */
    public static boolean supports(@NonNull final List<Capability> requiredCapabilities) {
        return CAPABILITIES.containsAll(requiredCapabilities);
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
    public void destroy() throws InterruptedException, IOException {
        network.destroy();
    }
}

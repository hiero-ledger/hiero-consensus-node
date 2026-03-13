// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.container;

import static java.util.Collections.unmodifiableSet;
import static org.assertj.core.api.Fail.fail;
import static org.hiero.sloth.fixtures.util.EnvironmentUtils.getDefaultOutputDirectory;

import com.swirlds.common.io.utility.FileUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.hiero.sloth.fixtures.Capability;
import org.hiero.sloth.fixtures.Network;
import org.hiero.sloth.fixtures.TestEnvironment;
import org.hiero.sloth.fixtures.TimeManager;
import org.hiero.sloth.fixtures.TransactionGenerator;
import org.hiero.sloth.fixtures.internal.RegularTimeManager;

/**
 * Implementation of {@link TestEnvironment} for tests running on a container network.
 */
public class ContainerTestEnvironment implements TestEnvironment {

    private static final String ENV_NAME = "container";

    /** Capabilities supported by the container test environment */
    private static final Set<Capability> CAPABILITIES = unmodifiableSet(EnumSet.of(
            Capability.RECONNECT,
            Capability.BACK_PRESSURE,
            Capability.SINGLE_NODE_JVM_SHUTDOWN,
            Capability.USES_REAL_NETWORK));

    /** The granularity of time defining how often continuous assertions are checked */
    private static final Duration GRANULARITY = Duration.ofMillis(10);

    private final Path rootOutputDirectory;
    private final ContainerNetwork network;
    private final RegularTimeManager timeManager = new RegularTimeManager(GRANULARITY);
    private final ContainerTransactionGenerator transactionGenerator = new ContainerTransactionGenerator();

    /**
     * Constructor with default values for using random node-ids and default directory for container logs.
     */
    public ContainerTestEnvironment() {
        this(true, getDefaultOutputDirectory(ENV_NAME), false, List.of());
    }

    /**
     * Constructor for the {@link ContainerTestEnvironment} class with full configuration.
     *
     * @param useRandomNodeIds    {@code true} if the node IDs should be selected randomly; {@code false} otherwise
     * @param rootOutputDirectory the root directory where container logs will be written per test
     * @param gcLoggingEnabled    {@code true} if GC logging should be enabled for all node processes; {@code false} otherwise
     * @param jvmArgs             additional JVM arguments to pass to all node processes
     */
    public ContainerTestEnvironment(
            final boolean useRandomNodeIds,
            @NonNull final Path rootOutputDirectory,
            final boolean gcLoggingEnabled,
            @NonNull final List<String> jvmArgs) {
        this.rootOutputDirectory = rootOutputDirectory;

        try {
            if (Files.exists(rootOutputDirectory)) {
                FileUtils.deleteDirectory(rootOutputDirectory);
            }
            Files.createDirectories(rootOutputDirectory);
        } catch (final IOException ex) {
            fail("Failed to prepare directory: " + rootOutputDirectory, ex);
        }

        network = new ContainerNetwork(
                timeManager, transactionGenerator, rootOutputDirectory, useRandomNodeIds, gcLoggingEnabled, jvmArgs);
    }

    /**
     * Checks if the container test environment supports the given capabilities.
     *
     * @param requiredCapabilities the list of capabilities required by the test
     * @return {@code true} if the container test environment supports the required capabilities, {@code false}
     * otherwise
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
    public void destroy() {
        network.destroy();
    }
}

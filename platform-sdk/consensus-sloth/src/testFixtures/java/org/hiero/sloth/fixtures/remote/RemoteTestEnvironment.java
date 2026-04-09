// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.remote;

import static java.util.Collections.unmodifiableSet;
import static org.assertj.core.api.Fail.fail;

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
import org.hiero.sloth.fixtures.internal.RegularTimeManager;

/**
 * Implementation of {@link TestEnvironment} for tests running on remote machines via SSH.
 */
public class RemoteTestEnvironment implements TestEnvironment {

    /** Capabilities supported by the remote test environment */
    private static final Set<Capability> CAPABILITIES = unmodifiableSet(EnumSet.of(
            Capability.RECONNECT,
            Capability.BACK_PRESSURE,
            Capability.SINGLE_NODE_JVM_SHUTDOWN,
            Capability.USES_REAL_NETWORK));

    /** The granularity of time defining how often continuous assertions are checked */
    private static final Duration GRANULARITY = Duration.ofMillis(10);

    private final RemoteNetwork network;
    private final RegularTimeManager timeManager = new RegularTimeManager(GRANULARITY);

    /**
     * Constructor for the {@link RemoteTestEnvironment} class.
     *
     * @param useRandomNodeIds whether node IDs should be selected randomly
     * @param rootOutputDirectory the root directory where test output will be written
     * @param hosts the list of SSH host names for remote execution
     * @param remoteWorkDir the base working directory on the remote hosts
     * @param cleanupOnDestroy whether to clean up remote files after test completion
     * @param remoteJavaPath path to the Java executable on remote hosts
     * @param nodesPerHost maximum number of nodes per host
     */
    public RemoteTestEnvironment(
            final boolean useRandomNodeIds,
            @NonNull final Path rootOutputDirectory,
            @NonNull final List<String> hosts,
            @NonNull final String remoteWorkDir,
            final boolean cleanupOnDestroy,
            @NonNull final String remoteJavaPath,
            final int nodesPerHost) {
        try {
            if (Files.exists(rootOutputDirectory)) {
                FileUtils.deleteDirectory(rootOutputDirectory);
            }
            Files.createDirectories(rootOutputDirectory);
        } catch (final IOException ex) {
            fail("Failed to prepare directory: " + rootOutputDirectory, ex);
        }

        network = new RemoteNetwork(
                timeManager,
                rootOutputDirectory,
                useRandomNodeIds,
                hosts,
                remoteWorkDir,
                cleanupOnDestroy,
                remoteJavaPath,
                nodesPerHost);
    }

    /**
     * Checks if the remote test environment supports the given capabilities.
     *
     * @param requiredCapabilities the list of capabilities required by the test
     * @return {@code true} if the remote test environment supports the required capabilities
     */
    public static boolean supports(@NonNull final List<Capability> requiredCapabilities) {
        return CAPABILITIES.containsAll(requiredCapabilities);
    }

    @Override
    @NonNull
    public Network network() {
        return network;
    }

    @Override
    @NonNull
    public TimeManager timeManager() {
        return timeManager;
    }

    @Override
    public void destroy() {
        network.destroy();
    }
}

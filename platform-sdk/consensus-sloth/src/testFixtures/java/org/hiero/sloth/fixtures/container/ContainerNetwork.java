// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.container;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.sloth.fixtures.Node;
import org.hiero.sloth.fixtures.TimeManager;
import org.hiero.sloth.fixtures.internal.AbstractNetwork;
import org.hiero.sloth.fixtures.internal.RegularTimeManager;
import org.hiero.sloth.fixtures.internal.network.ConnectionKey;
import org.hiero.sloth.fixtures.network.Topology.ConnectionState;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * An implementation of {@link org.hiero.sloth.fixtures.Network} for the container environment. This class provides a
 * basic structure for a container network but does not implement all functionalities yet.
 */
public class ContainerNetwork extends AbstractNetwork {

    private static final Logger log = LogManager.getLogger();

    private final Network network = Network.newNetwork();
    private final RegularTimeManager timeManager;
    private final Path rootOutputDirectory;
    private final ImageFromDockerfile dockerImage;
    private final Executor executor = Executors.newCachedThreadPool();

    private final boolean gcLoggingEnabled;
    private final List<String> jvmArgs;

    /**
     * Constructor for {@link ContainerNetwork}.
     *
     * @param timeManager          the time manager to use
     * @param rootOutputDirectory  the root output directory for the network
     * @param useRandomNodeIds     {@code true} if the node IDs should be selected randomly; {@code false} otherwise
     * @param gcLoggingEnabled     {@code true} if GC logging should be enabled for all node processes; {@code false} otherwise
     * @param jvmArgs              additional JVM arguments to pass to all node processes
     */
    public ContainerNetwork(
            @NonNull final RegularTimeManager timeManager,
            @NonNull final Path rootOutputDirectory,
            final boolean useRandomNodeIds,
            final boolean gcLoggingEnabled,
            @NonNull final List<String> jvmArgs) {
        super(new Random(), useRandomNodeIds);
        this.timeManager = requireNonNull(timeManager);
        this.rootOutputDirectory = requireNonNull(rootOutputDirectory);
        this.dockerImage = new ImageFromDockerfile().withDockerfile(Path.of("build", "data", "Dockerfile"));
        this.gcLoggingEnabled = gcLoggingEnabled;
        this.jvmArgs = List.copyOf(requireNonNull(jvmArgs));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected TimeManager timeManager() {
        return timeManager;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onConnectionsChanged(@NonNull final Map<ConnectionKey, ConnectionState> connections) {}

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected ContainerNode doCreateNode(@NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts) {
        final Path outputDir = rootOutputDirectory.resolve(NODE_IDENTIFIER_FORMAT.formatted(nodeId.id()));
        final ContainerNode node = new ContainerNode(
                nodeId,
                timeManager,
                keysAndCerts,
                network,
                dockerImage,
                outputDir,
                networkConfiguration,
                gcLoggingEnabled,
                jvmArgs);
        timeManager.addTimeTickReceiver(node);
        return node;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void preStartHook(@NonNull final Roster roster) {}

    @Override
    protected void doSendQuiescenceCommand(@NonNull final QuiescenceCommand command, @NonNull final Duration timeout) {
        for (final Node node : nodes()) {
            executor.execute(() -> node.withTimeout(timeout).sendQuiescenceCommand(command));
        }
    }

    /**
     * Shuts down the network and cleans up resources. Once this method is called, the network cannot be started again.
     * This method is idempotent and can be called multiple times without any side effects.
     */
    void destroy() {
        log.info("Destroying network...");
        nodes().forEach(node -> ((ContainerNode) node).destroy());
        network.close();
    }
}

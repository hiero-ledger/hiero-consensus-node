// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.RUNNING;
import static org.hiero.otter.fixtures.internal.InstrumentedModules.INSTRUMENTED_EVENT_CREATOR;

import com.swirlds.platform.builder.ModulesConfig_;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.container.proto.InstrumentationPingRequest;
import org.hiero.otter.fixtures.internal.NetworkConfiguration;
import org.hiero.otter.fixtures.internal.result.ConsensusRoundPool;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * An implementation of {@link InstrumentedNode} for a containerized environment.
 */
public class InstrumentedContainerNode extends ContainerNode implements InstrumentedNode {

    private static final Logger log = LogManager.getLogger();

    private static final Duration PING_TIMEOUT = Duration.ofMinutes(1);

    /**
     * Constructor for the {@link InstrumentedContainerNode} class.
     *
     * @param selfId the unique identifier for this node
     * @param timeManager the time manager to use for this node
     * @param keysAndCerts the keys for the node
     * @param network the network this node is part of
     * @param dockerImage the Docker image to use for this node
     * @param outputDirectory the directory where the node's output will be stored
     * @param networkConfiguration the network configuration for this node
     * @param consensusRoundPool the shared pool for deduplicating consensus rounds
     * @param gcLoggingEnabled {@code true} if GC logging should be enabled for the node process
     * @param jvmArgs additional JVM arguments to pass to the node process
     */
    public InstrumentedContainerNode(
            @NonNull final NodeId selfId,
            @NonNull final TimeManager timeManager,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final Network network,
            @NonNull final ImageFromDockerfile dockerImage,
            @NonNull final Path outputDirectory,
            @NonNull final NetworkConfiguration networkConfiguration,
            @NonNull final ConsensusRoundPool consensusRoundPool,
            final boolean gcLoggingEnabled,
            @NonNull final List<String> jvmArgs) {
        super(
                selfId,
                timeManager,
                keysAndCerts,
                network,
                dockerImage,
                outputDirectory,
                networkConfiguration,
                consensusRoundPool,
                gcLoggingEnabled,
                jvmArgs);
        configuration().withConfigValue(ModulesConfig_.EVENT_CREATOR, INSTRUMENTED_EVENT_CREATOR);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void ping(@NonNull final String message) {
        throwIsNotInLifecycle(RUNNING, "Cannot ping a node that is not running");
        log.info("Sending instrumentation ping '{}' to node {}", message, selfId);
        nodeCommBlockingStub
                .withDeadlineAfter(PING_TIMEOUT)
                .instrumentationPing(InstrumentationPingRequest.newBuilder()
                        .setMessage(message)
                        .build());
    }
}

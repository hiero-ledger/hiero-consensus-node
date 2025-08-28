// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static org.hiero.otter.fixtures.container.ContainerNetwork.NODE_IDENTIFIER_FORMAT;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.CONTAINER_APP_WORKING_DIR;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.CONTAINER_CONTROL_PORT;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.getContainerControlDebugPort;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.getJavaToolOptions;

import com.hedera.hapi.platform.state.NodeId;
import com.hedera.node.config.data.GrpcConfig;
import com.swirlds.common.metrics.platform.prometheus.PrometheusConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * A small convenience wrapper around {@link GenericContainer} that applies common configuration for Otter test node
 * containers. It connects the container to the provided Docker {@link Network}.
 */
public class HederaAppContainer extends GenericContainer<HederaAppContainer> {

    /**
     * Constructs a new container instance and exposed the debug port as {@code 5005 + selfId}.
     *
     * @param dockerImage the Docker image to run
     * @param network the Docker network to attach the container to
     * @param selfId the selfId for the node
     * @param configuration the configuration to extract ports from
     */
    public HederaAppContainer(
            @NonNull final ImageFromDockerfile dockerImage,
            @NonNull final Network network,
            @NonNull final NodeId selfId,
            @NonNull final Configuration configuration) {
        super(dockerImage);

        final String alias = String.format(NODE_IDENTIFIER_FORMAT, selfId.id());
        final int containerControlDebugPort = getContainerControlDebugPort(selfId);

        // Apply the common configuration expected by tests.
        // By default, the container wait for all ports listed, but we only want it to wait for the
        // container control port, because the node communication service is established later
        // by the test code with the init request.
        setNetwork(network);
        setNetworkAliases(List.of(alias));

        final GrpcConfig grpcConfig = configuration.getConfigData(GrpcConfig.class);
        final int grpcPort = grpcConfig.port();
        final int grpcTlsPort = grpcConfig.tlsPort();
        final int nodeOperatorPort = grpcConfig.nodeOperatorPort();
        final int workflowsPort = grpcConfig.workflowsPort();
        final int workflowsTlsPort = grpcConfig.workflowsTlsPort();

        final PrometheusConfig prometheusConfig = configuration.getConfigData(PrometheusConfig.class);
        final int prometheusPort = prometheusConfig.endpointPortNumber();

        addExposedPorts(CONTAINER_CONTROL_PORT, grpcPort, grpcTlsPort, nodeOperatorPort, workflowsPort, workflowsTlsPort, prometheusPort);
        this.waitingFor(Wait.forListeningPorts(CONTAINER_CONTROL_PORT, containerControlDebugPort));

        withEnv("JAVA_TOOL_OPTIONS", getJavaToolOptions(containerControlDebugPort));
        addFixedExposedPort(containerControlDebugPort, containerControlDebugPort);

        withWorkingDirectory(CONTAINER_APP_WORKING_DIR);
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.otter;

import static com.hedera.services.bdd.junit.hedera.otter.ConfigTxtGenerator.configTxtForRoster;
import static com.hedera.services.bdd.spec.HapiPropertySource.getConfigRealm;
import static com.hedera.services.bdd.spec.HapiPropertySource.getConfigShard;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.config.data.GrpcConfig;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeMetadata;
import com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils;
import com.swirlds.common.metrics.platform.prometheus.PrometheusConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.container.ContainerNode;
import org.hiero.otter.fixtures.container.ContainerTestEnvironment;
import org.testcontainers.containers.GenericContainer;

/**
 * A factory to create Otter-based Hedera networks.
 */
public class OtterFactory {

    private static final Configuration DEFAULT_CONFIGURATION = ConfigurationBuilder.create()
            .withConfigDataType(GrpcConfig.class)
            .withConfigDataType(PrometheusConfig.class)
            .build();
    private static final Path WORKING_DIR = Path.of("/opt", "DockerApp");
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    private OtterFactory() {}

    /**
     * Creates a new Otter-based Hedera network with the given name and node count.
     *
     * @param networkName the name of the network
     * @param nodeCount the number of nodes in the network
     * @return a new Otter-based Hedera network
     */
    public static OtterContainerNetwork createContainerNetwork(@NonNull final String networkName, final int nodeCount) {
        final ContainerTestEnvironment env = new ContainerTestEnvironment();
        final Network network = env.network();
        final List<ContainerNode> nodes = network.addNodes(nodeCount).stream()
                .map(node -> (ContainerNode) node)
                .toList();
        final Roster roster = network.roster();
        final List<HederaNode> hederaNodes =
                nodes.stream().map(OtterFactory::createContainerNode).toList();
        final String configTxt = configTxtForRoster(networkName, roster);
        return new OtterContainerNetwork(networkName, network, hederaNodes, configTxt);
    }

    private static HederaNode createContainerNode(@NonNull final ContainerNode node) {
        final int id = (int) node.selfId().id();
        final AccountID accountId = AccountID.newBuilder()
                .shardNum(getConfigShard())
                .realmNum(getConfigRealm())
                .accountNum(AddressBookUtils.CLASSIC_FIRST_NODE_ACCOUNT_NUM + id)
                .build();
        final GenericContainer<?> container = node.container();
        final GrpcConfig grpcConfig = DEFAULT_CONFIGURATION.getConfigData(GrpcConfig.class);
        final PrometheusConfig prometheusConfig = DEFAULT_CONFIGURATION.getConfigData(PrometheusConfig.class);
        final NodeMetadata metadata = new NodeMetadata(
                id,
                String.format("node-%d", id),
                accountId,
                container.getHost(),
                container.getMappedPort(grpcConfig.port()),
                container.getMappedPort(grpcConfig.nodeOperatorPort()),
                NodeMetadata.UNKNOWN_PORT,
                NodeMetadata.UNKNOWN_PORT,
                container.getMappedPort(prometheusConfig.endpointPortNumber()),
                WORKING_DIR);
        return new OtterContainerNode(metadata, node, EXECUTOR);
    }
}

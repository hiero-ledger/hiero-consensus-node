// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.otter;

import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.awaitStatus;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;

import com.hedera.services.bdd.junit.hedera.AbstractGrpcNetwork;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.subprocess.PrometheusClient;
import com.hedera.services.bdd.spec.TargetNetworkType;
import com.hedera.services.bdd.spec.infrastructure.HapiClients;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.hiero.otter.fixtures.Network;

/**
 * A Hedera network based on Otter's container environment.
 */
public class OtterContainerNetwork extends AbstractGrpcNetwork implements HederaNetwork {

    private static final PrometheusClient PROMETHEUS_CLIENT = new PrometheusClient();

    private final Network network;
    private final String configTxt;

    /**
     * Constructs a new OtterContainerNetwork.
     *
     * @param networkName the name of the network
     * @param network the Otter network
     * @param nodes the nodes in the network
     * @param configTxt the configuration text for the nodes
     */
    public OtterContainerNetwork(
            @NonNull final String networkName,
            @NonNull final Network network,
            @NonNull final List<HederaNode> nodes,
            @NonNull final String configTxt) {
        super(networkName, nodes);
        this.network = requireNonNull(network);
        this.configTxt = requireNonNull(configTxt);
    }

    @Override
    public TargetNetworkType type() {
        return TargetNetworkType.OTTER_CONTAINER_NETWORK;
    }

    @Override
    public void start() {
        network.start();
        for (final HederaNode node : nodes) {
            node.initWorkingDir(configTxt);
            node.start();
        }
    }

    @Override
    public void terminate() {
        network.shutdown();
        for (final HederaNode node : nodes) {
            node.stopFuture();
        }
    }

    @Override
    public void awaitReady(@NonNull final Duration timeout) {
        final Instant deadline = Instant.now().plus(timeout);
        for (final HederaNode node : nodes) {
            awaitStatus(node, Duration.between(Instant.now(), deadline), ACTIVE);
        }
        if (Instant.now().isAfter(deadline)) {
            throw new RuntimeException("Network was not ready in time");
        }
        this.clients = HapiClients.clientsFor(this);
    }

    @Override
    public PrometheusClient prometheusClient() {
        return PROMETHEUS_CLIENT;
    }
}

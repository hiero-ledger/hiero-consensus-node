// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.remote;

import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.remote.RemoteNetwork;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.infrastructure.HapiClients;
import com.hedera.services.bdd.spec.props.NodeConnectInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

public class RemoteNetworkFactory {
    private static final Logger log = LogManager.getLogger(RemoteNetworkFactory.class);
    private static final int DEFAULT_CLASSIC_NETWORK_SIZE = 4;

    /**
     * Creates a new remote network from the given YAML file.
     * @param remoteNodesYmlLoc the location of the YAML file containing the remote nodes configuration
     * @return a new remote network
     */
    public static HederaNetwork newWithTargetFrom(@NonNull final String remoteNodesYmlLoc) {
        requireNonNull(remoteNodesYmlLoc);
        final RemoteNetworkSpec networkSpec = loadSpec(remoteNodesYmlLoc);
        final var connectInfos = networkSpec.connectInfos();
        // If the spec points to localhost-only nodes and no external network is assumed,
        // stand up a local subprocess network on the configured base port to satisfy
        // "remote" runs without a pre-provisioned node.
        if (isLocalOnly(connectInfos)) {
            final var shared = NetworkTargetingExtension.SHARED_NETWORK.get();
            if (shared instanceof SubProcessNetwork sharedSubprocess) {
                return sharedSubprocess;
            }
            final int size = Math.max(connectInfos.size(), DEFAULT_CLASSIC_NETWORK_SIZE);
            final int firstPort = connectInfos.stream()
                    .mapToInt(NodeConnectInfo::getPort)
                    .min()
                    .orElse(50211);
            SubProcessNetwork.initializeNextPortsForNetwork(size, firstPort);
            final var network = SubProcessNetwork.newSharedNetwork(
                    SubProcessNetwork.SHARED_NETWORK_NAME, size, networkSpec.getShard(), networkSpec.getRealm());
            network.start();
            NetworkTargetingExtension.SHARED_NETWORK.set(network);
            return network;
        }
        return newWithTargetFrom(networkSpec.getShard(), networkSpec.getRealm(), connectInfos);
    }

    public static HederaNetwork newWithTargetFrom(
            final long shard, final long realm, @NonNull final List<NodeConnectInfo> nodeInfos) {
        requireNonNull(nodeInfos);
        return RemoteNetwork.newRemoteNetwork(nodeInfos, new HapiClients(() -> nodeInfos), shard, realm);
    }

    public static RemoteNetworkSpec loadSpec(@NonNull final String remoteNodesYmlLoc) {
        requireNonNull(remoteNodesYmlLoc);
        final var yamlIn = new Yaml(new Constructor(RemoteNetworkSpec.class, new LoaderOptions()));
        try (final var fin = Files.newInputStream(Paths.get(remoteNodesYmlLoc))) {
            final RemoteNetworkSpec networkSpec = yamlIn.load(fin);
            log.info("Loaded remote network spec from {}: {}", remoteNodesYmlLoc, networkSpec);
            return networkSpec;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read remote nodes YAML file: " + remoteNodesYmlLoc, e);
        }
    }

    private static boolean isLocalOnly(@NonNull final List<NodeConnectInfo> connectInfos) {
        return connectInfos.stream().allMatch(info -> {
            final var host = info.getHost().toLowerCase(Locale.ROOT);
            return "localhost".equals(host) || "127.0.0.1".equals(host);
        });
    }
}

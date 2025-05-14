// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.remote;

import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.remote.RemoteNetwork;
import com.hedera.services.bdd.spec.infrastructure.HapiClients;
import com.hedera.services.bdd.spec.props.NodeConnectInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

public class RemoteNetworkFactory {
    private static final Logger log = LogManager.getLogger(RemoteNetworkFactory.class);

    /**
     * Creates a new remote network from the given YAML file.
     * @param remoteNodesYmlLoc the location of the YAML file containing the remote nodes configuration
     * @return a new remote network
     */
    public static HederaNetwork newWithTargetFrom(@NonNull final String remoteNodesYmlLoc) {
        requireNonNull(remoteNodesYmlLoc);
        var yamlIn = new Yaml(new Constructor(RemoteNetworkSpec.class, new LoaderOptions()));
        try (final var fin = Files.newInputStream(Paths.get(remoteNodesYmlLoc))) {
            final RemoteNetworkSpec networkSpec = yamlIn.load(fin);
            log.info("Loaded remote network spec from {}: {}", remoteNodesYmlLoc, networkSpec);
            final var connectInfos = networkSpec.connectInfos();
            return newWithTargetFrom(networkSpec.getShard(), networkSpec.getRealm(), connectInfos);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read remote nodes YAML file: " + remoteNodesYmlLoc, e);
        }
    }

    public static HederaNetwork newWithTargetFrom(
            final long shard, final long realm, @NonNull final List<NodeConnectInfo> nodeInfos) {
        requireNonNull(nodeInfos);
        return RemoteNetwork.newRemoteNetwork(nodeInfos, new HapiClients(() -> nodeInfos), shard, realm);
    }
}

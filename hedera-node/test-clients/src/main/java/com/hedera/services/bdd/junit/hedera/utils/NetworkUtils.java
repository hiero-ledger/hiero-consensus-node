// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.utils;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromByteString;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.gossipCaCertificateForNodeId;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.workingDirFor;
import static com.hedera.services.bdd.suites.utils.sysfiles.BookEntryPojo.asOctets;
import static java.util.Objects.requireNonNull;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.internal.network.Network;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeMetadata;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hederahashgraph.api.proto.java.ServiceEndpoint;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Utility class for generating an address book configuration file.
 */
public class NetworkUtils {
    public static final long CLASSIC_FIRST_NODE_ACCOUNT_NUM = 3;
    public static final String[] CLASSIC_NODE_NAMES =
            new String[] {"node1", "node2", "node3", "node4", "node5", "node6", "node7", "node8"};
    private static final Key CLASSIC_ADMIN_KEY = Key.newBuilder()
            .ed25519(Bytes.fromHex("0aa8e21064c61eab86e2a9c164565b4e7a9a4146106e0a6cd03a8c395a110e92"))
            .build();

    private NetworkUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Creates a network instance from the provided parameters.
     *
     * @param nodes the nodes in the network
     * @param nextInternalGossipPort the next gossip port to use
     * @param nextExternalGossipPort the next gossip TLS port to use
     * @return the contents of the <i>config.txt</i> file
     */
    public static Network generateNetworkConfig(
            @NonNull final List<HederaNode> nodes, final int nextInternalGossipPort, final int nextExternalGossipPort) {
        return generateNetworkConfig(nodes, nextInternalGossipPort, nextExternalGossipPort, Map.of());
    }

    /**
     * Creates a network instance from the provided parameters, with the option to override the
     * weights of the nodes.
     * @param nodes the nodes in the network
     * @param nextInternalGossipPort the next gossip port to use
     * @param nextExternalGossipPort the next gossip TLS port to use
     * @param overrideWeights the map of node IDs to their weights
     * @return the contents of the <i>config.txt</i> file
     */
    public static Network generateNetworkConfig(
            @NonNull final List<HederaNode> nodes,
            final int nextInternalGossipPort,
            final int nextExternalGossipPort,
            @NonNull final Map<Long, Long> overrideWeights) {
        final List<com.hedera.node.internal.network.NodeMetadata> metadata = new ArrayList<>();

        for (final var hNode : nodes) {
            final Bytes localhost = fromByteString(asOctets("127.0.0.1"));
            final Bytes cert = Bytes.wrap(gossipCaCertificateForNodeId(hNode.getNodeId()));
            final var rosterEntry = RosterEntry.newBuilder()
                    .nodeId(hNode.getNodeId())
                    .weight(overrideWeights.getOrDefault(hNode.getNodeId(), 1L))
                    .gossipCaCertificate(cert)
                    .gossipEndpoint(List.of(
                            com.hedera.hapi.node.base.ServiceEndpoint.newBuilder()
                                    .ipAddressV4(localhost)
                                    .port(nextInternalGossipPort + ((int) hNode.getNodeId() * 2))
                                    .build(),
                            com.hedera.hapi.node.base.ServiceEndpoint.newBuilder()
                                    .ipAddressV4(localhost)
                                    .port(nextExternalGossipPort + ((int) hNode.getNodeId() * 2))
                                    .build()))
                    .build();
            final var grpcPort = hNode.getGrpcPort();
            final var serviceEndpoint = grpcPort > 0
                    ? HapiPropertySource.asServiceEndpoint(hNode.getHost() + ":" + grpcPort)
                    : rosterEntry.gossipEndpoint().getFirst();
            final var node = com.hedera.hapi.node.state.addressbook.Node.newBuilder()
                    .nodeId(hNode.getNodeId())
                    .accountId(hNode.getAccountId())
                    .description("node" + (hNode.getNodeId() + 1))
                    .gossipEndpoint(rosterEntry.gossipEndpoint())
                    .serviceEndpoint(serviceEndpoint)
                    .gossipCaCertificate(cert)
                    // The gRPC certificate hash is irrelevant for PR checks
                    .grpcCertificateHash(Bytes.EMPTY)
                    .weight(rosterEntry.weight())
                    .deleted(false)
                    .adminKey(CLASSIC_ADMIN_KEY)
                    .declineReward(false)
                    .build();
            metadata.add(com.hedera.node.internal.network.NodeMetadata.newBuilder()
                    .rosterEntry(rosterEntry)
                    .node(node)
                    .build());
        }
        return Network.newBuilder().ledgerId(Bytes.EMPTY).nodeMetadata(metadata).build();
    }

    /**
     * Returns the "classic" metadata for a node in the network, matching the names
     * used by {@link #generateNetworkConfig(List, int, int)} to generate the
     * <i>config.txt</i> file. The working directory is inferred from the node id
     * and the network scope.
     *
     * @param nodeId the ID of the node
     * @param networkName the name of the network
     * @param scope if non-null, an additional scope to use for the working directory
     * @param nextGrpcPort the next gRPC port to use
     * @param nextGossipPort the next gossip port to use
     * @param nextGossipTlsPort the next gossip TLS port to use
     * @param nextPrometheusPort the next Prometheus port to use
     * @return the metadata for the node
     */
    public static NodeMetadata classicMetadataFor(
            final int nodeId,
            @NonNull final String networkName,
            @NonNull final String host,
            @Nullable String scope,
            final int nextGrpcPort,
            final int nextNodeOperatorPort,
            final int nextGossipPort,
            final int nextGossipTlsPort,
            final int nextPrometheusPort,
            final int nextDebugPort,
            final long shard,
            final long realm) {
        requireNonNull(host);
        requireNonNull(networkName);
        return new NodeMetadata(
                nodeId,
                CLASSIC_NODE_NAMES[nodeId],
                AccountID.newBuilder()
                        .shardNum(shard)
                        .realmNum(realm)
                        .accountNum(CLASSIC_FIRST_NODE_ACCOUNT_NUM + nodeId)
                        .build(),
                host,
                nextGrpcPort + nodeId * 2,
                nextNodeOperatorPort + nodeId,
                nextGossipPort + nodeId * 2,
                nextGossipTlsPort + nodeId * 2,
                nextPrometheusPort + nodeId,
                nextDebugPort + nodeId,
                workingDirFor(nodeId, scope));
    }

    /**
     * Returns the "classic" metadata for a node in the network, matching the names
     * used by {@link #generateNetworkConfig(List, int, int)} to generate the
     * <i>config.txt</i> file.
     *
     * @param nodeId the ID of the node
     * @param networkName the name of the network
     * @param host the host name or IP address
     * @param nextGrpcPort the next gRPC port to use
     * @param nextNodeOperatorPort the next node operator port to use
     * @param nextGossipPort the next gossip port to use
     * @param nextGossipTlsPort the next gossip TLS port to use
     * @param nextPrometheusPort the next Prometheus port to use
     * @param workingDir the working directory for the node
     * @return the metadata for the node
     */
    public static NodeMetadata classicMetadataFor(
            final int nodeId,
            @NonNull final String networkName,
            @NonNull final String host,
            final int nextGrpcPort,
            final int nextNodeOperatorPort,
            final int nextGossipPort,
            final int nextGossipTlsPort,
            final int nextPrometheusPort,
            final int nextDebugPort,
            @NonNull final Path workingDir,
            final long shard,
            final long realm) {
        requireNonNull(host);
        requireNonNull(networkName);
        requireNonNull(workingDir);
        return new NodeMetadata(
                nodeId,
                CLASSIC_NODE_NAMES[nodeId],
                AccountID.newBuilder()
                        .shardNum(shard)
                        .realmNum(realm)
                        .accountNum(CLASSIC_FIRST_NODE_ACCOUNT_NUM + nodeId)
                        .build(),
                host,
                nextGrpcPort + nodeId * 2,
                nextNodeOperatorPort + nodeId,
                nextGossipPort + nodeId * 2,
                nextGossipTlsPort + nodeId * 2,
                nextPrometheusPort + nodeId,
                nextDebugPort + nodeId,
                workingDir);
    }

    /**
     * Returns a stream of numeric node ids from the given roster.
     *
     * @param roster the roster
     * @return the stream of node ids
     */
    public static Stream<Long> nodeIdsFrom(@NonNull final Roster roster) {
        requireNonNull(roster);
        return roster.rosterEntries().stream().map(RosterEntry::nodeId);
    }

    public static RosterEntry entryById(@NonNull final Roster roster, final long nodeId) {
        requireNonNull(roster);
        return roster.rosterEntries().stream()
                .filter(entry -> entry.nodeId() == nodeId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No entry for node" + nodeId));
    }

    /**
     * Returns service end point base on the host and port. - used for hapi path for ServiceEndPoint
     *
     * @param host is an ip or domain name, do not pass in an invalid ip such as "130.0.0.1", will set it as domain name otherwise.
     * @param port the port number
     * @return the service endpoint
     */
    public static ServiceEndpoint endpointFor(@NonNull final String host, final int port) {
        final Pattern IPV4_ADDRESS_PATTERN = Pattern.compile("^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.?\\b){4}$");
        final var builder = ServiceEndpoint.newBuilder().setPort(port);
        if (IPV4_ADDRESS_PATTERN.matcher(host).matches()) {
            final var octets = host.split("[.]");
            builder.setIpAddressV4(ByteString.copyFrom((new byte[] {
                (byte) Integer.parseInt(octets[0]),
                (byte) Integer.parseInt(octets[1]),
                (byte) Integer.parseInt(octets[2]),
                (byte) Integer.parseInt(octets[3])
            })));
        } else {
            builder.setDomainName(host);
        }
        return builder.build();
    }

    /**
     * Returns the classic fee collector account ID for a given node ID.
     *
     * @param nodeId the node ID
     * @return the classic fee collector account ID
     */
    public static long classicFeeCollectorIdFor(final long nodeId) {
        return nodeId + CLASSIC_FIRST_NODE_ACCOUNT_NUM;
    }
}

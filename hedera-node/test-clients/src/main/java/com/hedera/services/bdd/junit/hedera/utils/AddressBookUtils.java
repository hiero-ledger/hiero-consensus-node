// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.utils;

import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.workingDirFor;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeMetadata;
import com.hederahashgraph.api.proto.java.ServiceEndpoint;
import com.swirlds.platform.crypto.CryptoStatic;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.security.cert.CertificateEncodingException;
import java.text.ParseException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.consensus.model.roster.AddressBook;

/**
 * Utility class for generating an address book configuration file.
 */
public class AddressBookUtils {
    public static final long CLASSIC_FIRST_NODE_ACCOUNT_NUM = 3;
    public static final String[] CLASSIC_NODE_NAMES =
            new String[] {"node1", "node2", "node3", "node4", "node5", "node6", "node7", "node8"};

    private AddressBookUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Given a config.txt file, generates the same map of node ids to ASN.1 DER encodings of X.509 certificates
     * as will be produced in a test network.
     * @param configTxt the contents of a config.txt file
     * @return the map of node IDs to their cert encodings
     */
    public static Map<Long, Bytes> certsFor(@NonNull final String configTxt) {
        final AddressBook synthBook;
        try {
            synthBook = com.swirlds.platform.system.address.AddressBookUtils.parseAddressBookText(configTxt);
        } catch (ParseException e) {
            throw new IllegalArgumentException(e);
        }
        try {
            CryptoStatic.generateKeysAndCerts(synthBook);
        } catch (Exception e) {
            throw new IllegalStateException("Error generating keys and certs", e);
        }
        return IntStream.range(0, synthBook.getSize())
                .boxed()
                .collect(toMap(j -> synthBook.getNodeId(j).id(), j -> {
                    try {
                        return Bytes.wrap(requireNonNull(synthBook
                                        .getAddress(synthBook.getNodeId(j))
                                        .getSigCert())
                                .getEncoded());
                    } catch (CertificateEncodingException e) {
                        throw new IllegalStateException(e);
                    }
                }));
    }

    /**
     * Returns the contents of a <i>config.txt</i> file for the given network.
     *
     * @param networkName the name of the network
     * @param nodes the nodes in the network
     * @param nextInternalGossipPort the next gossip port to use
     * @param nextExternalGossipPort the next gossip TLS port to use
     * @return the contents of the <i>config.txt</i> file
     */
    public static String configTxtForLocal(
            @NonNull final String networkName,
            @NonNull final List<HederaNode> nodes,
            final int nextInternalGossipPort,
            final int nextExternalGossipPort) {
        return configTxtForLocal(networkName, nodes, nextInternalGossipPort, nextExternalGossipPort, Map.of());
    }

    /**
     * Returns the contents of a <i>config.txt</i> file for the given network, with the option to override the
     * weights of the nodes.
     * @param networkName the name of the network
     * @param nodes the nodes in the network
     * @param nextInternalGossipPort the next gossip port to use
     * @param nextExternalGossipPort the next gossip TLS port to use
     * @param overrideWeights the map of node IDs to their weights
     * @return the contents of the <i>config.txt</i> file
     */
    public static String configTxtForLocal(
            @NonNull final String networkName,
            @NonNull final List<HederaNode> nodes,
            final int nextInternalGossipPort,
            final int nextExternalGossipPort,
            @NonNull final Map<Long, Long> overrideWeights) {
        final var sb = new StringBuilder();
        sb.append("swirld, ")
                .append(networkName)
                .append("\n")
                .append("\n# This next line is, hopefully, ignored.\n")
                .append("app, HederaNode.jar\n\n#The following nodes make up this network\n");
        var maxNodeId = 0L;
        for (final var node : nodes) {
            final var accountId = node.getAccountId();
            final var fqAccId =
                    String.format("%d.%d.%d", accountId.shardNum(), accountId.realmNum(), accountId.accountNum());
            sb.append("address, ")
                    .append(node.getNodeId())
                    .append(", ")
                    // For now only use the node id as its nickname
                    .append(node.getNodeId())
                    .append(", ")
                    .append(node.getName())
                    .append(", ")
                    .append(overrideWeights.getOrDefault(node.getNodeId(), 1L))
                    .append(", 127.0.0.1, ")
                    .append(nextInternalGossipPort + (node.getNodeId() * 2))
                    .append(", 127.0.0.1, ")
                    .append(nextExternalGossipPort + (node.getNodeId() * 2))
                    .append(", ")
                    .append(fqAccId)
                    .append('\n');
            maxNodeId = Math.max(node.getNodeId(), maxNodeId);
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * Returns the "classic" metadata for a node in the network, matching the names
     * used by {@link #configTxtForLocal(String, List, int, int)} to generate the
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
                workingDirFor(nodeId, scope));
    }

    /**
     * Returns the "classic" metadata for a node in the network, matching the names
     * used by {@link #configTxtForLocal(String, List, int, int)} to generate the
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

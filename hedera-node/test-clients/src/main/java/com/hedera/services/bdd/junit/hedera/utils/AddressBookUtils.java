// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.utils;

import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.workingDirFor;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.CertificateEncodingException;
import java.text.ParseException;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.AddressBook;

/**
 * Utility class for generating an address book configuration file.
 */
public class AddressBookUtils {
    public static final long CLASSIC_FIRST_NODE_ACCOUNT_NUM = 3;
    public static final String[] CLASSIC_NODE_NAMES =
            new String[] {"node1", "node2", "node3", "node4", "node5", "node6", "node7", "node8"};
    private static final String GOSSIP_CERTS_RESOURCE = "hapi-test-gossip-certs.json";
    private static final Map<Long, GossipKeyMaterial> GOSSIP_KEY_MATERIAL =
            new ConcurrentHashMap<>(loadPreGeneratedGossipKeys());
    private static final Map<Long, byte[]> GOSSIP_CERT_CACHE = new ConcurrentHashMap<>();

    static {
        GOSSIP_KEY_MATERIAL.forEach((nodeId, material) -> GOSSIP_CERT_CACHE.put(nodeId, material.sigCertDer()));
    }

    /**
     * Gossip key material pre-generated for test networks.
     */
    static final class GossipKeyMaterial {
        private final byte[] sigCertPem;
        private final byte[] sigPrivateKeyPem;
        private final byte[] sigCertDer;

        GossipKeyMaterial(
                @NonNull final byte[] sigCertPem,
                @NonNull final byte[] sigPrivateKeyPem,
                @NonNull final byte[] sigCertDer) {
            this.sigCertPem = requireNonNull(sigCertPem);
            this.sigPrivateKeyPem = requireNonNull(sigPrivateKeyPem);
            this.sigCertDer = requireNonNull(sigCertDer);
        }

        byte[] sigCertPem() {
            return sigCertPem;
        }

        byte[] sigPrivateKeyPem() {
            return sigPrivateKeyPem;
        }

        byte[] sigCertDer() {
            return sigCertDer;
        }
    }

    private AddressBookUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Given a config.txt file, returns the map of node ids to ASN.1 DER encodings of X.509 certificates
     * pre-generated for test networks.
     *
     * @param configTxt the contents of a config.txt file
     * @return the map of node IDs to their cert encodings
     * @throws IllegalStateException if any node ID is missing from the pre-generated material
     */
    public static Map<Long, Bytes> certsFor(@NonNull final String configTxt) {
        final AddressBook synthBook = synthBookFrom(configTxt);
        final var cachedCerts = cachedCertsFor(synthBook);
        if (cachedCerts != null) {
            return cachedCerts;
        }
        final var missingNodeIds = missingNodeIds(nodeIdsFrom(synthBook));
        throw new IllegalStateException(missingKeyMaterialMessage(missingNodeIds));
    }

    /**
     * Returns a deterministic, unique gossip CA certificate for the given node id.
     *
     * @param nodeId the node id
     * @return the certificate bytes
     * @throws IllegalStateException if the node id has no pre-generated certificate
     */
    public static byte[] gossipCaCertificateForNodeId(final long nodeId) {
        final var cert = GOSSIP_CERT_CACHE.get(nodeId);
        if (cert == null) {
            throw new IllegalStateException(missingKeyMaterialMessage(Set.of(nodeId)));
        }
        return cert;
    }

    /**
     * Returns pre-generated gossip key material for the nodes in a <i>config.txt</i> file.
     *
     * @param configTxt the contents of a <i>config.txt</i> file
     * @return the pre-generated key material for each node
     * @throws IllegalStateException if any node ID is missing from the pre-generated material
     */
    public static Map<Long, GossipKeyMaterial> preGeneratedGossipKeysFor(@NonNull final String configTxt) {
        final var synthBook = synthBookFrom(configTxt);
        return preGeneratedGossipKeysForNodeIds(nodeIdsFrom(synthBook));
    }

    /**
     * Returns pre-generated gossip key material for the given node IDs.
     *
     * @param nodeIds the node IDs to retrieve key material for
     * @return the pre-generated key material
     * @throws IllegalStateException if any node ID is missing from the pre-generated material
     */
    public static Map<Long, GossipKeyMaterial> preGeneratedGossipKeysForNodeIds(@NonNull final Set<Long> nodeIds) {
        final var missingNodeIds = missingNodeIds(nodeIds);
        if (!missingNodeIds.isEmpty()) {
            throw new IllegalStateException(missingKeyMaterialMessage(missingNodeIds));
        }
        final Map<Long, GossipKeyMaterial> material = new HashMap<>();
        for (final var nodeId : nodeIds) {
            material.put(nodeId, GOSSIP_KEY_MATERIAL.get(nodeId));
        }
        return Map.copyOf(material);
    }

    /**
     * Generates JSON-encoded gossip key material for a contiguous range of node IDs.
     *
     * @param firstNodeId the first node id (inclusive)
     * @param lastNodeId the last node id (inclusive)
     * @return JSON payload compatible with {@value #GOSSIP_CERTS_RESOURCE}
     * @throws IllegalArgumentException if the node id range is invalid
     */
    public static String generateGossipKeyMaterialJsonForRange(final long firstNodeId, final long lastNodeId) {
        if (firstNodeId < 0 || lastNodeId < firstNodeId) {
            throw new IllegalArgumentException("Invalid node id range [" + firstNodeId + ", " + lastNodeId + "]");
        }
        final var nodeIds =
                LongStream.rangeClosed(firstNodeId, lastNodeId).boxed().collect(Collectors.toCollection(TreeSet::new));
        return generateGossipKeyMaterialJsonForNodeIds(nodeIds);
    }

    /**
     * Generates JSON-encoded gossip key material for the given node IDs.
     *
     * @param nodeIds the node IDs to generate key material for
     * @return JSON payload compatible with {@value #GOSSIP_CERTS_RESOURCE}
     * @throws IllegalArgumentException if no node IDs are provided
     */
    public static String generateGossipKeyMaterialJsonForNodeIds(@NonNull final Set<Long> nodeIds) {
        requireNonNull(nodeIds);
        if (nodeIds.isEmpty()) {
            throw new IllegalArgumentException("No node IDs provided for gossip key generation");
        }
        final var orderedNodeIds = new TreeSet<>(nodeIds);
        final Map<NodeId, KeysAndCerts> keysAndCerts;
        try {
            final var nodeIdList = orderedNodeIds.stream().map(NodeId::of).toList();
            keysAndCerts = CryptoStatic.generateKeysAndCerts(nodeIdList, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while generating gossip key material", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate gossip key material", e);
        }
        final Map<String, GossipKeyMaterialJson> payload = new TreeMap<>();
        for (final var nodeId : orderedNodeIds) {
            final var entry = keysAndCerts.get(NodeId.of(nodeId));
            if (entry == null) {
                throw new IllegalStateException("Missing generated key material for node " + nodeId);
            }
            final var json = new GossipKeyMaterialJson();
            try {
                json.sigCertPem = Base64.getEncoder()
                        .encodeToString(pemBytes(false, entry.sigCert().getEncoded()));
            } catch (CertificateEncodingException e) {
                throw new IllegalStateException("Unable to encode gossip certificate for node " + nodeId, e);
            }
            json.sigPrivateKeyPem = Base64.getEncoder()
                    .encodeToString(
                            pemBytes(true, entry.sigKeyPair().getPrivate().getEncoded()));
            payload.put(Long.toString(nodeId), json);
        }
        try {
            return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize gossip key material JSON", e);
        }
    }

    private static @Nullable Map<Long, Bytes> cachedCertsFor(@NonNull final AddressBook synthBook) {
        for (int i = 0; i < synthBook.getSize(); i++) {
            if (!GOSSIP_CERT_CACHE.containsKey(synthBook.getNodeId(i).id())) {
                return null;
            }
        }
        return IntStream.range(0, synthBook.getSize())
                .boxed()
                .collect(toMap(
                        j -> synthBook.getNodeId(j).id(),
                        j -> Bytes.wrap(
                                GOSSIP_CERT_CACHE.get(synthBook.getNodeId(j).id()))));
    }

    /**
     * Loads pre-generated gossip key material from the embedded JSON resource.
     *
     * @return the decoded key material
     */
    private static Map<Long, GossipKeyMaterial> loadPreGeneratedGossipKeys() {
        try (InputStream input = AddressBookUtils.class.getClassLoader().getResourceAsStream(GOSSIP_CERTS_RESOURCE)) {
            if (input == null) {
                return Map.of();
            }
            final var mapper = new ObjectMapper();
            final Map<String, GossipKeyMaterialJson> encoded =
                    mapper.readValue(input, new TypeReference<Map<String, GossipKeyMaterialJson>>() {});
            final Map<Long, GossipKeyMaterial> decoded = new HashMap<>();
            for (final var entry : encoded.entrySet()) {
                final long nodeId = Long.parseLong(entry.getKey());
                final var material = requireNonNull(entry.getValue());
                final var sigCertPem = Base64.getDecoder().decode(requireNonNull(material.sigCertPem));
                final var sigPrivateKeyPem = Base64.getDecoder().decode(requireNonNull(material.sigPrivateKeyPem));
                decoded.put(
                        nodeId, new GossipKeyMaterial(sigCertPem, sigPrivateKeyPem, decodeCertificateDer(sigCertPem)));
            }
            return Map.copyOf(decoded);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Failed to load pre-generated gossip key material", e);
        }
    }

    /**
     * Parses a {@link AddressBook} from the given <i>config.txt</i> contents.
     *
     * @param configTxt the <i>config.txt</i> contents
     * @return the parsed address book
     */
    private static AddressBook synthBookFrom(@NonNull final String configTxt) {
        try {
            return com.swirlds.platform.system.address.AddressBookUtils.parseAddressBookText(configTxt);
        } catch (ParseException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Set<Long> nodeIdsFrom(@NonNull final AddressBook synthBook) {
        return IntStream.range(0, synthBook.getSize())
                .mapToObj(i -> synthBook.getNodeId(i).id())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<Long> missingNodeIds(@NonNull final Collection<Long> nodeIds) {
        return nodeIds.stream()
                .filter(nodeId -> !GOSSIP_KEY_MATERIAL.containsKey(nodeId))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String missingKeyMaterialMessage(@NonNull final Set<Long> nodeIds) {
        return "Missing pre-generated gossip key material for node IDs "
                + nodeIds
                + ". Generate additional entries with "
                + AddressBookUtils.class.getSimpleName()
                + ".generateGossipKeyMaterialJsonForRange(...) or "
                + AddressBookUtils.class.getSimpleName()
                + ".generateGossipKeyMaterialJsonForNodeIds(...) and append them to "
                + GOSSIP_CERTS_RESOURCE
                + ".";
    }

    /**
     * Extracts DER-encoded certificate bytes from a PEM-encoded certificate.
     *
     * @param pemBytes the PEM-encoded certificate bytes
     * @return the DER-encoded certificate bytes
     */
    private static byte[] decodeCertificateDer(@NonNull final byte[] pemBytes) {
        final var pem = new String(pemBytes, StandardCharsets.US_ASCII);
        final var header = "-----BEGIN CERTIFICATE-----";
        final var footer = "-----END CERTIFICATE-----";
        final int start = pem.indexOf(header);
        final int end = pem.indexOf(footer);
        if (start == -1 || end == -1 || end <= start) {
            throw new IllegalArgumentException("Invalid PEM certificate content");
        }
        final var base64Body = pem.substring(start + header.length(), end).replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64Body);
    }

    private static byte[] pemBytes(final boolean isPrivateKey, @NonNull final byte[] encoded) {
        final var type = isPrivateKey ? "PRIVATE KEY" : "CERTIFICATE";
        final var encoder = Base64.getMimeEncoder(64, new byte[] {'\n'});
        final var pem = new StringBuilder()
                .append("-----BEGIN ")
                .append(type)
                .append("-----\n")
                .append(encoder.encodeToString(encoded))
                .append("\n-----END ")
                .append(type)
                .append("-----\n");
        return pem.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * JSON payload for pre-generated gossip key material.
     */
    private static final class GossipKeyMaterialJson {
        public String sigCertPem;
        public String sigPrivateKeyPem;
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

// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli;

import static org.hiero.consensus.crypto.KeysAndCertsGenerator.generateKeysAndCerts;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.NodeMetadata;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.crypto.EnhancedKeyStoreLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStoreException;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.hiero.base.crypto.SigningSchema;
import org.hiero.base.utility.CommonUtils;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.node.NodeUtilities;
import picocli.CommandLine;
import picocli.CommandLine.Parameters;

@CommandLine.Command(
        name = "generate-keys",
        mixinStandardHelpOptions = true,
        description = "Generates Node's X.509 certificate and private keys.")
@SubcommandOf(Pcli.class)
public class GenerateKeysCommand extends AbstractCommand {
    /** DER-encoded Ed25519 admin key reused for every generated node (matches the classic test networks). */
    private static final Key CLASSIC_ADMIN_KEY = Key.newBuilder()
            .ed25519(Bytes.fromHex("0aa8e21064c61eab86e2a9c164565b4e7a9a4146106e0a6cd03a8c395a110e92"))
            .build();

    private static final String GENESIS_NETWORK_JSON = "genesis-network.json";
    private static final long FIRST_NODE_ACCOUNT_NUM = 3;
    private static final Bytes LOCALHOST = Bytes.wrap(new byte[] {127, 0, 0, 1});
    private static final int BASE_INTERNAL_GOSSIP_PORT = 50111;
    private static final int BASE_EXTERNAL_GOSSIP_PORT = 50112;

    private Path sigCertPath;
    private Path networkPath;

    @Parameters
    private List<Integer> ids;

    /**
     * The path to place the generated key files.
     */
    @CommandLine.Option(
            names = {"-p", "--path"},
            description = "Path to place the keys (default: data/keys)")
    private void setSigCertPath(final Path sigCertPath) {
        this.sigCertPath = pathMustExist(sigCertPath.toAbsolutePath());
    }

    /**
     * The signing schema used for the node signing keys.
     */
    @CommandLine.Option(
            names = {"-s", "--schema"},
            defaultValue = "RSA",
            description = "Signing schema for the node signing key: ${COMPLETION-CANDIDATES} (default: RSA)")
    private SigningSchema schema;

    /**
     * Whether to also emit a genesis-network.json wiring the generated signing certs as gossip certificates.
     */
    @CommandLine.Option(
            names = {"--emit-network"},
            description = "Also write a " + GENESIS_NETWORK_JSON
                    + " wiring the generated signing certs as gossip certificates")
    private boolean emitNetwork;

    /**
     * The directory for the generated genesis-network.json.
     */
    @CommandLine.Option(
            names = {"-n", "--network-path"},
            description = "Directory for the generated " + GENESIS_NETWORK_JSON + " (default: data/config)")
    private void setNetworkPath(final Path networkPath) {
        this.networkPath = networkPath.toAbsolutePath();
    }

    @Override
    public Integer call()
            throws KeyStoreException, ExecutionException, InterruptedException, IOException,
                    CertificateEncodingException {
        final var nodeIds = ids.stream().map(NodeId::of).toList();
        final var keysEntries = generateKeysAndCerts(nodeIds, schema);
        if (sigCertPath == null) {
            sigCertPath = Path.of(System.getProperty("user.dir")).resolve("data/keys");
            Files.createDirectories(sigCertPath);
        }
        for (var kEntry : keysEntries.entrySet()) {
            var publicKeyStorePath = sigCertPath.resolve(
                    String.format("s-public-%s.pem", NodeUtilities.formatNodeName(kEntry.getKey())));
            var privateKeyStorePath = sigCertPath.resolve(
                    String.format("s-private-%s.pem", NodeUtilities.formatNodeName(kEntry.getKey())));
            EnhancedKeyStoreLoader.writePemFile(
                    true,
                    privateKeyStorePath,
                    kEntry.getValue().sigKeyPair().getPrivate().getEncoded());
            EnhancedKeyStoreLoader.writePemFile(
                    false, publicKeyStorePath, kEntry.getValue().sigCert().getEncoded());
        }
        CommonUtils.tellUserConsole("All " + ids.size() + " " + schema + " keys generated in: " + sigCertPath);

        if (emitNetwork) {
            if (networkPath == null) {
                networkPath = Path.of(System.getProperty("user.dir")).resolve("data/config");
            }
            Files.createDirectories(networkPath);
            final var networkFile = networkPath.resolve(GENESIS_NETWORK_JSON);
            Files.writeString(networkFile, Network.JSON.toJSON(buildNetwork(keysEntries)));
            CommonUtils.tellUserConsole("Wrote genesis network to: " + networkFile);
        }
        return 0;
    }

    /**
     * Builds a genesis {@link Network} from the generated keys, using each node's signing certificate as its gossip CA
     * certificate. Endpoints, weights, and account IDs use simple deterministic defaults suitable for a local network.
     *
     * @param keysEntries the generated keys and certs per node
     * @return the assembled network
     */
    private static Network buildNetwork(final Map<NodeId, KeysAndCerts> keysEntries)
            throws CertificateEncodingException {
        final List<NodeMetadata> metadata = new ArrayList<>();
        final var sortedEntries = keysEntries.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getKey().id()))
                .toList();
        for (final var entry : sortedEntries) {
            final long nodeId = entry.getKey().id();
            final Bytes cert = Bytes.wrap(entry.getValue().sigCert().getEncoded());
            final List<ServiceEndpoint> gossipEndpoints = List.of(
                    ServiceEndpoint.newBuilder()
                            .ipAddressV4(LOCALHOST)
                            .port(BASE_INTERNAL_GOSSIP_PORT + (int) nodeId * 2)
                            .build(),
                    ServiceEndpoint.newBuilder()
                            .ipAddressV4(LOCALHOST)
                            .port(BASE_EXTERNAL_GOSSIP_PORT + (int) nodeId * 2)
                            .build());
            final var rosterEntry = RosterEntry.newBuilder()
                    .nodeId(nodeId)
                    .weight(1L)
                    .gossipCaCertificate(cert)
                    .gossipEndpoint(gossipEndpoints)
                    .build();
            final var node = Node.newBuilder()
                    .nodeId(nodeId)
                    .accountId(AccountID.newBuilder()
                            .shardNum(0)
                            .realmNum(0)
                            .accountNum(FIRST_NODE_ACCOUNT_NUM + nodeId)
                            .build())
                    .description("node" + (nodeId + 1))
                    .gossipEndpoint(gossipEndpoints)
                    .serviceEndpoint(gossipEndpoints.getFirst())
                    .gossipCaCertificate(cert)
                    .grpcCertificateHash(Bytes.EMPTY)
                    .weight(1L)
                    .deleted(false)
                    .adminKey(CLASSIC_ADMIN_KEY)
                    .declineReward(false)
                    .build();
            metadata.add(NodeMetadata.newBuilder()
                    .rosterEntry(rosterEntry)
                    .node(node)
                    .build());
        }
        return Network.newBuilder().ledgerId(Bytes.EMPTY).nodeMetadata(metadata).build();
    }
}

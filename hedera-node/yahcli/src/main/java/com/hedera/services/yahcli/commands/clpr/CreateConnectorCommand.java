// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCreateConnector;
import static com.hedera.services.yahcli.config.ConfigUtils.configFrom;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.services.yahcli.suites.ClprTxnSuite;
import com.hederahashgraph.api.proto.java.Key;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** Creates a channel-scoped connector binding signed by the payer and the connector identity. */
@Command(
        name = "create-connector",
        subcommands = {HelpCommand.class},
        description = "Creates a connector on a channel using its identity bundle and private key.")
public class CreateConnectorCommand implements Callable<Integer> {

    @ParentCommand
    ClprCommand clprCommand;

    @Option(names = "--identity", required = true, description = "JSON produced by generate-connector-identity.")
    Path identityPath;

    @Option(names = "--key-file", description = "Connector private key file. Defaults to <identity>.key.")
    Path keyPath;

    @Option(names = "--channel-id", required = true, description = "Hex-encoded 32-byte channel ID.")
    String channelIdHex;

    @Option(
            names = {"--connector-contract"},
            paramLabel = "<shard.realm.num>",
            required = true,
            description = "Connector contract id (e.g. the PassThroughAuth deployment on this ledger). Required: "
                    + "ClprCreateConnectorHandler.pureChecks rejects transactions missing a connector contract.")
    String connectorContract;

    @Option(
            names = {"--admin-key"},
            paramLabel = "<hex>",
            description = "Hex-encoded public key to set as the connector's admin key. Scheme is inferred from "
                    + "length: 32 bytes = ED25519; 33 bytes = ECDSA_SECP256K1 compressed; 64 bytes = "
                    + "ECDSA_SECP256K1 uncompressed (X||Y, no 0x04 header, compressed on your behalf). "
                    + "Defaults to the identity's public key (so the operator that controls the connector "
                    + "keypair is also its administrator). Mutually exclusive with --admin-key-file.")
    String adminKeyHex;

    @Option(
            names = {"--admin-key-file"},
            paramLabel = "<path>",
            description = "Path to a file containing a serialized proto.Key protobuf for the connector's admin key. "
                    + "Use this for non-ED25519 keys or pre-built proto.Key values; otherwise --admin-key is simpler.")
    String adminKeyFile;

    @Option(
            names = {"--locked-stake"},
            paramLabel = "<tinybars>",
            defaultValue = "0",
            description = "Locked stake (tinybars) for the connector")
    long lockedStake;

    @Override
    public Integer call() throws Exception {
        final var config = configFrom(clprCommand.getYahcli());

        final var bundle = ClprArgs.readFlatJsonStringFields(identityPath);
        final var scheme =
                GenerateConnectorIdentityCommand.ClprIdentityCrypto.normalizeScheme(bundle.get("signatureScheme"));
        final var key = readIdentityKey(keyPath != null ? keyPath : Path.of(identityPath + ".key"));
        final var op = clprCreateConnector()
                .connectorId(ClprArgs.requiredBytes("connector-id", bundle.get("connectorId")))
                .salt(ClprArgs.requiredBytes("salt", bundle.get("salt")))
                .channelId(ClprArgs.requiredBytes("channel-id", channelIdHex))
                .connectorContractId(ClprArgs.parseContractId(connectorContract))
                .lockedStake(lockedStake);
        if ("ED25519".equals(scheme)) {
            op.ed25519Identity(key);
        } else {
            op.secp256k1Identity(key);
        }
        final var publicKey = buildAdminKey(ClprArgs.requiredBytes("public-key", bundle.get("publicKey")));
        if (!publicKey.equals(op.identityKey())) {
            throw new IllegalArgumentException("The private key does not match the public key in the identity bundle");
        }
        final var adminKeyHexProvided = adminKeyHex != null && !adminKeyHex.isBlank();
        final var adminKeyFileProvided = adminKeyFile != null && !adminKeyFile.isBlank();
        if (adminKeyHexProvided && adminKeyFileProvided) {
            throw new IllegalArgumentException("Cannot specify both --admin-key and --admin-key-file; pick one.");
        }
        if (adminKeyHexProvided) {
            op.adminKey(buildAdminKey(ClprArgs.parseHex(adminKeyHex)));
        } else if (adminKeyFileProvided) {
            final var keyBytes = ClprArgs.readBytesFile(Path.of(adminKeyFile));
            op.adminKey(Key.parseFrom(keyBytes));
        } else {
            op.adminKey(publicKey);
        }

        final var delegate = new ClprTxnSuite(config, "ClprCreateConnector", op);
        delegate.runSuiteSync();
        return ClprOutcome.reportTxn(config, delegate, op, "created CLPR connector", "could not create CLPR connector");
    }

    static byte[] readIdentityKey(final Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Expected a regular connector key file at " + path);
        }
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            final var hex = new String(input.readNBytes(70), StandardCharsets.US_ASCII).trim();
            if (input.read() != -1 || !hex.matches("(?:0[xX])?[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("Expected a 32-byte hex-encoded connector private key");
            }
            return ClprArgs.parseHex(hex);
        }
    }

    /**
     * Builds a {@code proto.Key} from raw admin-key bytes, inferring the scheme from length:
     * 32 → ED25519, 33 → ECDSA_SECP256K1 compressed (used directly), 64 → ECDSA_SECP256K1
     * uncompressed (X||Y, no 0x04 header — compressed on the user's behalf).
     */
    private static Key buildAdminKey(final byte[] keyBytes) {
        return switch (keyBytes.length) {
            case 32 ->
                Key.newBuilder().setEd25519(ByteString.copyFrom(keyBytes)).build();
            case 33 ->
                Key.newBuilder()
                        .setECDSASecp256K1(ByteString.copyFrom(keyBytes))
                        .build();
            case 64 ->
                Key.newBuilder()
                        .setECDSASecp256K1(ByteString.copyFrom(MiscCryptoUtils.compressSecp256k1(keyBytes)))
                        .build();
            default ->
                throw new IllegalArgumentException(
                        "--admin-key must be 32 (ED25519), 33 (ECDSA compressed), or 64 (ECDSA uncompressed) bytes; got "
                                + keyBytes.length);
        };
    }
}

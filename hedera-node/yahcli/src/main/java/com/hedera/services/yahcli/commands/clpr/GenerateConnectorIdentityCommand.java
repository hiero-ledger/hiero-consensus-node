// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.hapi.utils.SignatureGenerator;
import com.hedera.services.yahcli.commands.clpr.GenerateChannelIdentityCommand.ClprIdentityCrypto;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Generates a fresh CLPR connector identity bundle: a random salt, a fresh keypair,
 * the derived connector id, the matching ownership commitment, and a pre-computed
 * reveal signature.
 *
 * <p>Output is a JSON object with the fields needed to invoke {@code register-connector}
 * (use {@code commitment}) and later {@code complete-connector}
 * (use {@code channelId}, {@code connectorId}, {@code publicKey}, {@code signature},
 * {@code signatureScheme}, {@code salt}).
 *
 * <p>The private key is <em>not</em> part of the JSON bundle. It is written to a companion
 * {@code <out>.key} file (owner-only, 0600) so the operator can sign future connector messages.
 * Treat it as a secret. For ED25519 it is the 32-byte seed; for ECDSA_SECP256K1 it is
 * the 32-byte secp256k1 scalar, hex-encoded with a {@code 0x} prefix. Existing files at either
 * path are never overwritten; they are renamed to {@code <name>.<epochMillis>.bak} first.
 */
@Command(
        name = "generate-connector-identity",
        subcommands = {HelpCommand.class},
        description = "Generates a fresh salt, keypair, derived connectorId, ownership commitment, "
                + "and reveal signature for use with register-connector / complete-connector. "
                + "Requires --channel-id (the channel this connector will serve).")
public class GenerateConnectorIdentityCommand implements Callable<Integer> {

    private static final int SALT_LEN = 32;

    /**
     * Default 20-byte CLPR service address used by the connector completion handler:
     * {@code 0x000000000000000000000000000000000000016e}. Matches
     * {@code ClprServiceConstants.CLPR_EVM_ADDRESS_BYTES}.
     */
    private static final byte[] DEFAULT_SERVICE_ADDRESS = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, (byte) 0x6e
    };

    @ParentCommand
    ClprCommand clprCommand;

    @Option(
            names = {"--channel-id"},
            paramLabel = "<hex>",
            required = true,
            description = "Hex-encoded channel id (32 bytes) this connector will serve.")
    String channelIdHex;

    @Option(
            names = {"--signature-scheme"},
            paramLabel = "<scheme>",
            defaultValue = "ED25519",
            description = "One of: ED25519, ECDSA_SECP256K1. Defaults to ED25519.")
    String signatureScheme;

    @Option(
            names = {"--service-address"},
            paramLabel = "<hex>",
            description = "Optional hex-encoded CLPR service address used in the signature message. "
                    + "Defaults to the handler's hard-coded 20-byte address (0x...16e). "
                    + "Provide explicitly only if your network's handler reads serviceAddress from ledger config.")
    String serviceAddressHex;

    @Option(
            names = {"--out"},
            paramLabel = "<path>",
            description = "Required. Path to write the JSON bundle to; the private key is written to <path>.key.")
    String outFile;

    @Override
    public Integer call() throws Exception {
        final var scheme = ClprIdentityCrypto.normalizeScheme(signatureScheme);
        final byte[] channelId = ClprArgs.requiredBytes("channel-id", channelIdHex);
        if (channelId.length != 32) {
            throw new IllegalArgumentException("--channel-id must be 32 bytes (got " + channelId.length + ")");
        }
        final byte[] serviceAddress = (serviceAddressHex == null || serviceAddressHex.isBlank())
                ? DEFAULT_SERVICE_ADDRESS
                : ClprArgs.parseHex(serviceAddressHex);

        final var rng = new SecureRandom();

        // 1. Random 32-byte salt.
        final byte[] salt = new byte[SALT_LEN];
        rng.nextBytes(salt);

        // 2. Fresh keypair for the chosen scheme.
        final var keys = ClprIdentityCrypto.generate(scheme, rng);
        final byte[] publicKey = keys.publicKey();

        // 3. connector_id = keccak256(channel_id || public_key || salt)
        final byte[] connectorIdPreimage = new byte[channelId.length + publicKey.length + salt.length];
        System.arraycopy(channelId, 0, connectorIdPreimage, 0, channelId.length);
        System.arraycopy(publicKey, 0, connectorIdPreimage, channelId.length, publicKey.length);
        System.arraycopy(salt, 0, connectorIdPreimage, channelId.length + publicKey.length, salt.length);
        final byte[] connectorId = MiscCryptoUtils.keccak256DigestOf(connectorIdPreimage);

        // 4. commitment = keccak256(connector_id || public_key)
        final byte[] commitPreimage = new byte[connectorId.length + publicKey.length];
        System.arraycopy(connectorId, 0, commitPreimage, 0, connectorId.length);
        System.arraycopy(publicKey, 0, commitPreimage, connectorId.length, publicKey.length);
        final byte[] commitment = MiscCryptoUtils.keccak256DigestOf(commitPreimage);

        // 5. reveal_signature = sign( keccak256(connector_id || service_address) ) with the private key.
        final byte[] sigPreimage = new byte[connectorId.length + serviceAddress.length];
        System.arraycopy(connectorId, 0, sigPreimage, 0, connectorId.length);
        System.arraycopy(serviceAddress, 0, sigPreimage, connectorId.length, serviceAddress.length);
        final byte[] sigMsgHash = MiscCryptoUtils.keccak256DigestOf(sigPreimage);
        final byte[] signature = SignatureGenerator.signBytes(sigMsgHash, keys.privateKey());

        if (outFile == null || outFile.isBlank()) {
            throw new IllegalArgumentException("--out is required; the private key must be stored in a file");
        }

        // 6. Emit JSON. privateKey is intentionally absent — written to the companion .key file.
        final var hex = HexFormat.of();
        final var json = """
                {
                  "channelId":    "0x%s",
                  "connectorId":     "0x%s",
                  "publicKey":       "0x%s",
                  "salt":            "0x%s",
                  "signatureScheme": "%s",
                  "serviceAddress":  "0x%s",
                  "commitment":      "0x%s",
                  "signature":       "0x%s"
                }
                """.formatted(
                        hex.formatHex(channelId),
                        hex.formatHex(connectorId),
                        hex.formatHex(publicKey),
                        hex.formatHex(salt),
                        scheme,
                        hex.formatHex(serviceAddress),
                        hex.formatHex(commitment),
                        hex.formatHex(signature));

        final var bundlePath = Path.of(outFile);
        final var keyPath = Path.of(outFile + ".key");
        ClprArgs.writeBundleAndKey(bundlePath, json, keyPath, "0x" + hex.formatHex(keys.privateKeyBytes()) + "\n");
        System.out.println("Wrote connector identity bundle to " + outFile);
        System.out.println("Wrote private key to " + keyPath + " — keep it secret");
        return 0;
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.hapi.utils.keys.Ed25519Utils;
import com.hedera.node.app.hapi.utils.keys.Secp256k1Utils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** Generates a reusable connector identity and writes its private key to an owner-only companion file. */
@Command(
        name = "generate-connector-identity",
        subcommands = {HelpCommand.class},
        description = "Generates a key pair, salt, and connector ID for create-connector.")
public class GenerateConnectorIdentityCommand implements Callable<Integer> {

    private static final int SALT_LEN = 32;

    @ParentCommand
    ClprCommand clprCommand;

    @Option(
            names = {"--signature-scheme"},
            paramLabel = "<scheme>",
            defaultValue = "ED25519",
            description = "One of: ED25519, ECDSA_SECP256K1. Defaults to ED25519.")
    String signatureScheme;

    @Option(
            names = {"--out"},
            paramLabel = "<path>",
            description = "Required. Path to write the JSON bundle to; the private key is written to <path>.key.")
    String outFile;

    @Override
    public Integer call() throws Exception {
        final var scheme = ClprIdentityCrypto.normalizeScheme(signatureScheme);
        final var rng = new SecureRandom();

        final byte[] salt = new byte[SALT_LEN];
        rng.nextBytes(salt);

        final var keys = ClprIdentityCrypto.generate(scheme, rng);
        final byte[] publicKey = keys.publicKey();

        if (outFile == null || outFile.isBlank()) {
            throw new IllegalArgumentException("--out is required; the private key must be stored in a file");
        }

        final var connectorId = MiscCryptoUtils.keccak256DigestOf(Bytes.merge(Bytes.wrap(publicKey), Bytes.wrap(salt)));
        final var hex = HexFormat.of();
        final var json = """
                {
                  "connectorId":     "0x%s",
                  "publicKey":       "0x%s",
                  "salt":            "0x%s",
                  "signatureScheme": "%s"
                }
                """.formatted(connectorId.toHex(), hex.formatHex(publicKey), hex.formatHex(salt), scheme);

        final var bundlePath = Path.of(outFile);
        final var keyPath = Path.of(outFile + ".key");
        ClprArgs.writeBundleAndKey(bundlePath, json, keyPath, "0x" + hex.formatHex(keys.privateKeyBytes()) + "\n");
        System.out.println("Wrote connector identity bundle to " + outFile);
        System.out.println("Wrote private key to " + keyPath + " — keep it secret");
        return 0;
    }

    static final class ClprIdentityCrypto {
        private static final int ED25519_SEED_LEN = 32;
        private static final int ECDSA_SCALAR_LEN = 32;
        private static final BigInteger SECP256K1_N =
                new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);

        private ClprIdentityCrypto() {}

        record Keys(byte[] privateKeyBytes, byte[] publicKey) {}

        static String normalizeScheme(final String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("--signature-scheme is required");
            }
            final var upper = raw.toUpperCase();
            return switch (upper) {
                case "ED25519", "ECDSA_SECP256K1" -> upper;
                default ->
                    throw new IllegalArgumentException(
                            "Unsupported --signature-scheme '" + raw + "'. Supported: ED25519, ECDSA_SECP256K1.");
            };
        }

        static Keys generate(final String scheme, final SecureRandom rng) {
            return switch (scheme) {
                case "ED25519" -> {
                    final byte[] seed = new byte[ED25519_SEED_LEN];
                    rng.nextBytes(seed);
                    final var priv = Ed25519Utils.keyFrom(seed);
                    final byte[] pub = Ed25519Utils.extractEd25519PublicKey(priv);
                    yield new Keys(seed, pub);
                }
                case "ECDSA_SECP256K1" -> {
                    final byte[] scalar = sampleSecp256k1Scalar(rng);
                    final var priv = Secp256k1Utils.readECKeyFrom(scalar);
                    // Handler expects 64-byte uncompressed pubkey (X||Y, no 0x04 header).
                    final byte[] compressed = Secp256k1Utils.extractEcdsaPublicKey(priv);
                    final byte[] uncompressed64 = MiscCryptoUtils.decompressSecp256k1(compressed);
                    yield new Keys(scalar, uncompressed64);
                }
                default -> throw new IllegalStateException("unreachable: " + scheme);
            };
        }

        // Rejection-sample a 32-byte scalar in [1, n-1]. The chance of a single draw being
        // out of range is ~2^-128, so the loop almost always exits on the first iteration.
        private static byte[] sampleSecp256k1Scalar(final SecureRandom rng) {
            final byte[] buf = new byte[ECDSA_SCALAR_LEN];
            while (true) {
                rng.nextBytes(buf);
                final var s = new BigInteger(1, buf);
                if (s.signum() == 1 && s.compareTo(SECP256K1_N) < 0) {
                    return buf;
                }
            }
        }
    }
}

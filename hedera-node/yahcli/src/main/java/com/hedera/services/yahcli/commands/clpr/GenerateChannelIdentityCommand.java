// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.hapi.utils.SignatureGenerator;
import com.hedera.node.app.hapi.utils.keys.Ed25519Utils;
import com.hedera.node.app.hapi.utils.keys.Secp256k1Utils;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Generates a fresh CLPR channel identity bundle: a random channel id, a fresh keypair,
 * the matching ownership commitment, and a pre-computed reveal signature.
 *
 * <p>Output is a JSON object with the fields needed to invoke {@code register-channel}
 * (use {@code ownershipCommitment}) and later {@code complete-channel}
 * (use {@code channelId}, {@code publicKey}, {@code signature}, {@code signatureScheme}).
 *
 * <p>{@code privateKey} is included so the operator can rotate the channel key later,
 * sign config updates, etc. Treat it as a secret. For ED25519 it is the 32-byte seed; for
 * ECDSA_SECP256K1 it is the 32-byte secp256k1 scalar.
 */
@Command(
        name = "generate-channel-identity",
        subcommands = {HelpCommand.class},
        description = "Generates a fresh channel id, keypair, ownership commitment, and reveal "
                + "signature for use with register-channel / complete-channel.")
public class GenerateChannelIdentityCommand implements Callable<Integer> {

    private static final int CHANNEL_ID_LEN = 32;

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
            description = "If set, writes the JSON bundle to this file instead of stdout.")
    String outFile;

    @Override
    public Integer call() throws Exception {
        final var scheme = ClprIdentityCrypto.normalizeScheme(signatureScheme);
        final var rng = new SecureRandom();

        // 1. Random 32-byte channel id.
        final byte[] channelId = new byte[CHANNEL_ID_LEN];
        rng.nextBytes(channelId);

        // 2. Fresh keypair for the chosen scheme.
        final var keys = ClprIdentityCrypto.generate(scheme, rng);
        final byte[] publicKey = keys.publicKey();
        final PrivateKey privateKey = keys.privateKey();

        // 3. ownership_commitment = keccak256(channel_id || public_key)
        final byte[] commitPreimage = new byte[channelId.length + publicKey.length];
        System.arraycopy(channelId, 0, commitPreimage, 0, channelId.length);
        System.arraycopy(publicKey, 0, commitPreimage, channelId.length, publicKey.length);
        final byte[] commitment = MiscCryptoUtils.keccak256DigestOf(commitPreimage);

        // 4. reveal_signature = sign( keccak256(channel_id) ) with the private key.
        final byte[] revealMsgHash = MiscCryptoUtils.keccak256DigestOf(channelId);
        final byte[] signature = SignatureGenerator.signBytes(revealMsgHash, privateKey);

        // 5. Emit JSON. Hand-rolled to avoid an extra protobuf type for an ad-hoc bundle.
        final var hex = HexFormat.of();
        final var json = """
                {
                  "channelId":       "0x%s",
                  "publicKey":          "0x%s",
                  "privateKey":         "0x%s",
                  "signatureScheme":    "%s",
                  "ownershipCommitment":"0x%s",
                  "signature":          "0x%s"
                }
                """.formatted(
                        hex.formatHex(channelId),
                        hex.formatHex(publicKey),
                        hex.formatHex(keys.privateKeyBytes()),
                        scheme,
                        hex.formatHex(commitment),
                        hex.formatHex(signature));

        if (outFile != null && !outFile.isBlank()) {
            Files.writeString(Path.of(outFile), json);
            System.out.println("Wrote channel identity bundle to " + outFile);
        } else {
            System.out.println(json);
        }
        return 0;
    }

    /**
     * Package-private helper that mirrors the keypair generation needed by both
     * {@code generate-channel-identity} and {@code generate-connector-identity}.
     */
    static final class ClprIdentityCrypto {
        private static final int ED25519_SEED_LEN = 32;
        private static final int ECDSA_SCALAR_LEN = 32;
        private static final BigInteger SECP256K1_N =
                new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);

        private ClprIdentityCrypto() {}

        record Keys(byte[] privateKeyBytes, PrivateKey privateKey, byte[] publicKey) {}

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
                    yield new Keys(seed, priv, pub);
                }
                case "ECDSA_SECP256K1" -> {
                    final byte[] scalar = sampleSecp256k1Scalar(rng);
                    final var priv = Secp256k1Utils.readECKeyFrom(scalar);
                    // Handler expects 64-byte uncompressed pubkey (X||Y, no 0x04 header).
                    final byte[] compressed = Secp256k1Utils.extractEcdsaPublicKey(priv);
                    final byte[] uncompressed64 = MiscCryptoUtils.decompressSecp256k1(compressed);
                    yield new Keys(scalar, priv, uncompressed64);
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

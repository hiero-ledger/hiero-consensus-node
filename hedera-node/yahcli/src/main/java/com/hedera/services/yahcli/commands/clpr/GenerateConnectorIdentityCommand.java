// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.yahcli.commands.clpr.GenerateChannelIdentityCommand.ClprIdentityCrypto;
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
        description = "Generates a key pair, salt, and connector ID for register-connector.")
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
}

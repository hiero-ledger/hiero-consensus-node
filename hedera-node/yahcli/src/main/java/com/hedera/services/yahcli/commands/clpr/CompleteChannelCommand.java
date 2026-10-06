// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteChannel;
import static com.hedera.services.yahcli.config.ConfigUtils.configFrom;

import com.hedera.services.yahcli.suites.ClprTxnSuite;
import com.hederahashgraph.api.proto.java.ClprSignatureScheme;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Submits Phase 2 (Reveal) of the two-phase channel registration protocol — a
 * {@code ClprCompleteChannel} HAPI transaction that opens the prior commitment by
 * revealing the channel id, public key, and reveal signature.
 *
 * <p>For convenience, {@code --identity <path>} loads {@code channel-id},
 * {@code public-key}, {@code signature}, and {@code signature-scheme} from a JSON bundle
 * produced by {@link GenerateChannelIdentityCommand}; any flag passed explicitly
 * overrides the bundled value. Pair with {@link RegisterChannelCommand} (Phase 1).
 */
@Command(
        name = "complete-channel",
        subcommands = {HelpCommand.class},
        description = "Submits a ClprCompleteChannel (Phase 2: Reveal) transaction.")
public class CompleteChannelCommand implements Callable<Integer> {

    @ParentCommand
    ClprCommand clprCommand;

    @Option(
            names = {"--identity"},
            paramLabel = "<path>",
            description = "Path to the channel identity bundle produced by "
                    + "`generate-channel-identity`. Supplies channel-id, public-key, "
                    + "signature, and signature-scheme. Individual flags below override.")
    String identityFile;

    @Option(
            names = {"--channel-id"},
            paramLabel = "<hex>",
            description = "Hex-encoded channel id (32 bytes). Overrides the value from --identity.")
    String channelIdHex;

    @Option(
            names = {"--public-key"},
            paramLabel = "<hex>",
            description = "Hex-encoded public key bytes. Overrides the value from --identity.")
    String publicKeyHex;

    @Option(
            names = {"--signature"},
            paramLabel = "<hex>",
            description = "Hex-encoded signature over the canonical reveal payload. "
                    + "Overrides the value from --identity.")
    String signatureHex;

    @Option(
            names = {"--signature-scheme"},
            paramLabel = "<scheme>",
            description = "One of: ED25519, ECDSA_SECP256K1. " + "Defaults to the value from --identity, then ED25519.")
    String signatureScheme;

    @Option(
            names = {"--verifier-contract"},
            paramLabel = "<shard.realm.num>",
            required = true,
            description = "Verifier contract id. Required: ClprCompleteChannelHandler.pureChecks rejects "
                    + "transactions missing a verifier contract.")
    String verifierContract;

    @Option(
            names = {"--config-proof"},
            paramLabel = "<path>",
            description = "Path to a binary file containing the configuration proof bytes "
                    + "(e.g. the file written by `get-ledger-configuration --proof-path`). "
                    + "Mutually exclusive with --config-proof-hex; exactly one is required because "
                    + "ClprCompleteChannelHandler.pureChecks rejects transactions with empty configProofBytes.")
    String configProofFile;

    @Option(
            names = {"--config-proof-hex"},
            paramLabel = "<hex>",
            description = "Hex-encoded configuration proof bytes (with or without 0x prefix). "
                    + "Mutually exclusive with --config-proof; exactly one is required.")
    String configProofHex;

    @Override
    public Integer call() throws Exception {
        final var config = configFrom(clprCommand.getYahcli());

        // Identity bundle (if provided) supplies defaults; explicit flags win.
        final Map<String, String> identity = (identityFile != null && !identityFile.isBlank())
                ? ClprArgs.readFlatJsonStringFields(Path.of(identityFile))
                : Map.of();

        final var channelId = pick(channelIdHex, identity.get("channelId"), "channel-id");
        final var publicKey = pick(publicKeyHex, identity.get("publicKey"), "public-key");
        final var signature = pick(signatureHex, identity.get("signature"), "signature");
        final var scheme = firstNonBlank(signatureScheme, identity.get("signatureScheme"), "ED25519")
                .toUpperCase();

        final var op = clprCompleteChannel()
                .channelId(ClprArgs.requiredBytes("channel-id", channelId))
                .publicKey(ClprArgs.requiredBytes("public-key", publicKey))
                .signature(ClprArgs.requiredBytes("signature", signature))
                .signatureScheme(ClprSignatureScheme.valueOf(scheme));
        if (verifierContract != null && !verifierContract.isBlank()) {
            op.verifierContractId(ClprArgs.parseContractId(verifierContract));
        }
        final var configProofFileProvided = configProofFile != null && !configProofFile.isBlank();
        final var configProofHexProvided = configProofHex != null && !configProofHex.isBlank();
        if (configProofFileProvided && configProofHexProvided) {
            throw new IllegalArgumentException("Cannot specify both --config-proof and --config-proof-hex; pick one.");
        }
        if (!configProofFileProvided && !configProofHexProvided) {
            throw new IllegalArgumentException("Either --config-proof or --config-proof-hex is required");
        }
        final var configProofBytes = configProofFileProvided
                ? ClprArgs.readBytesFile(Path.of(configProofFile))
                : ClprArgs.parseHex(configProofHex);
        op.configProofBytes(configProofBytes);

        final var delegate = new ClprTxnSuite(config, "ClprCompleteChannel", op);
        delegate.runSuiteSync();
        return ClprOutcome.reportTxn(
                config, delegate, op, "completed CLPR channel reveal", "could not complete CLPR channel reveal");
    }

    /** Returns the first non-blank of {@code explicit}, {@code fromIdentity}; errors if both are blank. */
    private static String pick(final String explicit, final String fromIdentity, final String name) {
        final var v = firstNonBlank(explicit, fromIdentity, null);
        if (v == null) {
            throw new IllegalArgumentException("--" + name + " is required (provide it explicitly or via --identity)");
        }
        return v;
    }

    private static String firstNonBlank(final String... values) {
        for (final var v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}

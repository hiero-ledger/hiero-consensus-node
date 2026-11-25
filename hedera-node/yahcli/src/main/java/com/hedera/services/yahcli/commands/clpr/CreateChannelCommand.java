// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCreateChannel;
import static com.hedera.services.yahcli.config.ConfigUtils.configFrom;

import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.services.yahcli.suites.ClprTxnSuite;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** Creates a CLPR channel using the two ledgers’ agreed channel ID. */
@Command(
        name = "create-channel",
        subcommands = {HelpCommand.class},
        description = "Creates a CLPR channel with a verifier-bound channel ID.")
public class CreateChannelCommand implements Callable<Integer> {

    /** The Hiero TSS verifier is the CLPR system contract itself (0.0.366). */
    private static final long HIERO_TSS_VERIFIER_NUM = 0x16eL;

    @ParentCommand
    ClprCommand clprCommand;

    @Option(names = "--channel-id", required = true, description = "Derived 32-byte channel ID in hex.")
    String channelIdHex;

    @Option(names = "--salt", required = true, description = "32-byte channel salt in hex.")
    String saltHex;

    @Option(names = "--peer-verifier", required = true, description = "Verifier address on the peer ledger in hex.")
    String peerVerifierHex;

    @Option(
            names = "--trust-anchor",
            description = "Peer initial trust anchor in hex. Must equal the anchor in the proven peer configuration. "
                    + "Defaults to the anchor in --peer-config.")
    String trustAnchorHex;

    @Option(
            names = "--peer-config",
            paramLabel = "<path>",
            description = "Peer configuration file written by `get-ledger-configuration --out`. Supplies the peer "
                    + "trust anchor and, without --config-proof or --config-proof-hex, the configuration proof.")
    String peerConfigFile;

    @Option(
            names = "--local-config",
            paramLabel = "<path>",
            description = "Local configuration file written by `get-ledger-configuration --out` when the channel ID "
                    + "was generated. Supplies the local configuration timestamp and trust anchor hash.")
    String localConfigFile;

    @Option(
            names = "--local-config-timestamp",
            description = "Version of the local configuration the peer holds, as <seconds>[.<nanos>]. "
                    + "Overrides --local-config.")
    String localConfigTimestamp;

    @Option(
            names = "--local-trust-anchor-hash",
            description = "keccak256 of the trust anchor the peer holds for the local ledger, in hex. Must match the "
                    + "local initial trust anchor. Overrides --local-config.")
    String localTrustAnchorHashHex;

    @Option(
            names = {"--verifier-contract"},
            paramLabel = "<shard.realm.num>",
            required = true,
            description = "Verifier contract id. Required: ClprCreateChannelHandler.pureChecks rejects "
                    + "transactions missing a verifier contract.")
    String verifierContract;

    @Option(
            names = {"--config-proof"},
            paramLabel = "<path>",
            description = "Path to a binary file containing the configuration proof bytes "
                    + "(e.g. the file written by `get-ledger-configuration --proof-path`). "
                    + "Mutually exclusive with --config-proof-hex; exactly one is required because "
                    + "ClprCreateChannelHandler.pureChecks rejects transactions with empty configProofBytes.")
    String configProofFile;

    @Option(
            names = {"--config-proof-hex"},
            paramLabel = "<hex>",
            description = "Hex-encoded configuration proof bytes (with or without 0x prefix). "
                    + "Mutually exclusive with --config-proof; exactly one is required.")
    String configProofHex;

    @Option(
            names = {"--endpoint-manifest-proof"},
            paramLabel = "<path>",
            description = "Path to a binary file containing the peer's endpoint-manifest proof bytes "
                    + "(e.g. the file written by `get-endpoint-manifest --proof-path`). "
                    + "Mutually exclusive with --endpoint-manifest-proof-hex. Required when --verifier-contract is "
                    + "the Hiero TSS verifier (0.0.366), which rejects an empty endpoint_manifest_proof_bytes; "
                    + "the Besu, Sei, and Ethereum verifiers accept an empty proof and fall back to a bring-up "
                    + "manifest.")
    String endpointManifestProofFile;

    @Option(
            names = {"--endpoint-manifest-proof-hex"},
            paramLabel = "<hex>",
            description = "Hex-encoded endpoint-manifest proof bytes (with or without 0x prefix). "
                    + "Mutually exclusive with --endpoint-manifest-proof; "
                    + "required for the Hiero TSS verifier (0.0.366).")
    String endpointManifestProofHex;

    @Override
    public Integer call() throws Exception {
        final var config = configFrom(clprCommand.getYahcli());

        final var op = clprCreateChannel()
                .channelId(ClprArgs.requiredBytes("channel-id", channelIdHex))
                .salt(ClprArgs.requiredBytes("salt", saltHex))
                .peerVerifier(ClprArgs.requiredBytes("peer-verifier", peerVerifierHex))
                .verifierContractId(ClprArgs.parseContractId(verifierContract));
        final var peerConfig = peerConfigFile == null || peerConfigFile.isBlank()
                ? null
                : ClprLedgerConfigFile.read(Path.of(peerConfigFile));
        var configProofBytes = ClprArgs.optionalBytesFromFileOrHex(
                "config-proof", configProofFile, "config-proof-hex", configProofHex);
        if (configProofBytes == null && peerConfig != null) {
            configProofBytes = peerConfig.stateProof();
        }
        if (configProofBytes == null) {
            throw new IllegalArgumentException(
                    "One of --config-proof, --config-proof-hex or --peer-config is required");
        }
        op.configProofBytes(configProofBytes);
        final var trustAnchor = trustAnchorHex != null
                ? ClprArgs.parseHex(trustAnchorHex)
                : peerConfig == null ? null : peerConfig.initialTrustAnchor();
        if (trustAnchor == null || trustAnchor.length == 0) {
            throw new IllegalArgumentException("A peer trust anchor is required: pass --trust-anchor or --peer-config");
        }
        op.trustAnchor(trustAnchor);
        final var localConfig = localConfigFile == null || localConfigFile.isBlank()
                ? null
                : ClprLedgerConfigFile.read(Path.of(localConfigFile));
        final var timestamp = localConfigTimestamp != null
                ? ClprLedgerConfigFile.parseTimestamp(localConfigTimestamp)
                : localConfig == null ? null : localConfig.timestamp();
        if (timestamp == null) {
            throw new IllegalArgumentException(
                    "A local configuration timestamp is required: pass --local-config or --local-config-timestamp");
        }
        op.localConfigTimestamp(timestamp);
        if (localTrustAnchorHashHex != null) {
            op.localTrustAnchorHash(ClprArgs.parseHex(localTrustAnchorHashHex));
        } else if (localConfig != null) {
            op.localTrustAnchorHash(MiscCryptoUtils.keccak256DigestOf(localConfig.initialTrustAnchor()));
        } else {
            throw new IllegalArgumentException(
                    "A local trust anchor hash is required: pass --local-config or --local-trust-anchor-hash");
        }

        final var endpointManifestProofBytes = ClprArgs.optionalBytesFromFileOrHex(
                "endpoint-manifest-proof",
                endpointManifestProofFile,
                "endpoint-manifest-proof-hex",
                endpointManifestProofHex);
        if (endpointManifestProofBytes != null) {
            op.endpointManifestProofBytes(endpointManifestProofBytes);
        }
        if ((endpointManifestProofBytes == null || endpointManifestProofBytes.length == 0)
                && targetsHieroTssVerifier()) {
            config.output()
                    .warn("WARNING - no endpoint-manifest proof bytes; the Hiero TSS verifier (0.0.366) rejects an "
                            + "empty endpoint_manifest_proof_bytes with CLPR_VERIFIER_CONFIG_FAILED. Pass the peer's "
                            + "`get-endpoint-manifest --proof-path` file via --endpoint-manifest-proof.");
        }

        final var delegate = new ClprTxnSuite(config, "ClprCreateChannel", op);
        delegate.runSuiteSync();
        return ClprOutcome.reportTxn(config, delegate, op, "created CLPR channel", "could not create CLPR channel");
    }

    /** True when {@code --verifier-contract} names the Hiero TSS verifier (0.0.366). */
    private boolean targetsHieroTssVerifier() {
        return verifierContract != null
                && !verifierContract.isBlank()
                && ClprArgs.parseContractId(verifierContract).getContractNum() == HIERO_TSS_VERIFIER_NUM;
    }
}

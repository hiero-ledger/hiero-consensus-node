// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import com.hedera.node.app.hapi.utils.ClprChannelId;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hederahashgraph.api.proto.java.Timestamp;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Option;

/**
 * Derives the shared channel identity offline. The channel ID commits to one leg per direction: each leg binds a
 * ledger's chain ID, service address, configuration timestamp and initial trust anchor to the verifier for that
 * ledger on the other side. Each ledger's values come from its {@code get-ledger-configuration --out} file, and any
 * explicit option overrides the file.
 */
@Command(
        name = "generate-channel-identity",
        subcommands = HelpCommand.class,
        description = "Derives the channel ID from both ledgers' configurations, verifiers and a shared salt.")
public class GenerateChannelIdentityCommand implements Callable<Integer> {
    @Option(
            names = "--local-config",
            description = "Local ledger configuration file written by `get-ledger-configuration --out`.")
    Path localConfig;

    @Option(names = "--local-chain-id", description = "Local CAIP-2 chain ID.")
    String localChain;

    @Option(names = "--local-service", description = "Local CLPR service address in hex.")
    String localService;

    @Option(
            names = "--local-config-timestamp",
            description = "Version of the local configuration the peer holds, as <seconds>[.<nanos>].")
    String localTimestamp;

    @Option(names = "--local-trust-anchor", description = "Local initial trust anchor in hex.")
    String localTrustAnchor;

    @Option(
            names = "--local-verifier",
            required = true,
            description = "Verifier on the local ledger for the peer, as an address in hex.")
    String localVerifier;

    @Option(
            names = "--peer-config",
            description = "Peer ledger configuration file written by `get-ledger-configuration --out`.")
    Path peerConfig;

    @Option(names = "--peer-chain-id", description = "Peer CAIP-2 chain ID.")
    String peerChain;

    @Option(names = "--peer-service", description = "Peer CLPR service address in hex.")
    String peerService;

    @Option(
            names = "--peer-config-timestamp",
            description = "Version of the peer configuration the local ledger holds, as <seconds>[.<nanos>].")
    String peerTimestamp;

    @Option(names = "--peer-trust-anchor", description = "Peer initial trust anchor in hex.")
    String peerTrustAnchor;

    @Option(
            names = "--peer-verifier",
            required = true,
            description = "Verifier on the peer ledger for the local ledger, as an address in hex.")
    String peerVerifier;

    @Option(names = "--salt", description = "Shared 32-byte salt in hex. Defaults to a newly generated salt.")
    String saltHex;

    @Option(names = "--out", required = true, description = "Write the channel ID and salt to this JSON file.")
    Path output;

    @Override
    public Integer call() throws Exception {
        final byte[] salt;
        if (saltHex == null) {
            salt = new byte[32];
            new SecureRandom().nextBytes(salt);
        } else {
            salt = ClprArgs.parseHex(saltHex);
        }
        final var local = localConfig == null ? null : ClprLedgerConfigFile.read(localConfig);
        final var peer = peerConfig == null ? null : ClprLedgerConfigFile.read(peerConfig);
        // leg(local→peer) ends at the peer's verifier for the local ledger, and the other way round
        final var localToPeer = leg(
                "local",
                local,
                localChain,
                localService,
                localTimestamp,
                localTrustAnchor,
                ClprArgs.parseHex(peerVerifier));
        final var peerToLocal = leg(
                "peer", peer, peerChain, peerService, peerTimestamp, peerTrustAnchor, ClprArgs.parseHex(localVerifier));
        final var channelId = ClprChannelId.derive(localToPeer, peerToLocal, Bytes.wrap(salt));
        final var json = """
                {
                  "channelId": "0x%s",
                  "salt": "0x%s"
                }
                """.formatted(channelId.toHex(), HexFormat.of().formatHex(salt));
        ClprArgs.backupIfExists(output);
        ClprArgs.writeSecure(output, json);
        System.out.println("Wrote channel ID and salt to " + output);
        return 0;
    }

    private static Bytes leg(
            final String side,
            @Nullable final ClprLedgerConfigFile file,
            @Nullable final String chainId,
            @Nullable final String service,
            @Nullable final String timestamp,
            @Nullable final String trustAnchor,
            final byte[] destinationVerifier) {
        final var resolvedChainId = chainId != null ? chainId : file == null ? null : file.chainId();
        final var resolvedService =
                service != null ? ClprArgs.parseHex(service) : file == null ? null : file.serviceAddress();
        final Timestamp resolvedTimestamp = timestamp != null
                ? ClprLedgerConfigFile.parseTimestamp(timestamp)
                : file == null ? null : file.timestamp();
        final var resolvedAnchor =
                trustAnchor != null ? ClprArgs.parseHex(trustAnchor) : file == null ? null : file.initialTrustAnchor();
        if (resolvedChainId == null || resolvedChainId.isEmpty()) {
            throw new IllegalArgumentException(
                    "Missing " + side + " chain ID: pass --" + side + "-config or --" + side + "-chain-id");
        }
        if (resolvedService == null) {
            throw new IllegalArgumentException(
                    "Missing " + side + " service address: pass --" + side + "-config or --" + side + "-service");
        }
        if (resolvedTimestamp == null) {
            throw new IllegalArgumentException("Missing " + side + " configuration timestamp: pass --" + side
                    + "-config or --" + side + "-config-timestamp");
        }
        if (resolvedAnchor == null) {
            throw new IllegalArgumentException(
                    "Missing " + side + " trust anchor: pass --" + side + "-config or --" + side + "-trust-anchor");
        }
        return ClprChannelId.leg(
                resolvedChainId,
                Bytes.wrap(resolvedService),
                com.hedera.hapi.node.base.Timestamp.newBuilder()
                        .seconds(resolvedTimestamp.getSeconds())
                        .nanos(resolvedTimestamp.getNanos())
                        .build(),
                MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(resolvedAnchor)),
                Bytes.wrap(destinationVerifier));
    }
}

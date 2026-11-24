// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import com.hedera.node.app.hapi.utils.ClprChannelId;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Option;

/** Derives the shared channel identity offline from the two ledgers' verifier choices. */
@Command(
        name = "generate-channel-identity",
        subcommands = HelpCommand.class,
        description = "Derives the channel ID from both endpoint identities and a shared salt.")
public class GenerateChannelIdentityCommand implements Callable<Integer> {
    @Option(names = "--local-chain-id", required = true, description = "Local CAIP-2 chain ID.")
    String localChain;

    @Option(names = "--local-service", required = true, description = "Local CLPR service address in hex.")
    String localService;

    @Option(names = "--local-verifier", required = true, description = "Verifier address on the local ledger in hex.")
    String localVerifier;

    @Option(names = "--peer-chain-id", required = true, description = "Peer CAIP-2 chain ID.")
    String peerChain;

    @Option(names = "--peer-service", required = true, description = "Peer CLPR service address in hex.")
    String peerService;

    @Option(names = "--peer-verifier", required = true, description = "Verifier address on the peer ledger in hex.")
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
        final var channelId = ClprChannelId.derive(
                localChain,
                Bytes.wrap(ClprArgs.parseHex(localService)),
                Bytes.wrap(ClprArgs.parseHex(localVerifier)),
                peerChain,
                Bytes.wrap(ClprArgs.parseHex(peerService)),
                Bytes.wrap(ClprArgs.parseHex(peerVerifier)),
                Bytes.wrap(salt));
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
}

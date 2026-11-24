// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static com.hedera.services.yahcli.commands.clpr.IdentityTestSupport.backupsOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.services.yahcli.Yahcli;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class GenerateChannelIdentityCommandTest {
    @Test
    void generatedChannelIdentityMatchesSolidity(@TempDir final Path directory) throws Exception {
        final var output = directory.resolve("channel.json");
        final var salt = "00".repeat(32);
        assertThat(new CommandLine(new Yahcli())
                        .execute(
                                "clpr",
                                "generate-channel-identity",
                                "--local-chain-id",
                                "hedera:testnet",
                                "--local-service",
                                "00".repeat(18) + "016e",
                                "--local-verifier",
                                "00".repeat(18) + "1389",
                                "--peer-chain-id",
                                "eip155:1",
                                "--peer-service",
                                "010203",
                                "--peer-verifier",
                                "00112233445566778899aabbccddeeff00112233",
                                "--salt",
                                salt,
                                "--out",
                                output.toString()))
                .isZero();
        assertThat(ClprArgs.readFlatJsonStringFields(output))
                .containsEntry("salt", "0x" + salt)
                .containsEntry("channelId", "0xbb082a20892923291a09d2402beeedc74d1077bf44ba67d4b33cede9495688a2");
    }

    @Test
    void generatesFreshSaltsAndBacksUpPreviousIdentity(@TempDir final Path dir) throws Exception {
        final var output = dir.resolve("channel.json");
        final var command = generator(output);
        command.call();
        final var first = ClprArgs.readFlatJsonStringFields(output);
        command.call();
        final var second = ClprArgs.readFlatJsonStringFields(output);
        assertThat(first).containsOnlyKeys("channelId", "salt");
        assertThat(second.get("salt")).matches("0x[0-9a-f]{64}").isNotEqualTo(first.get("salt"));
        assertThat(second.get("channelId")).isNotEqualTo(first.get("channelId"));
        assertThat(backupsOf(dir, "channel.json")).hasSize(1);
        assertThat(dir.resolve("channel.json.key")).doesNotExist();
    }

    @Test
    void rejectsSymlinkedOutput(@TempDir final Path dir) throws Exception {
        final var target = Files.writeString(dir.resolve("target"), "original");
        final var link = Files.createSymbolicLink(dir.resolve("channel.json"), target);
        assertThatThrownBy(generator(link)::call).isInstanceOf(IOException.class);
        assertThat(Files.readString(target)).isEqualTo("original");
    }

    private static GenerateChannelIdentityCommand generator(final Path output) {
        final var generator = new GenerateChannelIdentityCommand();
        generator.output = output;
        generator.localChain = "eip155:298";
        generator.peerChain = "eip155:299";
        generator.localService = generator.peerService = "00".repeat(18) + "016e";
        generator.localVerifier = generator.peerVerifier = "00".repeat(18) + "016e";
        return generator;
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.services.yahcli.Yahcli;
import com.hederahashgraph.api.proto.java.ClprEndpoint;
import com.hederahashgraph.api.proto.java.ClprEndpointManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import picocli.CommandLine;

class ClprEndpointManifestOptionsTest {

    @Test
    void clprRegistersGetEndpointManifest() {
        final var clpr = new CommandLine(new Yahcli()).getSubcommands().get("clpr");

        assertThat(clpr.getSubcommands()).containsKey("get-endpoint-manifest");
    }

    @Test
    void completeChannelAcceptsEndpointManifestProof() {
        final var parsed = new CommandLine(new Yahcli())
                .parseArgs(
                        "clpr",
                        "complete-channel",
                        "--verifier-contract",
                        "0.0.366",
                        "--config-proof",
                        "config.bin",
                        "--endpoint-manifest-proof",
                        "manifest.bin");

        final var command = (CompleteChannelCommand)
                parsed.subcommand().subcommand().commandSpec().userObject();
        assertThat(command.configProofFile).isEqualTo("config.bin");
        assertThat(command.endpointManifestProofFile).isEqualTo("manifest.bin");
    }

    @Test
    void completeChannelDoesNotRequireEndpointManifestProof() {
        final var parsed = new CommandLine(new Yahcli())
                .parseArgs("clpr", "complete-channel", "--verifier-contract", "0.0.367", "--config-proof-hex", "01");

        final var command = (CompleteChannelCommand)
                parsed.subcommand().subcommand().commandSpec().userObject();
        assertThat(command.endpointManifestProofFile).isNull();
        assertThat(command.endpointManifestProofHex).isNull();
    }

    @Test
    void fileOrHexBytesAreAbsentWhenNeitherFlagIsGiven() throws Exception {
        assertThat(ClprArgs.optionalBytesFromFileOrHex("proof", null, "proof-hex", " "))
                .isNull();
    }

    @Test
    void fileOrHexBytesParseHex() throws Exception {
        assertThat(ClprArgs.optionalBytesFromFileOrHex("proof", "", "proof-hex", "0x0a0B"))
                .containsExactly(new byte[] {0x0a, 0x0b});
    }

    @Test
    void fileOrHexBytesReadFile(@TempDir final Path dir) throws Exception {
        final var proof = dir.resolve("proof.bin");
        Files.write(proof, new byte[] {1, 2, 3});

        assertThat(ClprArgs.optionalBytesFromFileOrHex("proof", proof.toString(), "proof-hex", null))
                .containsExactly(new byte[] {1, 2, 3});
    }

    @Test
    void fileOrHexBytesRejectBothFlags() {
        assertThatThrownBy(() -> ClprArgs.optionalBytesFromFileOrHex(
                        "endpoint-manifest-proof", "manifest.bin", "endpoint-manifest-proof-hex", "01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Cannot specify both --endpoint-manifest-proof and --endpoint-manifest-proof-hex; pick one.");
    }

    /** Genesis seeds version 1 with no endpoints; only a reconciler-built manifest with endpoints counts. */
    @ParameterizedTest
    @CsvSource({"1, 0, false", "2, 0, false", "1, 1, false", "2, 1, true", "3, 2, true"})
    void manifestIsFinalizedOnlyFromVersionTwoWithEndpoints(
            final long version, final int endpoints, final boolean expected) {
        final var manifest = ClprEndpointManifest.newBuilder().setVersion(version);
        for (int i = 0; i < endpoints; i++) {
            manifest.addEndpoints(ClprEndpoint.getDefaultInstance());
        }

        assertThat(GetEndpointManifestCommand.isFinalized(manifest.build())).isEqualTo(expected);
    }
}

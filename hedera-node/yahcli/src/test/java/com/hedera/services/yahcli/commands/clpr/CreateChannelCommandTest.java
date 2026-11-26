// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.util.JsonFormat;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.services.yahcli.Yahcli;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.Timestamp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CreateChannelCommandTest {
    private static final byte[] PEER_PROOF = {7, 7, 7};
    private static final byte[] PEER_ANCHOR = "peer-anchor".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LOCAL_ANCHOR = "local-anchor".getBytes(StandardCharsets.UTF_8);
    private static final Timestamp LOCAL_TIMESTAMP =
            Timestamp.newBuilder().setSeconds(1_764_064_800L).setNanos(5).build();

    @Test
    void takesEveryValueFromTheConfigurationFiles(@TempDir final Path dir) throws Exception {
        final var command = command(
                "--peer-config",
                peerConfig(dir).toString(),
                "--local-config",
                localConfig(dir).toString(),
                "--endpoint-manifest-proof-hex",
                "0a0b");

        final var fields = command.resolveFields();

        assertThat(fields.configProof()).isEqualTo(PEER_PROOF);
        assertThat(fields.trustAnchor()).isEqualTo(PEER_ANCHOR);
        assertThat(fields.localConfigTimestamp()).isEqualTo(LOCAL_TIMESTAMP);
        assertThat(fields.localTrustAnchorHash()).isEqualTo(MiscCryptoUtils.keccak256DigestOf(LOCAL_ANCHOR));
        assertThat(fields.endpointManifestProof()).isEqualTo(new byte[] {0x0a, 0x0b});
    }

    @Test
    void explicitOptionsOverrideTheConfigurationFiles(@TempDir final Path dir) throws Exception {
        final var command = command(
                "--peer-config",
                peerConfig(dir).toString(),
                "--local-config",
                localConfig(dir).toString(),
                "--config-proof-hex",
                "01",
                "--trust-anchor",
                "02",
                "--local-config-timestamp",
                "1000.000000003",
                "--local-trust-anchor-hash",
                "03");

        final var fields = command.resolveFields();

        assertThat(fields.configProof()).isEqualTo(new byte[] {1});
        assertThat(fields.trustAnchor()).isEqualTo(new byte[] {2});
        assertThat(fields.localConfigTimestamp())
                .isEqualTo(Timestamp.newBuilder().setSeconds(1000).setNanos(3).build());
        assertThat(fields.localTrustAnchorHash()).isEqualTo(new byte[] {3});
        assertThat(fields.endpointManifestProof()).isNull();
    }

    @Test
    void acceptsExplicitOptionsWithoutConfigurationFiles() throws Exception {
        final var fields = command(
                        "--config-proof-hex", "01",
                        "--trust-anchor", "02",
                        "--local-config-timestamp", "1000",
                        "--local-trust-anchor-hash", "03")
                .resolveFields();

        assertThat(fields.localConfigTimestamp())
                .isEqualTo(Timestamp.newBuilder().setSeconds(1000).build());
    }

    @Test
    void requiresAConfigurationProof() {
        assertThatThrownBy(() -> command(
                                "--trust-anchor", "02",
                                "--local-config-timestamp", "1000",
                                "--local-trust-anchor-hash", "03")
                        .resolveFields())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--config-proof");
    }

    @Test
    void requiresAPeerTrustAnchor() {
        assertThatThrownBy(() -> command(
                                "--config-proof-hex", "01",
                                "--local-config-timestamp", "1000",
                                "--local-trust-anchor-hash", "03")
                        .resolveFields())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("peer trust anchor");
    }

    @Test
    void requiresALocalConfigurationTimestamp() {
        assertThatThrownBy(() -> command(
                                "--config-proof-hex", "01",
                                "--trust-anchor", "02",
                                "--local-trust-anchor-hash", "03")
                        .resolveFields())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("local configuration timestamp");
    }

    @Test
    void requiresALocalTrustAnchorHash() {
        assertThatThrownBy(() -> command(
                                "--config-proof-hex", "01",
                                "--trust-anchor", "02",
                                "--local-config-timestamp", "1000")
                        .resolveFields())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("local trust anchor hash");
    }

    @Test
    void rejectsAMalformedLocalConfigurationTimestamp() {
        assertThatThrownBy(() -> command(
                                "--config-proof-hex", "01",
                                "--trust-anchor", "02",
                                "--local-config-timestamp", "1.0000000001",
                                "--local-trust-anchor-hash", "03")
                        .resolveFields())
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CreateChannelCommand command(final String... options) {
        final List<String> args = new ArrayList<>(List.of(
                "clpr",
                "create-channel",
                "--channel-id",
                "00".repeat(32),
                "--salt",
                "00".repeat(32),
                "--peer-verifier",
                "ff",
                "--verifier-contract",
                "0.0.366"));
        args.addAll(List.of(options));
        final var parsed = new CommandLine(new Yahcli()).parseArgs(args.toArray(String[]::new));
        return (CreateChannelCommand)
                parsed.subcommand().subcommand().commandSpec().userObject();
    }

    private static Path peerConfig(final Path dir) throws IOException {
        return writeConfig(dir.resolve("peer.json"), PEER_ANCHOR, null, PEER_PROOF);
    }

    private static Path localConfig(final Path dir) throws IOException {
        return writeConfig(dir.resolve("local.json"), LOCAL_ANCHOR, LOCAL_TIMESTAMP, new byte[0]);
    }

    /** A file as `get-ledger-configuration --out` writes it. */
    private static Path writeConfig(final Path path, final byte[] anchor, final Timestamp timestamp, final byte[] proof)
            throws IOException {
        final var configuration = ClprLedgerConfiguration.newBuilder()
                .setChainId("eip155:298")
                .setInitialTrustAnchor(ByteString.copyFrom(anchor));
        if (timestamp != null) {
            configuration.setTimestamp(timestamp);
        }
        return Files.writeString(path, """
                {
                  "configuration": %s,
                  "configurationStateProof": "%s"
                }
                """.formatted(
                        JsonFormat.printer().preservingProtoFieldNames().print(configuration),
                        Base64.getEncoder().encodeToString(proof)));
    }
}

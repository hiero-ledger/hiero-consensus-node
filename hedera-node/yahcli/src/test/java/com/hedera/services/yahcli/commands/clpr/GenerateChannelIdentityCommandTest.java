// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static com.hedera.services.yahcli.commands.clpr.IdentityTestSupport.backupsOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.util.JsonFormat;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.hapi.utils.ClprChannelId;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.yahcli.Yahcli;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprThrottles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class GenerateChannelIdentityCommandTest {
    private static final String SALT = "00".repeat(32);
    private static final String HIERO_SERVICE = "00".repeat(18) + "016e";
    private static final String LOCAL_VERIFIER = "00".repeat(18) + "1389";
    private static final String PEER_VERIFIER = "00112233445566778899aabbccddeeff00112233";

    @Test
    void derivesTheIdFromBothLegs(@TempDir final Path directory) throws Exception {
        final var output = directory.resolve("channel.json");
        assertThat(execute(
                        "--local-chain-id", "hedera:testnet",
                        "--local-service", HIERO_SERVICE,
                        "--local-config-timestamp", "2000.000000007",
                        "--local-trust-anchor", "0102",
                        "--local-verifier", LOCAL_VERIFIER,
                        "--peer-chain-id", "eip155:1",
                        "--peer-service", "010203",
                        "--peer-config-timestamp", "1000",
                        "--peer-trust-anchor", "abcd",
                        "--peer-verifier", PEER_VERIFIER,
                        "--salt", SALT,
                        "--out", output.toString()))
                .isZero();
        final var localToPeer = ClprChannelId.leg(
                "hedera:testnet",
                Bytes.fromHex(HIERO_SERVICE),
                Timestamp.newBuilder().seconds(2000).nanos(7).build(),
                MiscCryptoUtils.keccak256DigestOf(Bytes.fromHex("0102")),
                Bytes.fromHex(PEER_VERIFIER));
        final var peerToLocal = ClprChannelId.leg(
                "eip155:1",
                Bytes.fromHex("010203"),
                Timestamp.newBuilder().seconds(1000).build(),
                MiscCryptoUtils.keccak256DigestOf(Bytes.fromHex("abcd")),
                Bytes.fromHex(LOCAL_VERIFIER));
        final var expected = ClprChannelId.derive(localToPeer, peerToLocal, Bytes.fromHex(SALT));
        assertThat(ClprArgs.readFlatJsonStringFields(output))
                .containsEntry("salt", "0x" + SALT)
                .containsEntry("channelId", "0x" + expected.toHex());
    }

    @Test
    void bothOperatorsDeriveTheSameIdFromConfigurationFiles(@TempDir final Path directory) throws Exception {
        final var configA = writeConfig(directory.resolve("a.json"), "eip155:298", 1_764_064_800L, 123);
        final var configB = writeConfig(directory.resolve("b.json"), "eip155:299", 1_764_064_805L, 0);
        final var fromA = directory.resolve("from-a.json");
        final var fromB = directory.resolve("from-b.json");

        assertThat(execute(
                        "--local-config",
                        configA.toString(),
                        "--local-verifier",
                        HIERO_SERVICE,
                        "--peer-config",
                        configB.toString(),
                        "--peer-verifier",
                        HIERO_SERVICE,
                        "--salt",
                        SALT,
                        "--out",
                        fromA.toString()))
                .isZero();
        assertThat(execute(
                        "--local-config",
                        configB.toString(),
                        "--local-verifier",
                        HIERO_SERVICE,
                        "--peer-config",
                        configA.toString(),
                        "--peer-verifier",
                        HIERO_SERVICE,
                        "--salt",
                        SALT,
                        "--out",
                        fromB.toString()))
                .isZero();

        assertThat(ClprArgs.readFlatJsonStringFields(fromA).get("channelId"))
                .isEqualTo(ClprArgs.readFlatJsonStringFields(fromB).get("channelId"));
    }

    @Test
    void explicitOptionsOverrideTheConfigurationFile(@TempDir final Path directory) throws Exception {
        final var configA = writeConfig(directory.resolve("a.json"), "eip155:298", 1_764_064_800L, 0);
        final var configB = writeConfig(directory.resolve("b.json"), "eip155:299", 1_764_064_805L, 0);
        final var fromFile = directory.resolve("file.json");
        final var overridden = directory.resolve("overridden.json");
        final var common = new String[] {
            "--local-config", configA.toString(),
            "--local-verifier", HIERO_SERVICE,
            "--peer-config", configB.toString(),
            "--peer-verifier", HIERO_SERVICE,
            "--salt", SALT
        };

        assertThat(execute(concat(common, "--out", fromFile.toString()))).isZero();
        // An older local configuration than the one in the file
        assertThat(execute(concat(common, "--local-config-timestamp", "1764064799", "--out", overridden.toString())))
                .isZero();

        assertThat(ClprArgs.readFlatJsonStringFields(overridden).get("channelId"))
                .isNotEqualTo(ClprArgs.readFlatJsonStringFields(fromFile).get("channelId"));
    }

    @Test
    void readsTheConfigurationAndProofFromTheFile(@TempDir final Path directory) throws Exception {
        final var file =
                ClprLedgerConfigFile.read(writeConfig(directory.resolve("a.json"), "eip155:298", 1_764_064_800L, 123));

        assertThat(file.chainId()).isEqualTo("eip155:298");
        assertThat(file.serviceAddress()).isEqualTo(ClprArgs.parseHex(HIERO_SERVICE));
        assertThat(file.timestamp().getSeconds()).isEqualTo(1_764_064_800L);
        assertThat(file.timestamp().getNanos()).isEqualTo(123);
        assertThat(file.initialTrustAnchor()).isEqualTo("eip155:298".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(file.stateProof()).isNull();
    }

    @Test
    void rejectsAMissingConfigurationTimestamp(@TempDir final Path directory) {
        final var generator = generator(directory.resolve("channel.json"));
        generator.peerTimestamp = null;

        assertThatThrownBy(generator::call)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("peer configuration timestamp");
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
        generator.localService = generator.peerService = HIERO_SERVICE;
        generator.localTimestamp = "1000";
        generator.peerTimestamp = "1001";
        generator.localTrustAnchor = "01";
        generator.peerTrustAnchor = "02";
        generator.localVerifier = generator.peerVerifier = HIERO_SERVICE;
        return generator;
    }

    private static int execute(final String... options) {
        return new CommandLine(new Yahcli())
                .execute(concat(new String[] {"clpr", "generate-channel-identity"}, options));
    }

    private static String[] concat(final String[] first, final String... second) {
        final var all = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    /** A file as `get-ledger-configuration --out` writes it. */
    private static Path writeConfig(final Path path, final String chainId, final long seconds, final int nanos)
            throws IOException {
        final var configuration = ClprLedgerConfiguration.newBuilder()
                .setProtocolVersion(1)
                .setChainId(chainId)
                .setServiceAddress(ByteString.copyFrom(ClprArgs.parseHex(HIERO_SERVICE)))
                .setTimestamp(com.hederahashgraph.api.proto.java.Timestamp.newBuilder()
                        .setSeconds(seconds)
                        .setNanos(nanos))
                .setThrottles(ClprThrottles.newBuilder().setMaxMessagesPerBundle(100))
                .setInitialTrustAnchor(ByteString.copyFromUtf8(chainId))
                .build();
        return Files.writeString(path, """
                {
                  "configuration": %s,
                  "configurationStateProof": ""
                }
                """.formatted(
                        JsonFormat.printer().preservingProtoFieldNames().print(configuration)));
    }
}

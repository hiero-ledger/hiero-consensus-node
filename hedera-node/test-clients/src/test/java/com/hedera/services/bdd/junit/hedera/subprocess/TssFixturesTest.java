// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.subprocess;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.NodeMetadata;
import com.hedera.node.internal.network.NodeTssMetadata;
import com.hedera.node.internal.network.TssMetadata;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link TssFixtures}.
 */
class TssFixturesTest {
    private static final Bytes HINTS_KEY_0 = Bytes.wrap("hints-0");
    private static final Bytes HINTS_KEY_1 = Bytes.wrap("hints-1");
    private static final Bytes PRIVATE_KEY_0 = Bytes.wrap("private-0");
    private static final Bytes PRIVATE_KEY_1 = Bytes.wrap("private-1");
    private static final Bytes VERIFICATION_KEY_0 = Bytes.wrap("verification-key-0");
    private static final Bytes VERIFICATION_KEY_1 = Bytes.wrap("verification-key-1");

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Merge keeps the lowest node's export and collects every node's own private key")
    void mergeCollectsEachNodesOwnPrivateKey() throws IOException {
        final var node0Export = writeJson(
                "node0.json",
                export(VERIFICATION_KEY_0, entry(0, HINTS_KEY_0, PRIVATE_KEY_0), entry(1, HINTS_KEY_1, Bytes.EMPTY)));
        final var node1Export = writeJson(
                "node1.json",
                export(VERIFICATION_KEY_1, entry(0, HINTS_KEY_0, Bytes.EMPTY), entry(1, HINTS_KEY_1, PRIVATE_KEY_1)));

        final var merged = TssFixtures.mergeNodeExports(Map.of(1L, node1Export, 0L, node0Export));

        assertEquals(VERIFICATION_KEY_0, merged.tssMetadataOrThrow().historyProofVerificationKey(), "taken from node0");
        assertEquals(
                List.of(entry(0, HINTS_KEY_0, PRIVATE_KEY_0), entry(1, HINTS_KEY_1, PRIVATE_KEY_1)),
                merged.nodeTssMetadata());
    }

    @Test
    @DisplayName("Merge rejects an export that carries another node's private key")
    void mergeRejectsForeignPrivateKey() throws IOException {
        final var node0Export = writeJson(
                "node0.json",
                export(VERIFICATION_KEY_0, entry(0, HINTS_KEY_0, PRIVATE_KEY_0), entry(1, HINTS_KEY_1, PRIVATE_KEY_1)));

        final var e =
                assertThrows(IllegalStateException.class, () -> TssFixtures.mergeNodeExports(Map.of(0L, node0Export)));
        assertTrue(e.getMessage().contains("carries node1's private key"), e.getMessage());
    }

    @Test
    @DisplayName("Merge rejects an export without the node's own private key")
    void mergeRejectsMissingOwnPrivateKey() throws IOException {
        final var node1Export = writeJson(
                "node1.json",
                export(VERIFICATION_KEY_1, entry(0, HINTS_KEY_0, Bytes.EMPTY), entry(1, HINTS_KEY_1, Bytes.EMPTY)));

        final var e =
                assertThrows(IllegalStateException.class, () -> TssFixtures.mergeNodeExports(Map.of(1L, node1Export)));
        assertTrue(e.getMessage().contains("has no private key for node1"), e.getMessage());
    }

    @Test
    @DisplayName("Merge rejects an empty export map and an unreadable export")
    void mergeRejectsNoOrUnreadableExports() throws IOException {
        assertThrows(IllegalStateException.class, () -> TssFixtures.mergeNodeExports(Map.of()));

        final var garbage = Files.writeString(tempDir.resolve("garbage.json"), "not json");
        assertThrows(IllegalStateException.class, () -> TssFixtures.mergeNodeExports(Map.of(0L, garbage)));
    }

    @Test
    @DisplayName("Fixture file names map to their network, keyed off node0 for legacy per-node files")
    void fixtureNetworkNameParsesFileNames() {
        assertEquals("ledgerA", TssFixtures.fixtureNetworkName("ledgerA-genesis-network.json.gz"));
        assertEquals("ledgerA", TssFixtures.fixtureNetworkName("ledgerA-genesis-network.json"));
        assertEquals("ledgerA_manifest", TssFixtures.fixtureNetworkName("ledgerA_manifest-genesis-network.json.gz"));
        assertEquals(
                "ledgerA_manifest", TssFixtures.fixtureNetworkName("ledgerA_manifest-node0-genesis-network.json.gz"));
        assertNull(TssFixtures.fixtureNetworkName("ledgerA_manifest-node1-genesis-network.json.gz"));
        assertNull(TssFixtures.fixtureNetworkName("README.md"));
    }

    @Test
    @DisplayName("Fixture base port is the lowest node gRPC port, and node count is the nodeMetadata size")
    void fixtureBaseAndNodeCountReadsNodeMetadata() throws IOException {
        final var fixture = writeGzippedJson(
                "ledgerA_manifest-genesis-network.json.gz",
                Network.newBuilder()
                        .nodeMetadata(nodeWithGrpcPort(32102), nodeWithGrpcPort(32100))
                        .tssMetadata(TssMetadata.newBuilder().historyProofVerificationKey(VERIFICATION_KEY_0))
                        .build());

        assertArrayEquals(new int[] {32100, 2}, TssFixtures.fixtureBaseAndNodeCount(fixture));
    }

    @Test
    @DisplayName("Fixture without nodeMetadata yields no base port")
    void fixtureBaseAndNodeCountWithoutNodeMetadata() throws IOException {
        final var fixture = writeGzippedJson(
                "empty-genesis-network.json.gz",
                Network.newBuilder()
                        .tssMetadata(TssMetadata.newBuilder().historyProofVerificationKey(VERIFICATION_KEY_0))
                        .build());

        assertNull(TssFixtures.fixtureBaseAndNodeCount(fixture));
    }

    @Test
    @DisplayName("Installing a gzipped fixture writes it decompressed; a plain one is copied as-is")
    void installFixtureDecompressesGzip() throws IOException {
        final var json = "{\"nodeMetadata\":[]}";
        final var gz = tempDir.resolve("fixture.json.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        final var plain = Files.writeString(tempDir.resolve("fixture.json"), json);

        final var fromGz = tempDir.resolve("installed-from-gz.json");
        final var fromPlain = tempDir.resolve("installed-from-plain.json");
        TssFixtures.installFixture(gz, fromGz);
        TssFixtures.installFixture(plain, fromPlain);

        assertEquals(json, Files.readString(fromGz));
        assertEquals(json, Files.readString(fromPlain));
    }

    private static Network export(final Bytes verificationKey, final NodeTssMetadata... entries) {
        return Network.newBuilder()
                .tssMetadata(TssMetadata.newBuilder().historyProofVerificationKey(verificationKey))
                .nodeTssMetadata(entries)
                .build();
    }

    private static NodeTssMetadata entry(final long nodeId, final Bytes hintsKey, final Bytes privateKey) {
        return NodeTssMetadata.newBuilder()
                .nodeId(nodeId)
                .hintsKey(hintsKey)
                .blsPrivateKey(privateKey)
                .build();
    }

    private static NodeMetadata nodeWithGrpcPort(final int port) {
        return NodeMetadata.newBuilder()
                .node(Node.newBuilder()
                        .serviceEndpoint(ServiceEndpoint.newBuilder().port(port).build())
                        .build())
                .build();
    }

    private Path writeJson(final String name, final Network network) throws IOException {
        final var path = tempDir.resolve(name);
        try (OutputStream out = Files.newOutputStream(path)) {
            Network.JSON.write(network, new WritableStreamingData(out));
        }
        return path;
    }

    private Path writeGzippedJson(final String name, final Network network) throws IOException {
        final var path = tempDir.resolve(name);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(path))) {
            Network.JSON.write(network, new WritableStreamingData(out));
        }
        return path;
    }
}

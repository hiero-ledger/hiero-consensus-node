// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.otter.fixtures.assertions.MultipleNodeEventStreamResultsAssert.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.internal.result.MultipleNodeEventStreamResultsImpl;
import org.hiero.otter.fixtures.result.MultipleNodeEventStreamResults;
import org.hiero.otter.fixtures.result.SingleNodeEventStreamResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultipleNodeEventStreamResultsAssertTest {

    @TempDir
    private Path tempDir;

    private Path node1EventStreamDir;
    private Path node2EventStreamDir;
    private Path node3EventStreamDir;

    @BeforeEach
    void setUp() throws IOException {
        node1EventStreamDir = tempDir.resolve("node1");
        node2EventStreamDir = tempDir.resolve("node2");
        node3EventStreamDir = tempDir.resolve("node3");

        Files.createDirectories(node1EventStreamDir);
        Files.createDirectories(node2EventStreamDir);
        Files.createDirectories(node3EventStreamDir);
    }

    @Test
    void testHaveEqualFiles_withNullActual_shouldFail() {
        assertThatThrownBy(() -> assertThat(null).haveEqualFiles())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expecting actual not to be null");
    }

    @Test
    void testHaveEqualFiles_withNoSignatureFiles_shouldFail() throws IOException {
        // Create event stream files without signature files
        createEventStreamFile(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");
        createEventStreamFile(node2EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");

        final MultipleNodeEventStreamResults results = createResults(
                createSingleNodeResult(NodeId.of(0), node1EventStreamDir, false),
                createSingleNodeResult(NodeId.of(1), node2EventStreamDir, false));

        assertThatThrownBy(() -> assertThat(results).haveEqualFiles())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("No signature files found for any node");
    }

    @Test
    void testHaveEqualFiles_withSignatureFileCountDifferenceGreaterThanOne_shouldFail() throws IOException {
        // Node 1 has 3 files
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", "content2");
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T02_00_00.000000000Z.evts", "content3");

        // Node 2 has only 1 file
        createEventStreamFileWithSignature(node2EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");

        final MultipleNodeEventStreamResults results = createResults(
                createSingleNodeResult(NodeId.of(0), node1EventStreamDir, false),
                createSingleNodeResult(NodeId.of(1), node2EventStreamDir, false));

        assertThatThrownBy(() -> assertThat(results).haveEqualFiles())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Difference between min")
                .hasMessageContaining("and max")
                .hasMessageContaining("signature file count is greater than 1");
    }

    @Test
    void testHaveEqualFiles_withEqualFiles_shouldPass() throws IOException {
        final String content1 = "event stream content 1";
        final String content2 = "event stream content 2";

        // Create identical files on both nodes
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", content1);
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", content2);

        createEventStreamFileWithSignature(node2EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", content1);
        createEventStreamFileWithSignature(node2EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", content2);

        final MultipleNodeEventStreamResults results = createResults(
                createSingleNodeResult(NodeId.of(0), node1EventStreamDir, false),
                createSingleNodeResult(NodeId.of(1), node2EventStreamDir, false));

        assertThatCode(() -> assertThat(results).haveEqualFiles()).doesNotThrowAnyException();
    }

    @Test
    void testHaveEqualFiles_withDifferentEventStreamFileNames_shouldFail() throws IOException {
        // Create files with different names
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");
        createEventStreamFileWithSignature(node2EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", "content1");

        final MultipleNodeEventStreamResults results = createResults(
                createSingleNodeResult(NodeId.of(0), node1EventStreamDir, false),
                createSingleNodeResult(NodeId.of(1), node2EventStreamDir, false));

        assertThatThrownBy(() -> assertThat(results).haveEqualFiles())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Event stream file")
                .hasMessageContaining("has a different name");
    }

    @Test
    void testHaveEqualFiles_withDifferentEventStreamFileContent_shouldFail() throws IOException {
        // Create files with same name but different content
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");
        createEventStreamFileWithSignature(node2EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content2");

        final MultipleNodeEventStreamResults results = createResults(
                createSingleNodeResult(NodeId.of(0), node1EventStreamDir, false),
                createSingleNodeResult(NodeId.of(1), node2EventStreamDir, false));

        assertThatThrownBy(() -> assertThat(results).haveEqualFiles())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Event stream file")
                .hasMessageContaining("differs between node");
    }

    @Test
    void testHaveEqualFiles_withDifferentSignatureFileNames_shouldFail() throws IOException {
        // Create event stream files with matching names and content
        createEventStreamFile(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");
        createEventStreamFile(node2EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");

        // Create signature files with different names
        createSignatureFile(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts_sig", 100);
        createSignatureFile(node2EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts_sig", 100);

        final MultipleNodeEventStreamResults results = createResults(
                createSingleNodeResult(NodeId.of(0), node1EventStreamDir, false),
                createSingleNodeResult(NodeId.of(1), node2EventStreamDir, false));

        assertThatThrownBy(() -> assertThat(results).haveEqualFiles())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Signature file")
                .hasMessageContaining("has a different name");
    }

    @Test
    void testHaveEqualFiles_withDifferentSignatureFileSizes_shouldFail() throws IOException {
        // Create identical event stream files
        createEventStreamFile(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");
        createEventStreamFile(node2EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");

        // Create signature files with different sizes
        createSignatureFile(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts_sig", 100);
        createSignatureFile(node2EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts_sig", 200);

        final MultipleNodeEventStreamResults results = createResults(
                createSingleNodeResult(NodeId.of(0), node1EventStreamDir, false),
                createSingleNodeResult(NodeId.of(1), node2EventStreamDir, false));

        assertThatThrownBy(() -> assertThat(results).haveEqualFiles())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Signature file")
                .hasMessageContaining("differs between node");
    }

    @Test
    void testHaveEqualFiles_withReconnectedNode_missingFiles_shouldPass() throws IOException {
        // Node 1 (blueprint) has 3 files
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", "content2");
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T02_00_00.000000000Z.evts", "content3");

        // Node 2 (reconnected) has only 2 files (missing the first one)
        createEventStreamFileWithSignature(node2EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", "content2");
        createEventStreamFileWithSignature(node2EventStreamDir, "2024-01-01T02_00_00.000000000Z.evts", "content3");

        final MultipleNodeEventStreamResults results = createResults(
                createSingleNodeResult(NodeId.of(0), node1EventStreamDir, false),
                createSingleNodeResult(NodeId.of(1), node2EventStreamDir, true));

        assertThatCode(() -> assertThat(results).haveEqualFiles()).doesNotThrowAnyException();
    }

    @Test
    void testHaveEqualFiles_withReconnectedNode_differentContent_shouldFail() throws IOException {
        // Node 1 (blueprint) has files
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", "content2");

        // Node 2 (reconnected) has matching names but different content
        createEventStreamFileWithSignature(node2EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", "different");

        final MultipleNodeEventStreamResults results = createResults(
                createSingleNodeResult(NodeId.of(0), node1EventStreamDir, false),
                createSingleNodeResult(NodeId.of(1), node2EventStreamDir, true));

        assertThatThrownBy(() -> assertThat(results).haveEqualFiles())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Event stream file")
                .hasMessageContaining("differs between node");
    }

    @Test
    void testHaveEqualFiles_withMultipleNodes_shouldPass() throws IOException {
        final String content1 = "event stream content 1";
        final String content2 = "event stream content 2";

        // Create identical files on all three nodes
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", content1);
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", content2);

        createEventStreamFileWithSignature(node2EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", content1);
        createEventStreamFileWithSignature(node2EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", content2);

        createEventStreamFileWithSignature(node3EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", content1);
        createEventStreamFileWithSignature(node3EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", content2);

        final MultipleNodeEventStreamResults results = createResults(
                createSingleNodeResult(NodeId.of(0), node1EventStreamDir, false),
                createSingleNodeResult(NodeId.of(1), node2EventStreamDir, false),
                createSingleNodeResult(NodeId.of(2), node3EventStreamDir, false));

        assertThatCode(() -> assertThat(results).haveEqualFiles()).doesNotThrowAnyException();
    }

    @Test
    void testHaveEqualFiles_withMixedReconnectedAndNormalNodes_shouldPass() throws IOException {
        final String content1 = "event stream content 1";
        final String content2 = "event stream content 2";
        final String content3 = "event stream content 3";

        // Node 1 (normal) has all files
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", content1);
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", content2);
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T02_00_00.000000000Z.evts", content3);

        // Node 2 (normal) has all files
        createEventStreamFileWithSignature(node2EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", content1);
        createEventStreamFileWithSignature(node2EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", content2);
        createEventStreamFileWithSignature(node2EventStreamDir, "2024-01-01T02_00_00.000000000Z.evts", content3);

        // Node 3 (reconnected) is missing the first file
        createEventStreamFileWithSignature(node3EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", content2);
        createEventStreamFileWithSignature(node3EventStreamDir, "2024-01-01T02_00_00.000000000Z.evts", content3);

        final MultipleNodeEventStreamResults results = createResults(
                createSingleNodeResult(NodeId.of(0), node1EventStreamDir, false),
                createSingleNodeResult(NodeId.of(1), node2EventStreamDir, false),
                createSingleNodeResult(NodeId.of(2), node3EventStreamDir, true));

        assertThatCode(() -> assertThat(results).haveEqualFiles()).doesNotThrowAnyException();
    }

    @Test
    void testHaveEqualFiles_withReconnectedSignatureFileSizeDifference_shouldFail() throws IOException {
        // Node 1 (blueprint) has files
        createEventStreamFile(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");
        createSignatureFile(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts_sig", 100);

        // Node 2 (reconnected) has matching file but different signature size
        createEventStreamFile(node2EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");
        createSignatureFile(node2EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts_sig", 200);

        final MultipleNodeEventStreamResults results = createResults(
                createSingleNodeResult(NodeId.of(0), node1EventStreamDir, false),
                createSingleNodeResult(NodeId.of(1), node2EventStreamDir, true));

        assertThatThrownBy(() -> assertThat(results).haveEqualFiles())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Signature file")
                .hasMessageContaining("differs between node");
    }

    // Helper methods

    private void createEventStreamFileWithSignature(final Path dir, final String fileName, final String content)
            throws IOException {
        createEventStreamFile(dir, fileName, content);
        createSignatureFile(dir, fileName + "_sig", 100);
    }

    private void createEventStreamFile(final Path dir, final String fileName, final String content)
            throws IOException {
        final Path file = dir.resolve(fileName);
        Files.writeString(file, content);
    }

    private void createSignatureFile(final Path dir, final String fileName, final int size) throws IOException {
        final Path file = dir.resolve(fileName);
        final byte[] data = new byte[size];
        Files.write(file, data);
    }

    private MultipleNodeEventStreamResults createResults(final SingleNodeEventStreamResult... results) {
        // Create a mock MultipleNodeEventStreamResults using the actual implementation constructor
        // Since the constructor that takes List<SingleNodeEventStreamResult> is private,
        // we create an anonymous implementation instead
        return new MultipleNodeEventStreamResults() {
            @Override
            public List<SingleNodeEventStreamResult> results() {
                return List.of(results);
            }

            @Override
            public MultipleNodeEventStreamResults suppressingNode(final NodeId nodeId) {
                throw new UnsupportedOperationException("Not needed for tests");
            }

            @Override
            public MultipleNodeEventStreamResults suppressingNodes(final Collection<Node> nodes) {
                throw new UnsupportedOperationException("Not needed for tests");
            }

            @Override
            public MultipleNodeEventStreamResults withReconnectedNodes(final Collection<Node> nodes) {
                throw new UnsupportedOperationException("Not needed for tests");
            }
        };
    }

    private SingleNodeEventStreamResult createSingleNodeResult(
            final NodeId nodeId, final Path eventStreamDir, final boolean reconnected) {
        return new TestSingleNodeEventStreamResult(nodeId, eventStreamDir, reconnected);
    }

    /**
     * Test implementation of SingleNodeEventStreamResult that reads from a directory
     */
    private static class TestSingleNodeEventStreamResult implements SingleNodeEventStreamResult {
        private final NodeId nodeId;
        private final Path eventStreamDir;
        private final boolean reconnected;

        TestSingleNodeEventStreamResult(final NodeId nodeId, final Path eventStreamDir, final boolean reconnected) {
            this.nodeId = nodeId;
            this.eventStreamDir = eventStreamDir;
            this.reconnected = reconnected;
        }

        @Override
        public NodeId nodeId() {
            return nodeId;
        }

        @Override
        public List<Path> eventStreamFiles() {
            try {
                return Files.list(eventStreamDir)
                        .filter(path -> path.toString().endsWith(".evts"))
                        .sorted()
                        .toList();
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public List<Path> signatureFiles() {
            try {
                return Files.list(eventStreamDir)
                        .filter(path -> path.toString().endsWith(".evts_sig"))
                        .sorted()
                        .toList();
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public SingleNodeEventStreamResult markReconnected() {
            return new TestSingleNodeEventStreamResult(nodeId, eventStreamDir, true);
        }

        @Override
        public boolean hasNotReconnected() {
            return !reconnected;
        }
    }
}
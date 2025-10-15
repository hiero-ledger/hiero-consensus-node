// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.otter.fixtures.assertions.MultipleNodeEventStreamResultsAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.hiero.consensus.config.EventConfig_;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.internal.result.MultipleNodeEventStreamResultsImpl;
import org.hiero.otter.fixtures.internal.result.SingleNodeEventStreamResultImpl;
import org.hiero.otter.fixtures.result.MultipleNodeEventStreamResults;
import org.hiero.otter.fixtures.result.SingleNodeEventStreamResult;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultipleNodeEventStreamResultsAssertTest {

    private static final Configuration TEST_CONFIGURATION = new TestConfigBuilder()
            .withValue(EventConfig_.ENABLE_EVENT_STREAMING, true)
            .getOrCreateConfig();

    @TempDir
    private Path tempDir;

    private Path node0EventStreamDir;
    private Path node1EventStreamDir;
    private Path node2EventStreamDir;

    @BeforeEach
    void setUp() throws IOException {
        node0EventStreamDir = tempDir.resolve("events_0");
        node1EventStreamDir = tempDir.resolve("events_1");
        node2EventStreamDir = tempDir.resolve("events_2");

        Files.createDirectories(node0EventStreamDir);
        Files.createDirectories(node1EventStreamDir);
        Files.createDirectories(node2EventStreamDir);
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
        createEventStreamFile(node0EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");
        createEventStreamFile(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");

        final MultipleNodeEventStreamResults results =
                createResults(createSingleNodeResult(NodeId.of(0), 0), createSingleNodeResult(NodeId.of(1), 0));

        assertThatThrownBy(() -> assertThat(results).haveEqualFiles())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("No signature files found");
    }

    @Test
    void testHaveEqualFiles_withEqualFiles_shouldPass() throws IOException {
        final String content1 = "event stream content 1";
        final String content2 = "event stream content 2";

        // Create identical files on both nodes
        createEventStreamFileWithSignature(node0EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", content1);
        createEventStreamFileWithSignature(node0EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", content2);

        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", content1);
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", content2);

        final MultipleNodeEventStreamResults results =
                createResults(createSingleNodeResult(NodeId.of(0), 0), createSingleNodeResult(NodeId.of(1), 0));

        assertThatCode(() -> assertThat(results).haveEqualFiles()).doesNotThrowAnyException();
    }

    @Test
    void testHaveEqualFiles_withDifferentEventStreamFileNames_shouldFail() throws IOException {
        // Create files with different names
        createEventStreamFileWithSignature(node0EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", "content1");

        final MultipleNodeEventStreamResults results =
                createResults(createSingleNodeResult(NodeId.of(0), 0), createSingleNodeResult(NodeId.of(1), 0));

        assertThatThrownBy(() -> assertThat(results).haveEqualFiles())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Event stream file")
                .hasMessageContaining("has a different name");
    }

    @Test
    void testHaveEqualFiles_withDifferentEventStreamFileContent_shouldFail() throws IOException {
        // Create files with same name but different content
        createEventStreamFileWithSignature(node0EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "different");

        final MultipleNodeEventStreamResults results =
                createResults(createSingleNodeResult(NodeId.of(0), 0), createSingleNodeResult(NodeId.of(1), 0));

        assertThatThrownBy(() -> assertThat(results).haveEqualFiles())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Event stream file")
                .hasMessageContaining("differs between node");
    }

    @Test
    void testHaveEqualFiles_withDifferentSignatureFileNames_shouldFail() throws IOException {
        // Create event stream files with matching names and content
        createEventStreamFile(node0EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");
        createEventStreamFile(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");

        // Create signature files with different names
        createSignatureFile(node0EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts_sig", 100);
        createSignatureFile(node1EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts_sig", 100);

        final MultipleNodeEventStreamResults results =
                createResults(createSingleNodeResult(NodeId.of(0), 0), createSingleNodeResult(NodeId.of(1), 0));

        assertThatThrownBy(() -> assertThat(results).haveEqualFiles())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Signature file")
                .hasMessageContaining("has a different name");
    }

    @Test
    void testHaveEqualFiles_withDifferentSignatureFileSizes_shouldFail() throws IOException {
        // Create identical event stream files
        createEventStreamFile(node0EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");
        createEventStreamFile(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");

        // Create signature files with different sizes
        createSignatureFile(node0EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts_sig", 100);
        createSignatureFile(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts_sig", 200);

        final MultipleNodeEventStreamResults results =
                createResults(createSingleNodeResult(NodeId.of(0), 0), createSingleNodeResult(NodeId.of(1), 0));

        assertThatThrownBy(() -> assertThat(results).haveEqualFiles())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Signature file")
                .hasMessageContaining("differs between node");
    }

    @Test
    void testHaveEqualFiles_withReconnectedNode_missingFiles_shouldPass() throws IOException {
        // Node 1 (blueprint) has 3 files
        createEventStreamFileWithSignature(node0EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");
        createEventStreamFileWithSignature(node0EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", "content2");
        createEventStreamFileWithSignature(node0EventStreamDir, "2024-01-01T02_00_00.000000000Z.evts", "content3");

        // Node 2 (reconnected) has only 2 files (missing the first one)
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", "content2");
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T02_00_00.000000000Z.evts", "content3");

        final MultipleNodeEventStreamResults results =
                createResults(createSingleNodeResult(NodeId.of(0), 0), createSingleNodeResult(NodeId.of(1), 1));

        assertThatCode(() -> assertThat(results).haveEqualFiles()).doesNotThrowAnyException();
    }

    @Test
    void testHaveEqualFiles_withReconnectedNode_differentContent_shouldFail() throws IOException {
        // Node 1 (blueprint) has files
        createEventStreamFileWithSignature(node0EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");
        createEventStreamFileWithSignature(node0EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", "content2");
        createEventStreamFileWithSignature(node0EventStreamDir, "2024-01-01T02_00_00.000000000Z.evts", "content3");

        // Node 2 (reconnected) has matching names but different content
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", "different");
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T02_00_00.000000000Z.evts", "content3");

        final MultipleNodeEventStreamResults results =
                createResults(createSingleNodeResult(NodeId.of(0), 0), createSingleNodeResult(NodeId.of(1), 1));

        assertThatThrownBy(() -> assertThat(results).haveEqualFiles())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Event stream file")
                .hasMessageContaining("differs between node");
    }

    @Test
    void testHaveEqualFiles_withReconnectedNode_differentContentDuringReconnect_shouldPass() throws IOException {
        // Node 1 (blueprint) has files
        createEventStreamFileWithSignature(node0EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");
        createEventStreamFileWithSignature(node0EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", "content2");

        // Node 2 (reconnected) has matching names but different content in the last file
        createEventStreamFile(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "different1");
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T01_00_05.000000000Z.evts", "different2");

        final MultipleNodeEventStreamResults results =
                createResults(createSingleNodeResult(NodeId.of(0), 0), createSingleNodeResult(NodeId.of(1), 1));

        assertThatCode(() -> assertThat(results).haveEqualFiles()).doesNotThrowAnyException();
    }

    @Test
    void testHaveEqualFiles_withReconnectedNode_differentContentAfterReconnect_shouldPass() throws IOException {
        // Node 1 (blueprint) has files
        createEventStreamFileWithSignature(node0EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");
        createEventStreamFileWithSignature(node0EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", "content2");
        createEventStreamFileWithSignature(node0EventStreamDir, "2024-01-01T02_00_00.000000000Z.evts", "content3");

        // Node 2 (reconnected) has matching names but different content in the last file
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T01_00_05.000000000Z.evts", "different2");
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T02_00_00.000000000Z.evts", "content3");

        final MultipleNodeEventStreamResults results =
                createResults(createSingleNodeResult(NodeId.of(0), 0), createSingleNodeResult(NodeId.of(1), 1));

        assertThatCode(() -> assertThat(results).haveEqualFiles()).doesNotThrowAnyException();
    }

    @Test
    void testHaveEqualFiles_withMultipleNodes_shouldPass() throws IOException {
        final String content1 = "event stream content 1";
        final String content2 = "event stream content 2";

        // Create identical files on all three nodes
        createEventStreamFileWithSignature(node0EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", content1);
        createEventStreamFileWithSignature(node0EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", content2);

        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", content1);
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", content2);

        createEventStreamFileWithSignature(node2EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", content1);
        createEventStreamFileWithSignature(node2EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", content2);

        final MultipleNodeEventStreamResults results = createResults(
                createSingleNodeResult(NodeId.of(0), 0),
                createSingleNodeResult(NodeId.of(1), 0),
                createSingleNodeResult(NodeId.of(2), 0));

        assertThatCode(() -> assertThat(results).haveEqualFiles()).doesNotThrowAnyException();
    }

    @Test
    void testHaveEqualFiles_withMixedReconnectedAndNormalNodes_shouldPass() throws IOException {
        final String content1 = "event stream content 1";
        final String content2 = "event stream content 2";
        final String content3 = "event stream content 3";

        // Node 1 (normal) has all files
        createEventStreamFileWithSignature(node0EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", content1);
        createEventStreamFileWithSignature(node0EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", content2);
        createEventStreamFileWithSignature(node0EventStreamDir, "2024-01-01T02_00_00.000000000Z.evts", content3);

        // Node 2 (normal) has all files
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", content1);
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", content2);
        createEventStreamFileWithSignature(node1EventStreamDir, "2024-01-01T02_00_00.000000000Z.evts", content3);

        // Node 3 (reconnected) has different first file
        createEventStreamFileWithSignature(node2EventStreamDir, "2024-01-01T01_00_05.000000000Z.evts", "different");
        createEventStreamFileWithSignature(node2EventStreamDir, "2024-01-01T01_00_00.000000000Z.evts", content2);
        createEventStreamFileWithSignature(node2EventStreamDir, "2024-01-01T02_00_00.000000000Z.evts", content3);

        final MultipleNodeEventStreamResults results = createResults(
                createSingleNodeResult(NodeId.of(0), 0),
                createSingleNodeResult(NodeId.of(1), 0),
                createSingleNodeResult(NodeId.of(2), 1));

        assertThatCode(() -> assertThat(results).haveEqualFiles()).doesNotThrowAnyException();
    }

    @Test
    void testHaveEqualFiles_withReconnectedSignatureFileSizeDifference_shouldFail() throws IOException {
        // Node 1 (blueprint) has files
        createEventStreamFile(node0EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");
        createSignatureFile(node0EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts_sig", 100);

        // Node 2 (reconnected) has matching file but different signature size
        createEventStreamFile(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts", "content1");
        createSignatureFile(node1EventStreamDir, "2024-01-01T00_00_00.000000000Z.evts_sig", 200);

        final MultipleNodeEventStreamResults results = new MultipleNodeEventStreamResultsImpl(
                List.of(createSingleNodeResult(NodeId.of(0), 0), createSingleNodeResult(NodeId.of(1), 1)));

        assertThatThrownBy(() -> assertThat(results).haveEqualFiles())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Signature file")
                .hasMessageContaining("differs between node");
    }

    // Helper methods
    private void createEventStreamFileWithSignature(
            @NonNull final Path dir, @NonNull final String fileName, @NonNull final String content) throws IOException {
        createEventStreamFile(dir, fileName, content);
        createSignatureFile(dir, fileName + "_sig", 100);
    }

    private void createEventStreamFile(
            @NonNull final Path dir, @NonNull final String fileName, @NonNull final String content) throws IOException {
        final Path file = dir.resolve(fileName);
        Files.writeString(file, content);
    }

    private void createSignatureFile(@NonNull final Path dir, @NonNull final String fileName, final int size)
            throws IOException {
        final Path file = dir.resolve(fileName);
        final byte[] data = new byte[size];
        Files.write(file, data);
    }

    @NonNull
    private MultipleNodeEventStreamResults createResults(@NonNull final SingleNodeEventStreamResult... results) {
        return new MultipleNodeEventStreamResultsImpl(List.of(results));
    }

    @NonNull
    private SingleNodeEventStreamResult createSingleNodeResult(
            @NonNull final NodeId nodeId, final int numberOfReconnects) {
        final SingleNodeReconnectResult reconnectResult = mock(SingleNodeReconnectResult.class);
        when(reconnectResult.nodeId()).thenReturn(nodeId);
        when(reconnectResult.numSuccessfulReconnects()).thenReturn(numberOfReconnects);
        return new SingleNodeEventStreamResultImpl(nodeId, tempDir, TEST_CONFIGURATION, reconnectResult);
    }
}

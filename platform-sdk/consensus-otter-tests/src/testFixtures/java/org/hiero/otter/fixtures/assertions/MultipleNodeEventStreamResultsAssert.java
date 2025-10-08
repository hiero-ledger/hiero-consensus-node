// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.assertj.core.api.Assertions.fail;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.result.MultipleNodeEventStreamResults;
import org.hiero.otter.fixtures.result.SingleNodeEventStreamResult;

/**
 * Assertions for {@link MultipleNodeEventStreamResults}.
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class MultipleNodeEventStreamResultsAssert
        extends AbstractAssert<MultipleNodeEventStreamResultsAssert, MultipleNodeEventStreamResults> {

    /**
     * Creates a new instance of {@link MultipleNodeEventStreamResultsAssert}
     *
     * @param actual the actual {@link MultipleNodeEventStreamResults} to assert
     */
    public MultipleNodeEventStreamResultsAssert(@Nullable final MultipleNodeEventStreamResults actual) {
        super(actual, MultipleNodeEventStreamResultsAssert.class);
    }

    /**
     * Creates an assertion for the given {@link MultipleNodeEventStreamResults}.
     *
     * @param actual the {@link MultipleNodeEventStreamResults} to assert
     * @return an assertion for the given {@link MultipleNodeEventStreamResults}
     */
    @NonNull
    public static MultipleNodeEventStreamResultsAssert assertThat(
            @Nullable final MultipleNodeEventStreamResults actual) {
        return new MultipleNodeEventStreamResultsAssert(actual);
    }

    /**
     * Identifies the rounds which have reached consensus on more than one node and verifies that they are equal. If no
     * rounds have been produced or if only one node has produced rounds, this assertion will always pass.
     *
     * <p>Please note: this method will fail if no event stream files or signature files are found.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeEventStreamResultsAssert haveEqualFiles() {
        isNotNull();

        final List<List<Path>> allEventStreamFiles = actual.results().stream()
                .map(SingleNodeEventStreamResult::eventStreamFiles)
                .toList();
        assert !allEventStreamFiles.isEmpty(); // MultipleNodeEventStreamResults requires at least one node

        final List<List<Path>> allSignatureFiles = actual.results().stream()
                .map(SingleNodeEventStreamResult::signatureFiles)
                .toList();
        assert !allSignatureFiles.isEmpty(); // MultipleNodeEventStreamResults requires at least one node

        // Use the first node as the blueprint for comparison.
        final int nodeCount = actual.results().size();
        final int minSignatureFileCount =
                allSignatureFiles.stream().mapToInt(List::size).min().orElseThrow();
        final int maxSignatureFileCount =
                allSignatureFiles.stream().mapToInt(List::size).max().orElseThrow();

        if (maxSignatureFileCount == 0) {
            fail("No signature files found for any node");
        }
        if (maxSignatureFileCount - minSignatureFileCount > 1) {
            fail(
                    "Difference between min (%d) and max (%d) signature file count is greater than 1",
                    minSignatureFileCount, maxSignatureFileCount);
        }

        // pick a node with the maximum number of signature files as the blueprint
        // we will compare all other nodes with this one
        final int bluePrintIndex = IntStream.range(0, nodeCount)
                .filter(i -> allSignatureFiles.get(i).size() == maxSignatureFileCount)
                .findAny()
                .orElseThrow();
        final NodeId bluePrintNodeId = actual.results().get(bluePrintIndex).nodeId();
        final List<Path> bluePrintEventStreamFiles = allEventStreamFiles.get(bluePrintIndex);
        final List<Path> bluePrintSignatureFiles = allSignatureFiles.get(bluePrintIndex);

        try {
            for (int nodeIndex = 0; nodeIndex < nodeCount; nodeIndex++) {
                if (nodeIndex == bluePrintIndex) {
                    continue;
                }

                // get current node info
                final NodeId currentNodeId = actual.results().get(nodeIndex).nodeId();
                final List<Path> currentEventStreamFiles = allEventStreamFiles.get(nodeIndex);
                final List<Path> currentSignatureFiles = allSignatureFiles.get(nodeIndex);
                final int fileCount = currentSignatureFiles.size();

                // compare event stream files
                for (int fileIndex = 0; fileIndex < fileCount; fileIndex++) {
                    final Path bluePrintFile = bluePrintEventStreamFiles.get(fileIndex);
                    final Path currentFile = currentEventStreamFiles.get(fileIndex);
                    if (!Objects.equals(currentFile.getFileName(), bluePrintFile.getFileName())) {
                        fail(
                                "Event stream file %s of node %s has a different name than the corresponding file %s of node %s",
                                currentFile.getFileName(), currentNodeId, bluePrintFile.getFileName(), bluePrintNodeId);
                    }
                    Assertions.assertThat(currentFile)
                            .withFailMessage(
                                    "Event stream file %s differs between node %s and node %s",
                                    currentFile.getFileName(), currentNodeId, bluePrintNodeId)
                            .hasSameBinaryContentAs(bluePrintFile);
                }

                // compare signature files
                for (int fileIndex = 0; fileIndex < fileCount; fileIndex++) {
                    final Path bluePrintFile = bluePrintSignatureFiles.get(fileIndex);
                    final long bluePrintFileSize = Files.size(bluePrintFile);
                    final Path currentFile = currentSignatureFiles.get(fileIndex);
                    if (!Objects.equals(currentFile.getFileName(), bluePrintFile.getFileName())) {
                        fail(
                                "Signature file %s of node %s has a different name than the corresponding file %s of node %s",
                                currentFile.getFileName(), currentNodeId, bluePrintFile.getFileName(), bluePrintNodeId);
                    }
                    Assertions.assertThat(currentFile)
                            .withFailMessage(
                                    "Signature file %s differs between node %s and node %s",
                                    currentFile.getFileName(), currentNodeId, bluePrintNodeId)
                            .hasSize(bluePrintFileSize);
                }
            }
            //            // Compare event stream files and signature files of all other nodes to the blueprint node.
            //            for (int nodeIndex = 1; nodeIndex < nodeCount; nodeIndex++) {
            //                final NodeId currentNodeId = actual.results().get(nodeIndex).nodeId();
            //
            //                // compare event stream files
            //                final List<Path> currentEventStreamFiles = allEventStreamFiles.get(nodeIndex);
            //                if (currentEventStreamFiles.size() != eventStreamCount) {
            //                    fail("Node %s has a different number of event stream files (%d) than node %s (%d)",
            //                            bluePrintNodeId, eventStreamCount, currentNodeId,
            // currentEventStreamFiles.size());
            //                }
            //                for (int fileIndex = 0; fileIndex < eventStreamCount - 1; fileIndex++) {
            //                    final Path bluePrintFile = bluePrintEventStreamFiles.get(fileIndex);
            //                    final Path currentFile = currentEventStreamFiles.get(fileIndex);
            //                    if (!Objects.equals(currentFile.getFileName(), bluePrintFile.getFileName())) {
            //                        fail("Event stream file %s of node %s has a different name than the corresponding
            // file %s of node %s",
            //                                currentFile.getFileName(), currentNodeId, bluePrintFile.getFileName(),
            // bluePrintNodeId);
            //                    }
            //                    Assertions.assertThat(currentFile)
            //                            .withFailMessage("Event stream file %s differs between node %s and node %s",
            //                                    currentFile.getFileName(), currentNodeId, bluePrintNodeId)
            //                            .hasSameBinaryContentAs(bluePrintFile);
            //                }
            //
            //                // compare signature files
            //                final List<Path> currentSignatureFiles = allSignatureFiles.get(nodeIndex);
            //                if (currentSignatureFiles.size() != signatureFileCount) {
            //                    fail("Node %s has a different number of signature files (%d) than node %s (%d)",
            //                            bluePrintNodeId, signatureFileCount, currentNodeId,
            // currentSignatureFiles.size());
            //                }
            //                for (int fileIndex = 0; fileIndex < signatureFileCount; fileIndex++) {
            //                    final Path bluePrintFile = bluePrintSignatureFiles.get(fileIndex);
            //                    final long bluePrintFileSize = Files.size(bluePrintFile);
            //                    final Path currentFile = currentSignatureFiles.get(fileIndex);
            //                    if (!Objects.equals(currentFile.getFileName(), bluePrintFile.getFileName())) {
            //                        fail("Signature file %s of node %s has a different name than the corresponding
            // file %s of node %s",
            //                                currentFile.getFileName(), currentNodeId, bluePrintFile.getFileName(),
            // bluePrintNodeId);
            //                    }
            //                    Assertions.assertThat(currentFile)
            //                            .withFailMessage("Signature file %s differs between node %s and node %s",
            //                                    currentFile.getFileName(), currentNodeId, bluePrintNodeId)
            //                            .hasSize(bluePrintFileSize);
            //                }
            //            }
        } catch (final IOException e) {
            fail("I/O error when comparing files", e);
        }

        return this;
    }
}

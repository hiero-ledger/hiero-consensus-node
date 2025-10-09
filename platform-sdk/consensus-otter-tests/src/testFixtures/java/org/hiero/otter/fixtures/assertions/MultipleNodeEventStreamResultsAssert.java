// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.assertj.core.api.Assertions.fail;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IntSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
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

        final int nodeCount = actual.results().size();

        // determine statistics of signature file counts (ignoring nodes that have reconnected)
        final IntSummaryStatistics statistics = actual.results().stream()
                .filter(SingleNodeEventStreamResult::hasNotReconnected)
                .map(SingleNodeEventStreamResult::signatureFiles)
                .mapToInt(List::size)
                .summaryStatistics();
        if (statistics.getMax() == 0) {
            fail("No signature files found for any node");
        }
        if (statistics.getMax() - statistics.getMin() > 1) {
            fail(
                    "Difference between min (%d) and max (%d) signature file count is greater than 1",
                    statistics.getMin(), statistics.getMax());
        }

        // pick a node with the maximum number of signature files that has not reconnected
        // this will be our blueprint and we will compare all other nodes with this one
        final SingleNodeEventStreamResult bluePrint = actual.results().stream()
                .filter(SingleNodeEventStreamResult::hasNotReconnected)
                .filter(result -> result.signatureFiles().size() == statistics.getMax())
                .findAny()
                .orElseThrow();

        final List<Path> bluePrintEventStreamFiles = bluePrint.eventStreamFiles();
        final Map<Path, Path> bluePrintEventStreamFileLookup = bluePrintEventStreamFiles.stream()
                .collect(Collectors.toMap(Path::getFileName, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        final List<Path> bluePrintSignatureFiles = bluePrint.signatureFiles();
        final Map<Path, Path> bluePrintSignatureFileLookup =
                bluePrintSignatureFiles.stream().collect(Collectors.toMap(Path::getFileName, Function.identity()));

        try {
            for (final SingleNodeEventStreamResult result : actual.results()) {
                if (result.nodeId().equals(bluePrint.nodeId())) {
                    continue;
                }
                if (result.hasNotReconnected()) {
                    compareEventStreamFiles(bluePrint.nodeId(), bluePrintEventStreamFiles, result);
                    compareSignatureFiles(bluePrint.nodeId(), bluePrintSignatureFiles, result);
                } else {
                    compareReconnectedEventStreamFiles(bluePrint.nodeId(), bluePrintEventStreamFileLookup, result);
                    compareReconnectedSignatureFiles(bluePrint.nodeId(), bluePrintSignatureFileLookup, result);
                }
            }
        } catch (final IOException e) {
            fail("I/O error when comparing files", e);
        }

        return this;
    }

    private void compareEventStreamFiles(
            @NonNull final NodeId bluePrintNodeId,
            @NonNull final List<Path> bluePrintFiles,
            @NonNull final SingleNodeEventStreamResult current) {

        final int fileCount = current.eventStreamFiles().size();
        final NodeId currentNodeId = current.nodeId();
        final List<Path> currentFiles = current.eventStreamFiles();

        for (int fileIndex = 0; fileIndex < fileCount; fileIndex++) {
            final Path bluePrintFile = bluePrintFiles.get(fileIndex);
            final Path bluePrintFileName = bluePrintFile.getFileName();
            final Path currentFile = currentFiles.get(fileIndex);
            final Path currentFileName = currentFile.getFileName();
            if (!Objects.equals(bluePrintFileName, currentFileName)) {
                fail(
                        "Event stream file %s of node %s has a different name than the corresponding file %s of node %s",
                        bluePrintFileName, bluePrintNodeId, currentFileName, currentNodeId);
            }

            Assertions.assertThat(currentFile)
                    .withFailMessage(
                            "Event stream file %s differs between node %s and node %s",
                            bluePrintFileName, currentNodeId, bluePrintNodeId)
                    .hasSameBinaryContentAs(bluePrintFile);
        }
    }

    private void compareSignatureFiles(
            @NonNull final NodeId bluePrintNodeId,
            @NonNull final List<Path> bluePrintFiles,
            @NonNull final SingleNodeEventStreamResult current)
            throws IOException {

        final int fileCount = current.signatureFiles().size();
        final NodeId currentNodeId = current.nodeId();
        final List<Path> currentFiles = current.signatureFiles();

        for (int fileIndex = 0; fileIndex < fileCount; fileIndex++) {
            final Path bluePrintFile = bluePrintFiles.get(fileIndex);
            final Path bluePrintFileName = bluePrintFile.getFileName();
            final Path currentFile = currentFiles.get(fileIndex);
            final Path currentFileName = currentFile.getFileName();
            if (!Objects.equals(currentFileName, bluePrintFileName)) {
                fail(
                        "Signature file %s of node %s has a different name than the corresponding file %s of node %s",
                        currentFileName, currentNodeId, bluePrintFileName, bluePrintNodeId);
            }

            final long bluePrintFileSize = Files.size(bluePrintFile);
            Assertions.assertThat(currentFile)
                    .withFailMessage(
                            "Signature file %s differs between node %s and node %s",
                            bluePrintFileName, currentNodeId, bluePrintNodeId)
                    .hasSize(bluePrintFileSize);
        }
    }

    private void compareReconnectedEventStreamFiles(
            @NonNull final NodeId bluePrintNodeId,
            @NonNull final Map<Path, Path> bluePrintFileLookup,
            @NonNull final SingleNodeEventStreamResult current) {
        // A reconnected node may be missing some event stream files.
        // We only compare those files that are present on both nodes.
        for (final Path currentFile : current.eventStreamFiles()) {
            final Path bluePrintFile = bluePrintFileLookup.get(currentFile.getFileName());
            if (bluePrintFile != null) {
                Assertions.assertThat(currentFile)
                        .withFailMessage(
                                "Event stream file %s differs between node %s and node %s",
                                currentFile.getFileName(), current.nodeId(), bluePrintNodeId)
                        .hasSameBinaryContentAs(bluePrintFile);
            }
        }
    }

    private void compareReconnectedSignatureFiles(
            @NonNull final NodeId bluePrintNodeId,
            @NonNull final Map<Path, Path> bluePrintFileLookup,
            @NonNull final SingleNodeEventStreamResult current)
            throws IOException {
        // A reconnected node may be missing some signature files.
        // We only compare those files that are present on both nodes.
        for (final Path currentFile : current.signatureFiles()) {
            final Path bluePrintFile = bluePrintFileLookup.get(currentFile.getFileName());
            if (bluePrintFile != null) {
                final long bluePrintFileSize = Files.size(bluePrintFile);
                Assertions.assertThat(currentFile)
                        .withFailMessage(
                                "Signature file %s differs between node %s and node %s",
                                currentFile.getFileName(), current.nodeId(), bluePrintNodeId)
                        .hasSize(bluePrintFileSize);
            }
        }
    }
}

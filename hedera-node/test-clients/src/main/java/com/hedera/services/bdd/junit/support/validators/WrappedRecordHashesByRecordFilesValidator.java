// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators;

import static com.hedera.services.bdd.spec.TargetNetworkType.SUBPROCESS_NETWORK;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashesLog;
import com.hedera.node.app.hapi.utils.exports.recordstreaming.RecordStreamingUtils;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.hedera.ExternalPath;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * CI-focused validator that compares {@code wrapped-record-hashes.pb} across nodes that have
 * produced the <b>same set</b> of record stream files under {@code recordStreams}.
 *
 * <p>Only applies to subprocess networks. For each equivalence class of nodes with identical record stream
 * file sets, this validator parses and compares every wrapped record hash entry by block number and hash bytes.
 */
public class WrappedRecordHashesByRecordFilesValidator {
    private static final Logger log = LogManager.getLogger(WrappedRecordHashesByRecordFilesValidator.class);

    private static final String WRAPPED_RECORD_HASHES_FILE_NAME = "wrapped-record-hashes.pb";

    /**
     * Returns any validation errors as a stream of {@link Throwable}s.
     *
     * <p>No-op unless the spec is running against a subprocess network with multiple nodes.
     */
    public Stream<Throwable> validationErrorsIn(@NonNull final HapiSpec spec) {
        requireNonNull(spec);
        if (spec.targetNetworkType() != SUBPROCESS_NETWORK) {
            return Stream.empty();
        }

        try {
            final var nodes = spec.getNetworkNodes();
            if (nodes.size() < 2) {
                return Stream.empty();
            }

            final Map<RecordFilesSignature, List<HederaNode>> nodesByRecordFiles = new HashMap<>();
            for (final var node : nodes) {
                final var sig = recordFilesSignature(node);
                // If a node has produced no record files, we ignore it for this validator.
                if (sig.relativeRecordFiles().isEmpty()) {
                    log.info("Ignoring node {} due to absence of any record file sigs", node);
                    continue;
                }
                nodesByRecordFiles
                        .computeIfAbsent(sig, ignore -> new ArrayList<>())
                        .add(node);
            }

            final List<Throwable> errors = new ArrayList<>();
            for (final var group : nodesByRecordFiles.values()) {
                if (group.size() < 2) {
                    continue;
                }
                errors.addAll(compareWrappedHashesWithinGroup(group));
            }
            return errors.stream();
        } catch (final Throwable t) {
            return Stream.of(t);
        }
    }

    private static List<Throwable> compareWrappedHashesWithinGroup(@NonNull final List<HederaNode> nodes) {
        requireNonNull(nodes);

        final List<Throwable> errors = new ArrayList<>();

        final Map<Long, Optional<Path>> fileByNodeId = new HashMap<>();
        for (final var node : nodes) {
            fileByNodeId.put(node.getNodeId(), findWrappedHashesFile(node));
        }

        final var nodesWithFile = fileByNodeId.entrySet().stream()
                .filter(e -> e.getValue().isPresent())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (nodesWithFile.isEmpty()) {
            // Feature likely disabled for this run; nothing to validate for this group.
            return List.of();
        }

        final var nodesMissingFile = fileByNodeId.entrySet().stream()
                .filter(e -> e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (!nodesMissingFile.isEmpty()) {
            errors.add(new AssertionError(
                    "Wrapped record hashes validation failed: missing " + WRAPPED_RECORD_HASHES_FILE_NAME
                            + " for node(s) " + nodesMissingFile + " but present for node(s) " + nodesWithFile));
            return errors;
        }

        final var baselineNodeId = nodesWithFile.getFirst();
        final var baselineFile = fileByNodeId.get(baselineNodeId).orElseThrow();
        final var baselineByBlock = indexByBlockNumber(baselineNodeId, readWrappedHashesEntries(baselineFile));

        for (final var nodeId : nodesWithFile) {
            if (nodeId == baselineNodeId) {
                continue;
            }
            final var otherFile = fileByNodeId.get(nodeId).orElseThrow();
            final var otherByBlock = indexByBlockNumber(nodeId, readWrappedHashesEntries(otherFile));
            errors.addAll(compare(baselineNodeId, baselineByBlock, nodeId, otherByBlock));
        }

        return errors;
    }

    private static List<Throwable> compare(
            final long baselineNodeId,
            @NonNull final Map<Long, WrappedRecordFileBlockHashes> baselineByBlock,
            final long otherNodeId,
            @NonNull final Map<Long, WrappedRecordFileBlockHashes> otherByBlock) {
        requireNonNull(baselineByBlock);
        requireNonNull(otherByBlock);

        final List<Throwable> errors = new ArrayList<>();

        if (!otherByBlock.keySet().equals(baselineByBlock.keySet())) {
            final var missing = difference(baselineByBlock.keySet(), otherByBlock.keySet());
            final var extra = difference(otherByBlock.keySet(), baselineByBlock.keySet());
            errors.add(new AssertionError("Wrapped record hashes validation failed: node " + otherNodeId
                    + " has different block set than baseline node " + baselineNodeId + " (missing=" + missing
                    + ", extra=" + extra + ")"));
            return errors;
        }

        // Compare every hash for every block number (bounded error output).
        int mismatches = 0;
        final int maxDetails = 10;
        final StringBuilder details = new StringBuilder();
        for (final var blockNo : baselineByBlock.keySet().stream().sorted().toList()) {
            final var expected = baselineByBlock.get(blockNo);
            final var actual = otherByBlock.get(blockNo);
            if (!equalsBytes(expected.consensusTimestampHash(), actual.consensusTimestampHash())
                    || !equalsBytes(expected.outputItemsTreeRootHash(), actual.outputItemsTreeRootHash())) {
                mismatches++;
                if (mismatches <= maxDetails) {
                    details.append("\n  - block ")
                            .append(blockNo)
                            .append(" expected(consensusTsHash=")
                            .append(expected.consensusTimestampHash().toHex())
                            .append(", outputRoot=")
                            .append(expected.outputItemsTreeRootHash().toHex())
                            .append(") actual(consensusTsHash=")
                            .append(actual.consensusTimestampHash().toHex())
                            .append(", outputRoot=")
                            .append(actual.outputItemsTreeRootHash().toHex())
                            .append(')');
                }
            }
        }

        if (mismatches > 0) {
            errors.add(new AssertionError("Wrapped record hashes validation failed: node " + otherNodeId
                    + " differs from baseline node " + baselineNodeId + " in " + mismatches + " block(s)."
                    + details
                    + (mismatches > maxDetails ? "\n  - (showing first " + maxDetails + " mismatches)" : "")));
        }

        return errors;
    }

    private record RecordFilesSignature(List<String> relativeRecordFiles) {}

    private static RecordFilesSignature recordFilesSignature(@NonNull final HederaNode node) {
        requireNonNull(node);
        final var recordStreamsRoot = Optional.ofNullable(node.getExternalPath(ExternalPath.RECORD_STREAMS_DIR)
                        .toAbsolutePath()
                        .getParent())
                .orElseGet(() -> node.getExternalPath(ExternalPath.WORKING_DIR)
                        .resolve("data")
                        .resolve("recordStreams")
                        .toAbsolutePath());
        if (!Files.exists(recordStreamsRoot)) {
            return new RecordFilesSignature(List.of());
        }
        try {
            final var orderedRecordFiles =
                    RecordStreamingUtils.orderedRecordFilesFrom(recordStreamsRoot.toString(), f -> true);
            final var relative = orderedRecordFiles.stream()
                    .map(Path::of)
                    .map(p -> recordStreamsRoot.relativize(p.toAbsolutePath()).toString())
                    .toList();
            return new RecordFilesSignature(relative);
        } catch (final IOException e) {
            throw new UncheckedIOException(
                    "Unable to list record stream files for node " + node.getNodeId() + " at " + recordStreamsRoot, e);
        }
    }

    private static Optional<Path> findWrappedHashesFile(@NonNull final HederaNode node) {
        requireNonNull(node);
        final var expected = node.getExternalPath(ExternalPath.WRAPPED_RECORD_HASHES_FILE);
        if (Files.exists(expected)) {
            return Optional.of(expected);
        }
        final var dataDir = node.getExternalPath(ExternalPath.WORKING_DIR).resolve("data");
        if (!Files.exists(dataDir)) {
            return Optional.empty();
        }
        try (final var walk = Files.walk(dataDir, 8)) {
            return walk.filter(p -> p.getFileName().toString().equals(WRAPPED_RECORD_HASHES_FILE_NAME))
                    .findFirst();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<WrappedRecordFileBlockHashes> readWrappedHashesEntries(@NonNull final Path file) {
        requireNonNull(file);
        try {
            final var allBytes = Files.readAllBytes(file);
            if (allBytes.length == 0) {
                return List.of();
            }
            final var logMsg = WrappedRecordFileBlockHashesLog.PROTOBUF.parse(Bytes.wrap(allBytes));
            return List.copyOf(logMsg.entries());
        } catch (final ParseException e) {
            log.error("Failed parsing wrapped record hashes file {}", file, e);
            throw new IllegalStateException("Unable to parse " + file, e);
        } catch (final IOException e) {
            throw new UncheckedIOException("Unable to read " + file, e);
        }
    }

    private static Map<Long, WrappedRecordFileBlockHashes> indexByBlockNumber(
            final long nodeId, @NonNull final List<WrappedRecordFileBlockHashes> entries) {
        requireNonNull(entries);
        final Map<Long, WrappedRecordFileBlockHashes> map = new HashMap<>();
        for (final var entry : entries) {
            final var prev = map.put(entry.blockNumber(), entry);
            if (prev != null) {
                throw new IllegalStateException("Duplicate wrapped record hashes entry for block " + entry.blockNumber()
                        + " on node " + nodeId);
            }
        }
        return map;
    }

    private static boolean equalsBytes(@NonNull final Bytes a, @NonNull final Bytes b) {
        return a.equals(b);
    }

    private static Set<Long> difference(@NonNull final Set<Long> a, @NonNull final Set<Long> b) {
        final var d = new HashSet<>(a);
        d.removeAll(b);
        return d;
    }
}

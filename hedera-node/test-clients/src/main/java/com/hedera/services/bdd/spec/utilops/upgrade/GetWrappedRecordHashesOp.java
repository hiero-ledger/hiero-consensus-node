// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.upgrade;

import static com.hedera.services.bdd.junit.hedera.ExternalPath.WRAPPED_RECORD_HASHES_FILE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashesLog;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A utility operation that reads the wrapped record hashes file from every node in the network,
 * verifies that all nodes have identical contents, and exposes the entries via an
 * {@link AtomicReference} for use by downstream operations.
 */
public class GetWrappedRecordHashesOp extends UtilOp {

    private final AtomicReference<List<WrappedRecordFileBlockHashes>> entriesRef;
    private Map<Long, List<WrappedRecordFileBlockHashes>> wrappedRecordHashesByNode;

    public GetWrappedRecordHashesOp(@NonNull final AtomicReference<List<WrappedRecordFileBlockHashes>> entriesRef) {
        this.entriesRef = requireNonNull(entriesRef);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        wrappedRecordHashesByNode = new LinkedHashMap<>();
        for (final var node : spec.targetNetworkOrThrow().nodes()) {
            final var file = node.getExternalPath(WRAPPED_RECORD_HASHES_FILE);
            wrappedRecordHashesByNode.put(node.getNodeId(), parseEntries(file));
        }
        return true;
    }

    @Override
    protected void assertExpectationsGiven(final HapiSpec spec) throws Throwable {
        final var nodeIds = wrappedRecordHashesByNode.keySet().stream().sorted().toList();
        final var baselineId = nodeIds.getFirst();
        final var baselineByBlock = indexByBlockNumber(baselineId, wrappedRecordHashesByNode.get(baselineId));

        for (final var nodeId : nodeIds) {
            if (nodeId.equals(baselineId)) {
                continue;
            }

            final var otherByBlock = indexByBlockNumber(nodeId, wrappedRecordHashesByNode.get(nodeId));
            assertSameBlockSets(baselineId, baselineByBlock.keySet(), nodeId, otherByBlock.keySet());
            assertSameHashes(baselineId, baselineByBlock, nodeId, otherByBlock);
        }
    }

    @Override
    protected void updateStateOf(final HapiSpec spec) throws Throwable {
        final var baselineId =
                wrappedRecordHashesByNode.keySet().stream().min(Long::compare).orElseThrow();
        entriesRef.set(wrappedRecordHashesByNode.get(baselineId));
    }

    private static List<WrappedRecordFileBlockHashes> parseEntries(@NonNull final Path file) {
        requireNonNull(file);
        try {
            final var allBytes = Files.readAllBytes(file);
            if (allBytes.length == 0) {
                return List.of();
            }
            return List.copyOf(WrappedRecordFileBlockHashesLog.PROTOBUF
                    .parse(Bytes.wrap(allBytes))
                    .entries());
        } catch (final ParseException e) {
            throw new IllegalStateException("Unable to parse wrapped record hashes file " + file, e);
        } catch (final IOException e) {
            throw new UncheckedIOException("Unable to read wrapped record hashes file " + file, e);
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

    private static void assertSameBlockSets(
            final long baselineId,
            @NonNull final Set<Long> baselineBlocks,
            final long otherId,
            @NonNull final Set<Long> otherBlocks) {
        if (!baselineBlocks.equals(otherBlocks)) {
            final var missing = difference(baselineBlocks, otherBlocks);
            final var extra = difference(otherBlocks, baselineBlocks);
            throw new AssertionError("Wrapped record hashes mismatch between node " + baselineId + " and node "
                    + otherId + ": missing=" + missing + ", extra=" + extra);
        }
    }

    private static void assertSameHashes(
            final long baselineId,
            @NonNull final Map<Long, WrappedRecordFileBlockHashes> baseline,
            final long otherId,
            @NonNull final Map<Long, WrappedRecordFileBlockHashes> other) {
        int mismatches = 0;
        final int maxDetails = 10;
        final StringBuilder details = new StringBuilder();
        for (final var blockNo : baseline.keySet().stream().sorted().toList()) {
            final var expected = baseline.get(blockNo);
            final var actual = other.get(blockNo);
            if (!expected.consensusTimestampHash().equals(actual.consensusTimestampHash())
                    || !expected.outputItemsTreeRootHash().equals(actual.outputItemsTreeRootHash())) {
                mismatches++;
                if (mismatches <= maxDetails) {
                    details.append("\n  block ")
                            .append(blockNo)
                            .append(": expected(ctHash=")
                            .append(expected.consensusTimestampHash().toHex())
                            .append(", outputRoot=")
                            .append(expected.outputItemsTreeRootHash().toHex())
                            .append(") got(ctHash=")
                            .append(actual.consensusTimestampHash().toHex())
                            .append(", outputRoot=")
                            .append(actual.outputItemsTreeRootHash().toHex())
                            .append(')');
                }
            }
        }
        if (mismatches > 0) {
            throw new AssertionError("Wrapped record hashes differ between node " + baselineId
                    + " and node " + otherId + " in " + mismatches + " block(s):" + details
                    + (mismatches > maxDetails ? "\n  (showing first " + maxDetails + " mismatches)" : ""));
        }
    }

    private static Set<Long> difference(@NonNull final Set<Long> a, @NonNull final Set<Long> b) {
        final var d = new HashSet<>(a);
        d.removeAll(b);
        return d;
    }
}

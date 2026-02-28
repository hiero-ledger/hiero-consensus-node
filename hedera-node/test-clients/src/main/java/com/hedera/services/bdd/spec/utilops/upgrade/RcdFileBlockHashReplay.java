// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.upgrade;

import static com.hedera.node.app.blocks.BlockStreamManager.HASH_OF_ZERO;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.hashInternalNode;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.hashInternalNodeSingleChild;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.streams.RecordStreamFile;
import com.hedera.hapi.streams.SidecarFile;
import com.hedera.hapi.streams.TransactionSidecarRecord;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.node.app.hapi.utils.exports.recordstreaming.RecordStreamingUtils;
import com.hedera.node.app.records.impl.WrappedRecordFileBlockHashesCalculator;
import com.hedera.node.app.records.impl.WrappedRecordFileBlockHashesComputationInput;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Shared utility for replaying {@code .rcd} / {@code .rcd.gz} files from node0's record streams
 * directory, computing per-block {@link WrappedRecordFileBlockHashes} entries and chaining them
 * through the 5-level Merkle tree.
 */
public final class RcdFileBlockHashReplay {

    private static final Logger log = LogManager.getLogger(RcdFileBlockHashReplay.class);

    private static final int DEFAULT_MAX_SIDECAR_SIZE_BYTES = 256 * 1024 * 1024;
    private static final Bytes EMPTY_INT_NODE = hashInternalNode(HASH_OF_ZERO, HASH_OF_ZERO);

    private RcdFileBlockHashReplay() {}

    /**
     * Result of replaying record files: per-block entries, the final chained hash,
     * and the number of blocks processed.
     */
    public record ReplayResult(
            @NonNull Map<Long, WrappedRecordFileBlockHashes> entriesByBlock,
            @NonNull Bytes finalChainedHash,
            int blocksProcessed) {}

    /**
     * Reads {@code .rcd} files from node0's record streams, computes per-block wrapped hashes,
     * and chains them through the 5-level Merkle tree.
     *
     * @param spec               the HapiSpec providing the record streams directory
     * @param startBlockExclusive blocks less than this are skipped (use 0 for genesis)
     * @param endBlockInclusive  stop after processing this block
     * @param initialPrevHash    the previous wrapped block hash to start from
     *                           (use {@code HASH_OF_ZERO} for genesis)
     * @param initialHasher      the incremental streaming hasher to start from
     *                           (use a fresh empty hasher for genesis)
     * @return the replay result with per-block entries and final chained hash
     */
    public static ReplayResult replay(
            @NonNull final HapiSpec spec,
            final long startBlockExclusive,
            final long endBlockInclusive,
            @NonNull final Bytes initialPrevHash,
            @NonNull final IncrementalStreamingHasher initialHasher)
            throws IOException, ParseException {
        requireNonNull(spec);
        requireNonNull(initialPrevHash);
        requireNonNull(initialHasher);

        // 1. Discover record files from node0
        final var recordStreamsDir = spec.recordStreamsLoc(NodeSelector.byNodeId(0));
        log.info("[RcdFileBlockHashReplay] Record streams dir: {}", recordStreamsDir);

        final var recordFiles = RecordStreamingUtils.orderedRecordFilesFrom(recordStreamsDir.toString(), f -> true);
        log.info("[RcdFileBlockHashReplay] Found {} record files", recordFiles.size());

        // 2. Discover and index sidecar files by consensus time
        final var sidecarDir = recordStreamsDir.resolve("sidecar");
        final Map<Instant, List<String>> sidecarsByTime = new LinkedHashMap<>();
        if (Files.isDirectory(sidecarDir)) {
            final var sidecarFiles = RecordStreamingUtils.orderedSidecarFilesFrom(sidecarDir.toString());
            for (final var sf : sidecarFiles) {
                final var pair = RecordStreamingUtils.parseSidecarFileConsensusTimeAndSequenceNo(sf);
                sidecarsByTime
                        .computeIfAbsent(pair.getLeft(), k -> new ArrayList<>())
                        .add(sf);
            }
            log.info(
                    "[RcdFileBlockHashReplay] Found {} sidecar files across {} consensus times",
                    sidecarFiles.size(),
                    sidecarsByTime.size());
        }

        // 3. Process each record file
        final var entriesByBlock = new LinkedHashMap<Long, WrappedRecordFileBlockHashes>();
        var prevWrappedBlockHash = initialPrevHash;
        int processed = 0;

        for (final var recordFilePath : recordFiles) {
            final var rsf = readRecordFileAsPbj(recordFilePath);
            final long blockNumber = rsf.blockNumber();

            if (blockNumber <= startBlockExclusive) {
                continue;
            }
            if (blockNumber > endBlockInclusive) {
                log.info(
                        "[RcdFileBlockHashReplay] Stopping at block {} (end block is {})",
                        blockNumber,
                        endBlockInclusive);
                break;
            }

            // Read matching sidecars
            final var consensusTime = RecordStreamingUtils.parseRecordFileConsensusTime(recordFilePath);
            final var sidecars = readSidecarsForConsensusTime(sidecarsByTime, consensusTime);

            // Build computation input
            final var items = rsf.recordStreamItems();
            if (items.isEmpty()) {
                log.warn("[RcdFileBlockHashReplay] Skipping block {} — no record stream items", blockNumber);
                continue;
            }

            // Use the record file's consensus time (from the filename) as blockCreationTime
            final var blockCreationTime = new Timestamp(consensusTime.getEpochSecond(), consensusTime.getNano());

            final var hapiProtoVersion = rsf.hapiProtoVersion();

            final var startHash = requireNonNull(rsf.startObjectRunningHash()).hash();
            final var endHash = requireNonNull(rsf.endObjectRunningHash()).hash();

            final var input = new WrappedRecordFileBlockHashesComputationInput(
                    blockNumber,
                    blockCreationTime,
                    hapiProtoVersion,
                    startHash,
                    endHash,
                    items,
                    sidecars,
                    DEFAULT_MAX_SIDECAR_SIZE_BYTES);

            // Compute per-block hashes
            final var entry = WrappedRecordFileBlockHashesCalculator.compute(input);
            entriesByBlock.put(blockNumber, entry);

            // Compute block root hash via 5-level Merkle tree
            final var allPrevBlocksRootHash = Bytes.wrap(initialHasher.computeRootHash());
            final var blockRootHash = computeBlockRootHash(prevWrappedBlockHash, allPrevBlocksRootHash, entry);

            // Update chain
            initialHasher.addNodeByHash(blockRootHash.toByteArray());
            prevWrappedBlockHash = blockRootHash;
            processed++;
        }

        log.info(
                "[RcdFileBlockHashReplay] Processed {} blocks in range ({}, {}]. Final hash: {}",
                processed,
                startBlockExclusive,
                endBlockInclusive,
                prevWrappedBlockHash);

        return new ReplayResult(entriesByBlock, prevWrappedBlockHash, processed);
    }

    /**
     * Computes the wrapped record block root hash for a single block using the 5-level Merkle tree.
     */
    @SuppressWarnings("DuplicatedCode")
    private static Bytes computeBlockRootHash(
            @NonNull final Bytes prevWrappedBlockHash,
            @NonNull final Bytes allPrevBlocksRootHash,
            @NonNull final WrappedRecordFileBlockHashes entry) {
        final Bytes depth5Node1 = hashInternalNode(prevWrappedBlockHash, allPrevBlocksRootHash);
        final Bytes depth5Node2 = EMPTY_INT_NODE;
        final Bytes depth5Node3 = hashInternalNode(HASH_OF_ZERO, entry.outputItemsTreeRootHash());
        final Bytes depth5Node4 = EMPTY_INT_NODE;

        final Bytes depth4Node1 = hashInternalNode(depth5Node1, depth5Node2);
        final Bytes depth4Node2 = hashInternalNode(depth5Node3, depth5Node4);
        final Bytes depth3Node1 = hashInternalNode(depth4Node1, depth4Node2);

        final Bytes depth2Node1 = entry.consensusTimestampHash();
        final Bytes depth2Node2 = hashInternalNodeSingleChild(depth3Node1);

        return hashInternalNode(depth2Node1, depth2Node2);
    }

    /**
     * Reads a record stream file (.rcd or .rcd.gz) as a PBJ {@link RecordStreamFile},
     * skipping the 4-byte version header.
     */
    static RecordStreamFile readRecordFileAsPbj(@NonNull final String filePath) throws IOException, ParseException {
        final var path = Path.of(filePath);
        if (filePath.endsWith(".rcd.gz")) {
            try (final var in = new ReadableStreamingData(new GZIPInputStream(Files.newInputStream(path)))) {
                in.readInt(); // skip version
                return RecordStreamFile.PROTOBUF.parse(in);
            }
        } else {
            try (final var in = new ReadableStreamingData(Files.newInputStream(path))) {
                in.readInt(); // skip version
                return RecordStreamFile.PROTOBUF.parse(in);
            }
        }
    }

    /**
     * Reads and parses sidecar files for a given consensus time, returning the contained
     * {@link TransactionSidecarRecord} entries.
     */
    static List<TransactionSidecarRecord> readSidecarsForConsensusTime(
            @NonNull final Map<Instant, List<String>> sidecarsByTime, @NonNull final Instant consensusTime)
            throws IOException, ParseException {
        final var sidecarPaths = sidecarsByTime.get(consensusTime);
        if (sidecarPaths == null || sidecarPaths.isEmpty()) {
            return List.of();
        }
        final var result = new ArrayList<TransactionSidecarRecord>();
        for (final var sidecarPath : sidecarPaths) {
            final var sf = readSidecarFileAsPbj(sidecarPath);
            result.addAll(sf.sidecarRecords());
        }
        return result;
    }

    /**
     * Reads a sidecar file (.rcd or .rcd.gz) as a PBJ {@link SidecarFile}.
     * Sidecar files do not have a version header — they are pure protobuf.
     */
    static SidecarFile readSidecarFileAsPbj(@NonNull final String filePath) throws IOException, ParseException {
        final var path = Path.of(filePath);
        if (filePath.endsWith(".rcd.gz")) {
            try (final var in = new ReadableStreamingData(new GZIPInputStream(Files.newInputStream(path)))) {
                return SidecarFile.PROTOBUF.parse(in);
            }
        } else {
            try (final var in = new ReadableStreamingData(Files.newInputStream(path))) {
                return SidecarFile.PROTOBUF.parse(in);
            }
        }
    }
}

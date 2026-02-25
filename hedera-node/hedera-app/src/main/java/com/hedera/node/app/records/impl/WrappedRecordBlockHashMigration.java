// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.node.app.blocks.BlockStreamManager.HASH_OF_ZERO;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashesLog;
import com.hedera.node.app.blocks.impl.BlockImplUtils;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Performs the one-time migration of wrapped record file block hashes into block state.
 *
 * <p>This reads a jumpstart file (containing block number, previous block root hash, and streaming
 * hasher state) and a recent wrapped record hashes file, validates their consistency, computes the
 * Merkle block hashes for the range, and writes the results back to state.
 *
 * <p>TODO: Delete this in the release after receiving/injecting the jumpstart historical hashes file.
 */
public class WrappedRecordBlockHashMigration {

    private static final Logger log = LogManager.getLogger(WrappedRecordBlockHashMigration.class);

    /**
     * Holds the computed migration results needed for the state-write phase.
     *
     * @param blockHashes concatenated trailing block hashes
     * @param previousWrappedRecordBlockRootHash the final wrapped record block root hash
     * @param wrappedIntermediatePreviousBlockRootHashes intermediate hashing state
     * @param wrappedIntermediateBlockRootsLeafCount leaf count of the streaming hasher
     */
    public record Result(
            @NonNull Bytes blockHashes,
            @NonNull Bytes previousWrappedRecordBlockRootHash,
            @NonNull List<Bytes> wrappedIntermediatePreviousBlockRootHashes,
            long wrappedIntermediateBlockRootsLeafCount) {}

    private @Nullable Result result;

    /**
     * Returns the computed migration result, or null if the migration has not run or was skipped.
     */
    public @Nullable Result result() {
        return result;
    }

    static final Bytes EMPTY_INT_NODE = BlockImplUtils.hashInternalNode(HASH_OF_ZERO, HASH_OF_ZERO);
    private static final String RESUME_MESSAGE =
            "Resuming calculation of wrapped record file hashes until next attempt, but this node "
                    + "will likely experience an ISS";

    /**
     * Holds data loaded from the jumpstart binary file.
     *
     * @param blockNumber the jumpstart block number
     * @param prevHash the previous block root hash (48 bytes, SHA-384)
     * @param hasher the streaming hasher preloaded with historical state
     */
    private record JumpstartData(long blockNumber, Bytes prevHash, IncrementalStreamingHasher hasher) {}

    /**
     * Executes the wrapped record block hash migration if enabled.
     *
     * @param streamMode the current stream mode
     * @param recordsConfig the block record stream configuration
     */
    public void execute(@NonNull final StreamMode streamMode, @NonNull final BlockRecordStreamConfig recordsConfig) {
        requireNonNull(streamMode);
        requireNonNull(recordsConfig);

        final var computeHashesFromWrappedEnabled =
                streamMode != BLOCKS && recordsConfig.computeHashesFromWrappedRecordBlocks();
        if (!computeHashesFromWrappedEnabled) {
            return;
        }
        try {
            executeInternal(recordsConfig);
        } catch (Exception e) {
            log.error("Unable to compute continuing historical hash over recent wrapped records. " + RESUME_MESSAGE, e);
        }
    }

    private void executeInternal(@NonNull final BlockRecordStreamConfig recordsConfig) throws Exception {
        // Verify jumpstart file exists and can be loaded
        final var jumpstartFilePath = resolveJumpstartPath(recordsConfig);
        if (jumpstartFilePath == null) {
            return;
        }

        final var recentHashesPath = resolveRecentHashesPath(recordsConfig);
        if (recentHashesPath == null) {
            return;
        }

        final var jumpstartData = loadJumpstartData(jumpstartFilePath);
        if (jumpstartData == null) {
            return;
        }

        final var allRecentWrappedRecordHashes = loadRecentHashes(recentHashesPath);
        if (allRecentWrappedRecordHashes == null) {
            return;
        }

        if (!validateBlockNumberRange(jumpstartData.blockNumber(), allRecentWrappedRecordHashes)) {
            return;
        }

        // Compute hashes (state write deferred to SystemTransactions.doPostUpgradeSetup)
        computeHashes(jumpstartData, allRecentWrappedRecordHashes, recordsConfig.numOfBlockHashesInState());
    }

    private Path resolveJumpstartPath(@NonNull final BlockRecordStreamConfig recordsConfig) {
        if (isBlank(recordsConfig.jumpstartFile())) {
            log.warn("No jumpstart file location configured. {}", RESUME_MESSAGE);
            return null;
        }
        final var jumpstartFilePath = Paths.get(recordsConfig.jumpstartFile());
        if (!Files.exists(jumpstartFilePath)) {
            log.error("Jumpstart file not found at {}. {}", jumpstartFilePath, RESUME_MESSAGE);
            return null;
        }
        log.info("Found jumpstart file at {}", jumpstartFilePath);
        return jumpstartFilePath;
    }

    private Path resolveRecentHashesPath(@NonNull final BlockRecordStreamConfig recordsConfig) {
        if (isBlank(recordsConfig.wrappedRecordHashesDir())) {
            log.warn("No jumpstart historical hashes file location configured. {}", RESUME_MESSAGE);
            return null;
        }
        final var recentHashesPath = Paths.get(recordsConfig.wrappedRecordHashesDir())
                .resolve(WrappedRecordFileBlockHashesDiskWriter.DEFAULT_FILE_NAME);
        if (!Files.exists(recentHashesPath)) {
            log.error("Recent wrapped record hashes file not found at {}. {}", recentHashesPath, RESUME_MESSAGE);
            return null;
        }
        log.fatal("Found recent wrapped record hashes file at {}", recentHashesPath);
        return recentHashesPath;
    }

    /**
     * Reads the jumpstart binary file with format:
     *
     * <ul>
     *   <li>8 bytes: block number (long)</li>
     *   <li>48 bytes: previous block root hash (SHA-384)</li>
     *   <li>8 bytes: streaming hasher leaf count (long)</li>
     *   <li>4 bytes: streaming hasher hash count (int)</li>
     *   <li>48 bytes × hash count: streaming hasher pending subtree hashes</li>
     * </ul>
     *
     * The jumpstart file exactly encodes the fully-committed hash <b>of</b> the contained block
     * number. I.e. if the jumpstart file specifies a block number N, the first wrapped record block
     * taken from local disk and hashed is wrapped record block N+1 (using the jumpstart file's block
     * hash as the "previous block hash" for the first local wrapped record block).
     */
    private JumpstartData loadJumpstartData(@NonNull final Path jumpstartFilePath) throws Exception {
        try (final var din = new DataInputStream(Files.newInputStream(jumpstartFilePath))) {
            final long blockNumber = din.readLong();
            final byte[] prevHashBytes = new byte[HASH_SIZE];
            din.readFully(prevHashBytes);
            final Bytes prevHash = Bytes.wrap(prevHashBytes);

            final long leafCount = din.readLong();
            final int hashCount = din.readInt();
            final List<byte[]> hashes = new ArrayList<>(hashCount);
            for (int i = 0; i < hashCount; i++) {
                final byte[] hash = new byte[HASH_SIZE];
                din.readFully(hash);
                hashes.add(hash);
            }

            final var hasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), hashes, leafCount);
            if (hasher.leafCount() == 0) {
                log.error("Jumpstart file contains no entries (leaf count is 0). {}", RESUME_MESSAGE);
                return null;
            }
            log.info(
                    "Successfully loaded jumpstart file: blockNumber={}, leafCount={}, hashCount={}",
                    blockNumber,
                    leafCount,
                    hashCount);
            return new JumpstartData(blockNumber, prevHash, hasher);
        }
    }

    private WrappedRecordFileBlockHashesLog loadRecentHashes(@NonNull final Path recentHashesPath) throws Exception {
        final var loadedBytes = Files.readAllBytes(recentHashesPath);
        final var allRecentWrappedRecordHashes =
                WrappedRecordFileBlockHashesLog.PROTOBUF.parse(Bytes.wrap(loadedBytes));
        if (allRecentWrappedRecordHashes.entries().isEmpty()) {
            log.error("Recent wrapped record hashes file contains no entries. {}", RESUME_MESSAGE);
            return null;
        }
        log.info(
                "Successfully loaded recent wrapped record hashes file. Block num range: [{}, {}]",
                allRecentWrappedRecordHashes.entries().getFirst().blockNumber(),
                allRecentWrappedRecordHashes.entries().getLast().blockNumber());
        return allRecentWrappedRecordHashes;
    }

    private boolean validateBlockNumberRange(
            final long jumpstartBlockNum, @NonNull final WrappedRecordFileBlockHashesLog allRecentWrappedRecordHashes) {
        final var firstRecentRecord = allRecentWrappedRecordHashes.entries().getFirst();
        log.info(
                "First recent record num/hash: {}/{}",
                firstRecentRecord.blockNumber(),
                firstRecentRecord.outputItemsTreeRootHash());
        if (jumpstartBlockNum < firstRecentRecord.blockNumber()) {
            log.error(
                    "Configured jumpstart block num {} is less than the first available block number {} in the recent wrapped record hashes file. {}",
                    jumpstartBlockNum,
                    allRecentWrappedRecordHashes.entries().getFirst().blockNumber(),
                    RESUME_MESSAGE);
            return false;
        }

        if (jumpstartBlockNum > allRecentWrappedRecordHashes.entries().getLast().blockNumber()) {
            log.error(
                    "Jumpstart block number {} is greater than the highest block number {} in the recent wrapped record hashes file. No overlap between historical and recent blocks. {}",
                    jumpstartBlockNum,
                    allRecentWrappedRecordHashes.entries().getLast().blockNumber(),
                    RESUME_MESSAGE);
            return false;
        }

        final var neededRecentWrappedRecords = allRecentWrappedRecordHashes.entries().stream()
                .filter(rwr -> rwr.blockNumber() > jumpstartBlockNum)
                .toList();

        // Verify needed records have consecutive block numbers while iterating
        long expectedBlockNum = jumpstartBlockNum + 1;
        for (final var record : neededRecentWrappedRecords) {
            if (record.blockNumber() != expectedBlockNum) {
                log.error(
                        "Non-consecutive block numbers in needed wrapped records: expected block {} but found {}. {}",
                        expectedBlockNum,
                        record.blockNumber(),
                        RESUME_MESSAGE);
                return false;
            }
            expectedBlockNum++;
        }

        if (!neededRecentWrappedRecords.isEmpty()) {
            final var firstNeededRecentRecord = neededRecentWrappedRecords.getFirst();
            log.info(
                    "First needed record (jumpstart to recent record handoff) num/hash: {}/{}",
                    firstNeededRecentRecord.blockNumber(),
                    firstNeededRecentRecord.outputItemsTreeRootHash());
            final var lastNeededRecentRecord = neededRecentWrappedRecords.getLast();
            log.info(
                    "Last recent record num/hash: {}/{}",
                    lastNeededRecentRecord.blockNumber(),
                    lastNeededRecentRecord.outputItemsTreeRootHash());
        }
        return true;
    }

    private void computeHashes(
            @NonNull final JumpstartData jumpstartData,
            @NonNull final WrappedRecordFileBlockHashesLog allRecentWrappedRecordHashes,
            final int numTrailingBlocks) {
        // The number of the last wrapped record block; this is the final block processed prior to now
        final var jumpstartBlockNum = jumpstartData.blockNumber();
        // The hash of the (completed/hashed) jumpstart block number
        final var allPrevBlocksHasher = jumpstartData.hasher();
        final var neededRecentWrappedRecords = allRecentWrappedRecordHashes.entries().stream()
                .filter(rwr -> rwr.blockNumber() > jumpstartBlockNum)
                .toList();
        log.info(
                "Calculated range of needed recent wrapped record blocks: [{}, {}]",
                neededRecentWrappedRecords.getFirst().blockNumber(),
                neededRecentWrappedRecords.getLast().blockNumber());
        final var numNeededRecentWrappedRecords = neededRecentWrappedRecords.size();

        final List<Bytes> currentTrailingBlockHashes = new ArrayList<>(numTrailingBlocks);
        final int blockTailStartIndex = Math.max(0, numNeededRecentWrappedRecords - numTrailingBlocks);
        Bytes prevWrappedBlockHash = jumpstartData.prevHash();
        int wrappedRecordsProcessed = 0;
        log.info("Adding recent wrapped record file block hashes to genesis historical hash");
        for (final var recentWrappedRecordHashes : neededRecentWrappedRecords) {
            // Branch 1 is represented by `prevWrappedBlockHash`

            // Branch 2
            final Bytes allPrevBlocksHash = Bytes.wrap(allPrevBlocksHasher.computeRootHash());
            final Bytes depth5Node1 = BlockImplUtils.hashInternalNode(prevWrappedBlockHash, allPrevBlocksHash);

            // Branches 3/4 (empty)
            final Bytes depth5Node2 = EMPTY_INT_NODE;

            // Branches 5/6
            final Bytes outputTreeHash = recentWrappedRecordHashes.outputItemsTreeRootHash();
            final Bytes depth5Node3 = BlockImplUtils.hashInternalNode(HASH_OF_ZERO, outputTreeHash);

            // Branches 7/8 (empty)
            final Bytes depth5Node4 = EMPTY_INT_NODE;

            // Intermediate depths 4, 3, and 2
            final Bytes depth4Node1 = BlockImplUtils.hashInternalNode(depth5Node1, depth5Node2);
            final Bytes depth4Node2 = BlockImplUtils.hashInternalNode(depth5Node3, depth5Node4);

            final Bytes depth3Node1 = BlockImplUtils.hashInternalNode(depth4Node1, depth4Node2);

            final Bytes depth2Node1 = recentWrappedRecordHashes.consensusTimestampHash();
            final Bytes depth2Node2 = BlockImplUtils.hashInternalNodeSingleChild(depth3Node1);

            // Final block root
            final Bytes finalBlockHash = BlockImplUtils.hashInternalNode(depth2Node1, depth2Node2);
            if (wrappedRecordsProcessed != 0 && wrappedRecordsProcessed % 10000 == 0) {
                log.info("Processed {} wrapped record file block hashes", wrappedRecordsProcessed);
            }

            // Update trailing block hashes
            if (wrappedRecordsProcessed >= blockTailStartIndex) {
                currentTrailingBlockHashes.add(finalBlockHash);
            }

            // Prepare for next hashing iteration
            allPrevBlocksHasher.addNodeByHash(finalBlockHash.toByteArray());
            prevWrappedBlockHash = finalBlockHash;
            wrappedRecordsProcessed++;
        }
        log.info(
                "Completed processing all {} recent wrapped record hashes. Final wrapped record block hash (as of expected freeze block {}): {}",
                wrappedRecordsProcessed,
                jumpstartBlockNum + numNeededRecentWrappedRecords,
                prevWrappedBlockHash);

        result = new Result(
                concatHashes(currentTrailingBlockHashes),
                prevWrappedBlockHash,
                allPrevBlocksHasher.intermediateHashingState(),
                allPrevBlocksHasher.leafCount());
        log.info("Computed wrapped record block hash migration result (state write deferred)");
    }

    static Bytes concatHashes(@NonNull final List<Bytes> hashes) {
        if (hashes.isEmpty()) {
            return Bytes.EMPTY;
        }
        final byte[] out = new byte[hashes.size() * HASH_SIZE];
        int offset = 0;
        for (final var hash : hashes) {
            hash.getBytes(0, out, offset, HASH_SIZE);
            offset += HASH_SIZE;
        }
        return Bytes.wrap(out);
    }

    private static boolean isBlank(final String s) {
        return s == null || s.isBlank();
    }
}

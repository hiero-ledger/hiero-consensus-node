// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashesLog;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.node.config.data.BlockStreamJumpstartConfig;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
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
 * <p>This reads jumpstart config properties (block number, previous block root hash, and streaming
 * hasher state) and a recent wrapped record hashes file, validates their consistency,
 * computes the Merkle block hashes for the range, and writes the results back to state.
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

    /** Marker file written after a successful migration to prevent re-application on restart. */
    public static final String JUMPSTART_USED_MARKER = "jumpstart-used.properties";

    private @Nullable Result result;
    private @Nullable Path markerFilePath;
    private @Nullable String markerFileContent;

    /**
     * Returns the computed migration result, or null if the migration has not run or was skipped.
     */
    public @Nullable Result result() {
        return result;
    }

    /**
     * Returns the path where the marker file should be written after the migration result is
     * successfully applied to state, or null if the migration did not run.
     */
    public @Nullable Path markerFilePath() {
        return markerFilePath;
    }

    /**
     * Returns the content to write to the marker file, or null if the migration did not run.
     */
    public @Nullable String markerFileContent() {
        return markerFileContent;
    }

    private static final String RESUME_MESSAGE =
            "Resuming calculation of wrapped record file hashes until next attempt";

    /**
     * Executes the wrapped record block hash migration if enabled.
     *
     * @param streamMode the current stream mode
     * @param recordsConfig the block record stream configuration
     * @param jumpstartConfig the jumpstart configuration properties
     */
    public void execute(
            @NonNull final StreamMode streamMode,
            @NonNull final BlockRecordStreamConfig recordsConfig,
            @NonNull final BlockStreamJumpstartConfig jumpstartConfig) {
        requireNonNull(streamMode);
        requireNonNull(recordsConfig);
        requireNonNull(jumpstartConfig);

        final var computeHashesFromWrappedEnabled =
                streamMode != BLOCKS && recordsConfig.computeHashesFromWrappedRecordBlocks();
        if (!computeHashesFromWrappedEnabled) {
            return;
        }
        try {
            executeInternal(recordsConfig, jumpstartConfig);
        } catch (Exception e) {
            log.error("Unable to compute continuing historical hash over recent wrapped records. " + RESUME_MESSAGE, e);
        }
    }

    private void executeInternal(
            @NonNull final BlockRecordStreamConfig recordsConfig,
            @NonNull final BlockStreamJumpstartConfig jumpstartConfig)
            throws Exception {
        // Check if jumpstart config is populated (blockNum defaults to -1 when unconfigured)
        if (jumpstartConfig.blockNum() < 0) {
            log.warn("No jumpstart config populated (blockNum={}). {}", jumpstartConfig.blockNum(), RESUME_MESSAGE);
            return;
        }

        final var recentHashesPath = resolveRecentHashesPath(recordsConfig);
        if (recentHashesPath == null) {
            return;
        }

        // Check if a previous migration already ran (marker file prevents re-application)
        final var markerPath = recentHashesPath.resolveSibling(JUMPSTART_USED_MARKER);
        if (Files.exists(markerPath)) {
            log.info("Jumpstart migration already applied (marker file exists at {}), skipping", markerPath);
            return;
        }

        final var hasher = createHasherFromConfig(jumpstartConfig);
        if (hasher == null) {
            return;
        }

        final var allRecentWrappedRecordHashes = loadRecentHashes(recentHashesPath);
        if (allRecentWrappedRecordHashes == null) {
            return;
        }

        if (!validateBlockNumberRange(jumpstartConfig.blockNum(), allRecentWrappedRecordHashes)) {
            return;
        }

        // Compute hashes (state write deferred to SystemTransactions.doPostUpgradeSetup)
        computeHashes(jumpstartConfig, hasher, allRecentWrappedRecordHashes, recordsConfig.numOfBlockHashesInState());
        this.markerFilePath = markerPath;
        this.markerFileContent = MARKER_FILE_TEMPLATE.formatted(
                jumpstartConfig.blockNum(),
                jumpstartConfig.previousWrappedRecordBlockHash().toHex(),
                jumpstartConfig.streamingHasherLeafCount(),
                jumpstartConfig.streamingHasherHashCount());
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
        log.info("Found recent wrapped record hashes file at {}", recentHashesPath);
        return recentHashesPath;
    }

    /**
     * Creates a streaming hasher from jumpstart config properties.
     *
     * <p>The jumpstart config exactly encodes the fully-committed hash <b>of</b> the contained block
     * number. I.e. if the jumpstart config specifies a block number N, the first wrapped record block
     * taken from local disk and hashed is wrapped record block N+1 (using the jumpstart config's block
     * hash as the "previous block hash" for the first local wrapped record block).
     */
    private IncrementalStreamingHasher createHasherFromConfig(
            @NonNull final BlockStreamJumpstartConfig jumpstartConfig) {
        final var subtreeHashes = jumpstartConfig.streamingHasherSubtreeHashes();
        if (jumpstartConfig.streamingHasherHashCount() != subtreeHashes.size()) {
            log.error(
                    "Jumpstart config streamingHasherHashCount ({}) does not match subtree hashes list size ({}). {}",
                    jumpstartConfig.streamingHasherHashCount(),
                    subtreeHashes.size(),
                    RESUME_MESSAGE);
            return null;
        }
        final List<byte[]> hashes = new ArrayList<>(subtreeHashes.size());
        for (final var hash : subtreeHashes) {
            hashes.add(hash.toByteArray());
        }
        final var hasher = new IncrementalStreamingHasher(
                sha384DigestOrThrow(), hashes, jumpstartConfig.streamingHasherLeafCount());
        if (hasher.leafCount() == 0) {
            log.error("Jumpstart config contains no entries (leaf count is 0). {}", RESUME_MESSAGE);
            return null;
        }
        log.info(
                "Successfully loaded jumpstart config: blockNumber={}, leafCount={}, hashCount={}",
                jumpstartConfig.blockNum(),
                hasher.leafCount(),
                hasher.intermediateHashingState().size());
        return hasher;
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
                log.info(
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
            @NonNull final BlockStreamJumpstartConfig jumpstartConfig,
            @NonNull final IncrementalStreamingHasher allPrevBlocksHasher,
            @NonNull final WrappedRecordFileBlockHashesLog allRecentWrappedRecordHashes,
            final int numTrailingBlocks) {
        final var jumpstartBlockNum = jumpstartConfig.blockNum();
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
        Bytes prevWrappedBlockHash = jumpstartConfig.previousWrappedRecordBlockHash();
        int wrappedRecordsProcessed = 0;
        log.info("Adding recent wrapped record file block hashes to genesis historical hash");
        for (final var recentWrappedRecordHashes : neededRecentWrappedRecords) {
            final Bytes allPrevBlocksHash = Bytes.wrap(allPrevBlocksHasher.computeRootHash());
            final Bytes finalBlockHash = BlockRecordManagerImpl.computeWrappedRecordBlockRootHash(
                    prevWrappedBlockHash, allPrevBlocksHash, recentWrappedRecordHashes);
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

    private static final String MARKER_FILE_TEMPLATE = """
            # Jumpstart config properties used for this migration
            blockStream.jumpstart.blockNum=%d
            blockStream.jumpstart.previousWrappedRecordBlockHash=%s
            blockStream.jumpstart.streamingHasherLeafCount=%d
            blockStream.jumpstart.streamingHasherHashCount=%d
            blockStream.jumpstart.streamingHasherSubtreeHashes=<see config for values>
            """;
}

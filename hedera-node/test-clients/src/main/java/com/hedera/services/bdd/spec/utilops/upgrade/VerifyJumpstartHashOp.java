// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.upgrade;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.node.config.data.BlockStreamJumpStartConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * Three-way verification of the jumpstart hash computation:
 * <ol>
 *   <li><b>Chain 1 (file entries)</b>: chains wrapped hashes file entries via
 *       {@link RcdFileBlockHashReplay#computeBlockRootHash}</li>
 *   <li><b>Chain 2 (.rcd replay)</b>: replays {@code .rcd} files via
 *       {@link RcdFileBlockHashReplay}</li>
 *   <li><b>Three-way assertions</b>: per-block entry comparison, plus both chain hashes
 *       must match the node's logged hash</li>
 * </ol>
 */
public class VerifyJumpstartHashOp extends UtilOp {
    private static final Logger log = LogManager.getLogger(VerifyJumpstartHashOp.class);

    private final BlockStreamJumpStartConfig jumpstartConfig;
    private final List<WrappedRecordFileBlockHashes> wrappedHashes;
    private final String nodeComputedHash;
    private final String freezeBlockNum;

    public VerifyJumpstartHashOp(
            @NonNull final BlockStreamJumpStartConfig jumpstartConfig,
            @NonNull final List<WrappedRecordFileBlockHashes> wrappedHashes,
            @NonNull final String nodeComputedHash,
            @NonNull final String freezeBlockNum) {
        this.jumpstartConfig = requireNonNull(jumpstartConfig);
        this.wrappedHashes = requireNonNull(wrappedHashes);
        this.nodeComputedHash = requireNonNull(nodeComputedHash);
        this.freezeBlockNum = requireNonNull(freezeBlockNum);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final long freezeBlock = Long.parseLong(freezeBlockNum);

        // Create two independent hasher instances
        final var hasher1 = createHasherFromConfig(jumpstartConfig);
        final var hasher2 = createHasherFromConfig(jumpstartConfig);
        final long jumpstartBlockNum = jumpstartConfig.blockNum();
        final Bytes prevHash = jumpstartConfig.previousWrappedRecordBlockHash();

        log.info(
                "[VerifyJumpstartHash] Jumpstart block={}, prevHash={}, freeze block={}",
                jumpstartBlockNum,
                prevHash,
                freezeBlock);

        // ===== Chain 1: File entries chained via computeWrappedRecordBlockRootHash =====
        final var neededEntries = wrappedHashes.stream()
                .filter(e -> e.blockNumber() > jumpstartBlockNum && e.blockNumber() <= freezeBlock)
                .sorted(Comparator.comparingLong(WrappedRecordFileBlockHashes::blockNumber))
                .toList();

        log.info(
                "[VerifyJumpstartHash] Chain 1 (file): will replay {} entries, blocks ({}, {}]",
                neededEntries.size(),
                jumpstartBlockNum,
                neededEntries.isEmpty() ? "n/a" : neededEntries.getLast().blockNumber());

        Bytes fileChainHash = prevHash;
        int index = 0;
        for (final var entry : neededEntries) {
            final var allPrevBlocksRootHash = Bytes.wrap(hasher1.computeRootHash());
            final var blockRootHash =
                    RcdFileBlockHashReplay.computeBlockRootHash(fileChainHash, allPrevBlocksRootHash, entry);

            // Log first 3 and last 3 blocks for debugging
            final boolean isEdgeBlock = index < 3 || index >= neededEntries.size() - 3;
            if (isEdgeBlock) {
                log.info(
                        "[VerifyJumpstartHash] Chain 1 block {} (index {}): prevHash={}, ctHash={}, outputRoot={} → finalHash={}",
                        entry.blockNumber(),
                        index,
                        fileChainHash,
                        entry.consensusTimestampHash(),
                        entry.outputItemsTreeRootHash(),
                        blockRootHash);
            }

            hasher1.addNodeByHash(blockRootHash.toByteArray());
            fileChainHash = blockRootHash;
            index++;
        }

        log.info("[VerifyJumpstartHash] Chain 1 (file) final hash: {}", fileChainHash);

        // ===== Chain 2: .rcd replay =====
        final var rcdResult = RcdFileBlockHashReplay.replay(spec, jumpstartBlockNum, freezeBlock, prevHash, hasher2);

        log.info(
                "[VerifyJumpstartHash] Chain 2 (.rcd) processed {} blocks, final hash: {}",
                rcdResult.blocksProcessed(),
                rcdResult.finalChainedHash());

        // ===== Three-way assertions =====

        // 1. Per-block: .rcd entry vs file entry (ctHash + outputRoot)
        final var fileEntriesByBlock = new java.util.HashMap<Long, WrappedRecordFileBlockHashes>();
        for (final var entry : neededEntries) {
            fileEntriesByBlock.put(entry.blockNumber(), entry);
        }

        int mismatches = 0;
        for (final var mapEntry : rcdResult.entriesByBlock().entrySet()) {
            final long blockNumber = mapEntry.getKey();
            final var rcdEntry = mapEntry.getValue();
            final var fileEntry = fileEntriesByBlock.get(blockNumber);
            if (fileEntry == null) {
                log.warn(
                        "[VerifyJumpstartHash] No file entry for block {} (rcd ctHash={}, outputRoot={})",
                        blockNumber,
                        rcdEntry.consensusTimestampHash(),
                        rcdEntry.outputItemsTreeRootHash());
                mismatches++;
                continue;
            }
            if (!rcdEntry.consensusTimestampHash().equals(fileEntry.consensusTimestampHash())
                    || !rcdEntry.outputItemsTreeRootHash().equals(fileEntry.outputItemsTreeRootHash())) {
                log.error(
                        "[VerifyJumpstartHash] Block {} mismatch:"
                                + " rcd ctHash={}, file ctHash={};"
                                + " rcd outputRoot={}, file outputRoot={}",
                        blockNumber,
                        rcdEntry.consensusTimestampHash(),
                        fileEntry.consensusTimestampHash(),
                        rcdEntry.outputItemsTreeRootHash(),
                        fileEntry.outputItemsTreeRootHash());
                mismatches++;
            }
        }
        Assertions.assertEquals(
                0,
                mismatches,
                "[VerifyJumpstartHash] Found " + mismatches
                        + " block-level mismatches between .rcd replay and file entries");

        // 2. File chain hash vs node logged hash
        Assertions.assertEquals(
                nodeComputedHash,
                fileChainHash.toString(),
                ("[VerifyJumpstartHash] File chain mismatch after processing %d entries (blocks %s–%s)."
                                + " jumpstart block=%d, freeze block=%d."
                                + " Check node logs for 'First needed record' and 'Last recent record'.")
                        .formatted(
                                neededEntries.size(),
                                neededEntries.isEmpty()
                                        ? "n/a"
                                        : neededEntries.getFirst().blockNumber(),
                                neededEntries.isEmpty()
                                        ? "n/a"
                                        : neededEntries.getLast().blockNumber(),
                                jumpstartBlockNum,
                                freezeBlock));

        // 3. .rcd chain hash vs node logged hash
        Assertions.assertEquals(
                nodeComputedHash,
                rcdResult.finalChainedHash().toString(),
                ("[VerifyJumpstartHash] .rcd chain mismatch after processing %d blocks."
                                + " jumpstart block=%d, freeze block=%d."
                                + " Check node logs for 'Persisted' wrapped hash entries.")
                        .formatted(rcdResult.blocksProcessed(), jumpstartBlockNum, freezeBlock));

        log.info(
                "[VerifyJumpstartHash] Three-way verification passed: file chain={}, rcd chain={}, node logged={}",
                fileChainHash,
                rcdResult.finalChainedHash(),
                nodeComputedHash);

        return false;
    }

    private static IncrementalStreamingHasher createHasherFromConfig(@NonNull final BlockStreamJumpStartConfig config) {
        final List<byte[]> hashes = new ArrayList<>(config.streamingHasherHashCount());
        for (final var hash : config.streamingHasherSubtreeHashes()) {
            hashes.add(hash.toByteArray());
        }
        return new IncrementalStreamingHasher(sha384DigestOrThrow(), hashes, config.streamingHasherLeafCount());
    }
}

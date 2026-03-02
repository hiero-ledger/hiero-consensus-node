// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.upgrade;

import static com.hedera.node.app.blocks.BlockStreamManager.HASH_OF_ZERO;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.hashInternalNode;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.hashInternalNodeSingleChild;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * Three-way verification of the jumpstart hash computation:
 * <ol>
 *   <li><b>Chain 1 (file entries)</b>: chains wrapped hashes file entries via the 5-level
 *       Merkle tree (same computation as {@code WrappedRecordBlockHashMigration})</li>
 *   <li><b>Chain 2 (.rcd replay)</b>: replays {@code .rcd} files via
 *       {@link RcdFileBlockHashReplay}</li>
 *   <li><b>Three-way assertions</b>: per-block entry comparison, plus both chain hashes
 *       must match the node's logged hash</li>
 * </ol>
 */
public class VerifyJumpstartHashOp extends UtilOp {

    private static final Logger log = LogManager.getLogger(VerifyJumpstartHashOp.class);

    private static final Bytes EMPTY_INT_NODE = hashInternalNode(HASH_OF_ZERO, HASH_OF_ZERO);

    private final byte[] jumpstartContents;
    private final List<WrappedRecordFileBlockHashes> wrappedHashes;
    private final String nodeComputedHash;
    private final String freezeBlockNum;

    public VerifyJumpstartHashOp(
            @NonNull final byte[] jumpstartContents,
            @NonNull final List<WrappedRecordFileBlockHashes> wrappedHashes,
            @NonNull final String nodeComputedHash,
            @NonNull final String freezeBlockNum) {
        this.jumpstartContents = requireNonNull(jumpstartContents);
        this.wrappedHashes = requireNonNull(wrappedHashes);
        this.nodeComputedHash = requireNonNull(nodeComputedHash);
        this.freezeBlockNum = requireNonNull(freezeBlockNum);
    }

    /** Parsed jumpstart file state: the block number, previous hash, and streaming hasher. */
    private record JumpstartState(long jumpstartBlockNum, Bytes prevHash, IncrementalStreamingHasher hasher) {}

    /** Parses the jumpstart file bytes into a {@link JumpstartState}. */
    private static JumpstartState parseJumpstartFile(@NonNull final byte[] contents) throws java.io.IOException {
        try (final var din = new DataInputStream(new ByteArrayInputStream(contents))) {
            final long jumpstartBlockNum = din.readLong();
            final byte[] prevHashBytes = new byte[HASH_SIZE];
            din.readFully(prevHashBytes);
            final var prevHash = Bytes.wrap(prevHashBytes);

            final long leafCount = din.readLong();
            final int hashCount = din.readInt();
            final List<byte[]> hashes = new ArrayList<>(hashCount);
            for (int i = 0; i < hashCount; i++) {
                final byte[] hash = new byte[HASH_SIZE];
                din.readFully(hash);
                hashes.add(hash);
            }
            final var hasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), hashes, leafCount);
            return new JumpstartState(jumpstartBlockNum, prevHash, hasher);
        }
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final long freezeBlock = Long.parseLong(freezeBlockNum);

        // Parse jumpstart file twice — IncrementalStreamingHasher is not cloneable,
        // so we need independent hasher instances for the two chains
        final var state1 = parseJumpstartFile(jumpstartContents);
        final var state2 = parseJumpstartFile(jumpstartContents);
        final long jumpstartBlockNum = state1.jumpstartBlockNum();

        log.info(
                "[VerifyJumpstartHash] Jumpstart block={}, prevHash={}, freeze block={}",
                jumpstartBlockNum,
                state1.prevHash(),
                freezeBlock);

        // ===== Chain 1: File entries chained via 5-level Merkle tree =====
        final var neededEntries = wrappedHashes.stream()
                .filter(e -> e.blockNumber() > jumpstartBlockNum && e.blockNumber() <= freezeBlock)
                .sorted((a, b) -> Long.compare(a.blockNumber(), b.blockNumber()))
                .toList();

        log.info(
                "[VerifyJumpstartHash] Chain 1 (file): will replay {} entries, blocks ({}, {}]",
                neededEntries.size(),
                jumpstartBlockNum,
                neededEntries.isEmpty() ? "n/a" : neededEntries.getLast().blockNumber());

        Bytes fileChainHash = state1.prevHash();
        int index = 0;
        for (final var entry : neededEntries) {
            final Bytes allPrevBlocksHash = Bytes.wrap(state1.hasher().computeRootHash());
            final Bytes depth5Node1 = hashInternalNode(fileChainHash, allPrevBlocksHash);
            final Bytes depth5Node2 = EMPTY_INT_NODE;
            final Bytes depth5Node3 = hashInternalNode(HASH_OF_ZERO, entry.outputItemsTreeRootHash());
            final Bytes depth5Node4 = EMPTY_INT_NODE;

            final Bytes depth4Node1 = hashInternalNode(depth5Node1, depth5Node2);
            final Bytes depth4Node2 = hashInternalNode(depth5Node3, depth5Node4);
            final Bytes depth3Node1 = hashInternalNode(depth4Node1, depth4Node2);

            final Bytes depth2Node1 = entry.consensusTimestampHash();
            final Bytes depth2Node2 = hashInternalNodeSingleChild(depth3Node1);

            final Bytes finalBlockHash = hashInternalNode(depth2Node1, depth2Node2);

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
                        finalBlockHash);
            }

            state1.hasher().addNodeByHash(finalBlockHash.toByteArray());
            fileChainHash = finalBlockHash;
            index++;
        }

        log.info("[VerifyJumpstartHash] Chain 1 (file) final hash: {}", fileChainHash);

        // ===== Chain 2: .rcd replay =====
        final var rcdResult =
                RcdFileBlockHashReplay.replay(spec, jumpstartBlockNum, freezeBlock, state2.prevHash(), state2.hasher());

        log.info(
                "[VerifyJumpstartHash] Chain 2 (.rcd) processed {} blocks, final hash: {}",
                rcdResult.blocksProcessed(),
                rcdResult.finalChainedHash());

        // ===== Three-way assertions =====

        // 1. Per-block: .rcd entry vs file entry (ctHash + outputRoot)
        final var fileEntriesByBlock = new HashMap<Long, WrappedRecordFileBlockHashes>();
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
}

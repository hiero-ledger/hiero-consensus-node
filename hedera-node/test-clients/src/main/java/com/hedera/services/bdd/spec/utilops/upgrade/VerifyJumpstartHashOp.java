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
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * Replays the wrapped record block hash computation from {@code WrappedRecordBlockHashMigration}
 * using the jumpstart file and the gathered wrapped record hashes, then asserts the result matches
 * the final hash the node logged during migration.
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

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        // Parse the jumpstart file (same format as WrappedRecordBlockHashMigration.loadJumpstartData)
        final long jumpstartBlockNum;
        final Bytes prevHash;
        final IncrementalStreamingHasher allPrevBlocksHasher;
        try (final var din = new DataInputStream(new ByteArrayInputStream(jumpstartContents))) {
            jumpstartBlockNum = din.readLong();
            final byte[] prevHashBytes = new byte[HASH_SIZE];
            din.readFully(prevHashBytes);
            prevHash = Bytes.wrap(prevHashBytes);

            final long leafCount = din.readLong();
            final int hashCount = din.readInt();
            final List<byte[]> hashes = new ArrayList<>(hashCount);
            for (int i = 0; i < hashCount; i++) {
                final byte[] hash = new byte[HASH_SIZE];
                din.readFully(hash);
                hashes.add(hash);
            }
            allPrevBlocksHasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), hashes, leafCount);
            log.info(
                    "[VerifyJumpstartHash] Jumpstart file parsed: blockNum={}, prevHash={}, leafCount={}, hashCount={}",
                    jumpstartBlockNum,
                    prevHash,
                    leafCount,
                    hashCount);
        }

        // The jumpstart file represents the completed state *at* jumpstartBlockNum — the migration
        // loop processes blocks *after* it (blockNumber > jumpstartBlockNum), mirroring the
        // production filter in WrappedRecordBlockHashMigration.computeAndWriteHashes.
        // freezeBlockNum is the last block the node processed (inclusive); new blocks after the
        // upgrade start from freezeBlockNum + 1.
        final long freezeBlockNum = Long.parseLong(this.freezeBlockNum);
        final var neededEntries = wrappedHashes.stream()
                .filter(e -> e.blockNumber() > jumpstartBlockNum && e.blockNumber() <= freezeBlockNum)
                .sorted((a, b) -> Long.compare(a.blockNumber(), b.blockNumber()))
                .toList();

        log.info(
                "[VerifyJumpstartHash] Will replay {} entries, blocks ({}, {}] (jumpstart block {} exclusive, freeze block {} inclusive)",
                neededEntries.size(),
                jumpstartBlockNum,
                neededEntries.isEmpty() ? "n/a" : neededEntries.getLast().blockNumber(),
                jumpstartBlockNum,
                freezeBlockNum);
        log.info(
                "[VerifyJumpstartHash] Checking starting hash {} (block number {}) against node's expected final hash {} (block number {})",
                prevHash,
                jumpstartBlockNum,
                nodeComputedHash,
                freezeBlockNum);

        Bytes prevWrappedBlockHash = prevHash;
        int index = 0;
        for (final var entry : neededEntries) {
            final Bytes allPrevBlocksHash = Bytes.wrap(allPrevBlocksHasher.computeRootHash());
            final Bytes depth5Node1 = hashInternalNode(prevWrappedBlockHash, allPrevBlocksHash);
            final Bytes depth5Node2 = EMPTY_INT_NODE;
            final Bytes depth5Node3 = hashInternalNode(HASH_OF_ZERO, entry.outputItemsTreeRootHash());
            final Bytes depth5Node4 = EMPTY_INT_NODE;

            final Bytes depth4Node1 = hashInternalNode(depth5Node1, depth5Node2);
            final Bytes depth4Node2 = hashInternalNode(depth5Node3, depth5Node4);
            final Bytes depth3Node1 = hashInternalNode(depth4Node1, depth4Node2);

            final Bytes depth2Node1 = entry.consensusTimestampHash();
            final Bytes depth2Node2 = hashInternalNodeSingleChild(depth3Node1);

            final Bytes finalBlockHash = hashInternalNode(depth2Node1, depth2Node2);

            // Log first 3 and last 3 blocks so the per-block inputs can be compared with node logs
            final boolean isEdgeBlock = index < 3 || index >= neededEntries.size() - 3;
            if (isEdgeBlock) {
                log.info(
                        "[VerifyJumpstartHash] block {} (index {}): prevHash={}, ctHash={}, outputRoot={} → finalHash={}",
                        entry.blockNumber(),
                        index,
                        prevWrappedBlockHash,
                        entry.consensusTimestampHash(),
                        entry.outputItemsTreeRootHash(),
                        finalBlockHash);
            }

            allPrevBlocksHasher.addNodeByHash(finalBlockHash.toByteArray());
            prevWrappedBlockHash = finalBlockHash;
            index++;
        }

        log.info(
                "[VerifyJumpstartHash] Computed final hash {} (block number {}); node logged {}",
                prevWrappedBlockHash,
                freezeBlockNum,
                nodeComputedHash);

        Assertions.assertEquals(
                nodeComputedHash,
                prevWrappedBlockHash.toString(),
                ("[VerifyJumpstartHash] Mismatch after processing %d entries (blocks %s–%s)."
                                + " freeze block (exclusive upper bound)=%d, jumpstart block=%d."
                                + " Check node logs for 'First needed record' and 'Last recent record'"
                                + " to compare entry ranges.")
                        .formatted(
                                neededEntries.size(),
                                neededEntries.isEmpty()
                                        ? "n/a"
                                        : neededEntries.getFirst().blockNumber(),
                                neededEntries.isEmpty()
                                        ? "n/a"
                                        : neededEntries.getLast().blockNumber(),
                                freezeBlockNum,
                                jumpstartBlockNum));
        return false;
    }
}

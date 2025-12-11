// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.utils;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockFooter;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.blocks.StreamingTreeHasher;
import com.hedera.node.app.blocks.impl.ConcurrentStreamingTreeHasher;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import org.hiero.base.concurrent.AbstractTask;

/**
 * Benchmark version of BlockStreamManager.
 *
 * COPIED FROM PRODUCTION: BlockStreamManagerImpl
 * KEEPS:
 * - writeItem() logic (REAL)
 * - BlockStreamManagerTask (REAL)
 * - ParallelTask for hashing (REAL)
 * - SequentialTask for processing (REAL)
 * - Merkle tree hashers (REAL - ConcurrentStreamingTreeHasher) - ALL 5 TREES
 * - Merkle tree root computation (REAL - parallel .rootHash())
 * - IncrementalStreamingHasher for previousBlockHashes (REAL - builds tree of all block hashes)
 * - Block hash combining (REAL - COMPLETE: 10x SHA-384 combines including depth1 + timestamp + final root)
 * - BlockFooter creation (REAL - 3 essential hashes)
 * - Running hash manager (REAL - computes n-3 running hash with SHA-384)
 * - State commit cost (SIMULATED - 28x SHA-384 hashes mimicking VirtualMap path rehashing)
 * - State read cost (SIMULATED - 15x SHA-384 hashes mimicking VirtualMap.get())
 * - STATE_CHANGES items (SIMULATED - 2 per block: penultimate + final)
 * - blockStartStateHash tracking (SIMULATED - derived from previous block)
 *
 * REMOVES:
 * - Actual VirtualMap infrastructure (simulated with equivalent SHA-384 work)
 * - Block signing (we compute hash but don't sign)
 * - Quiescence/lifecycle hooks
 * - Disk I/O
 */
public class BenchmarkBlockStreamManager {
    private final ForkJoinPool executor;
    private final Consumer<List<BlockItem>> blockConsumer;

    // Current block state
    private final List<BlockItem> currentBlockItems = new ArrayList<>();
    private BlockStreamManagerTask worker;
    private Timestamp lastUsedTime;

    // REAL merkle tree hashers - ALL 5 trees
    private StreamingTreeHasher consensusHeaderHasher; // Branch 4: EVENT_HEADER, ROUND_HEADER
    private StreamingTreeHasher inputTreeHasher; // Branch 5: SIGNED_TRANSACTION
    private StreamingTreeHasher outputTreeHasher; // Branch 6: TRANSACTION_RESULT, TRANSACTION_OUTPUT, BLOCK_HEADER
    private StreamingTreeHasher stateChangesHasher; // Branch 7: STATE_CHANGES
    private StreamingTreeHasher traceDataHasher; // Branch 8: TRACE_DATA

    // REAL incremental hasher for tracking all previous block hashes
    private IncrementalStreamingHasher previousBlockHashes;

    // REAL running hash manager
    private RunningHashManager runningHashManager;

    // Stats
    private long totalItemsWritten = 0;
    private long totalBlocksSealed = 0;
    private Bytes lastComputedBlockHash = Bytes.EMPTY;
    private Bytes previousBlockHash = Bytes.EMPTY; // Track previous block for footer
    private Bytes lastStateCommitHash = Bytes.EMPTY; // Prevent JIT optimization of state commit
    private Bytes blockStartStateHash = Bytes.EMPTY; // Track state hash at block start

    public BenchmarkBlockStreamManager(Consumer<List<BlockItem>> blockConsumer) {
        this.executor = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
        this.blockConsumer = blockConsumer;
        this.lastUsedTime = Timestamp.DEFAULT;

        // Initialize previousBlockHashes with empty state (production pattern)
        this.previousBlockHashes = new IncrementalStreamingHasher(
                CommonUtils.sha384DigestOrThrow(),
                List.of(), // Empty initial state
                0L // No leaves yet
                );
    }

    /**
     * Start a new block. SIMPLIFIED from startRound().
     */
    public void startBlock(long blockNumber, BlockItem header) {
        currentBlockItems.clear();
        worker = new BlockStreamManagerTask();

        // SIMULATED STATE READ: Mimic reading BlockStreamInfo from VirtualMap
        // Production reads blockStreamInfo from state at startRound()
        // This involves VirtualMap.get() which rehashes path from root to leaf
        simulateStateRead();

        // REAL production hashers - ALL 5 TREES
        consensusHeaderHasher = new ConcurrentStreamingTreeHasher(executor, 128);
        inputTreeHasher = new ConcurrentStreamingTreeHasher(executor, 128);
        outputTreeHasher = new ConcurrentStreamingTreeHasher(executor, 128);
        stateChangesHasher = new ConcurrentStreamingTreeHasher(executor, 128);
        traceDataHasher = new ConcurrentStreamingTreeHasher(executor, 128);

        // REAL running hash manager
        runningHashManager = new RunningHashManager();

        // Add header via task system (REAL)
        worker.addItem(header);
    }

    /**
     * REAL production method - copied exactly from BlockStreamManagerImpl
     */
    public void writeItem(@NonNull final BlockItem item) {
        lastUsedTime = switch (item.item().kind()) {
            case STATE_CHANGES -> item.stateChangesOrThrow().consensusTimestampOrThrow();
            case TRANSACTION_RESULT -> item.transactionResultOrThrow().consensusTimestampOrThrow();
            default -> lastUsedTime;
        };
        worker.addItem(item);
        totalItemsWritten++;
    }

    /**
     * Seal the current block. SIMPLIFIED from endRound().
     * NOW INCLUDES REAL ROOT HASH COMPUTATION + STATE_CHANGES ITEMS
     */
    public void sealBlock(BlockItem proof) {
        // Sync all pending items
        worker.sync();

        // REAL: Add penultimate STATE_CHANGES item (before BlockStreamInfo write)
        // This captures all state changes EXCEPT the final BlockStreamInfo update
        BlockItem penultimateStateChanges = createStateChangesItem(lastUsedTime);
        worker.addItem(penultimateStateChanges);
        worker.sync();

        // REAL: Compute merkle root hashes in parallel
        try {
            // Compute ALL 5 merkle tree roots in parallel
            var consensusHeaderHashFuture = consensusHeaderHasher.rootHash();
            var inputsHashFuture = inputTreeHasher.rootHash();
            var outputsHashFuture = outputTreeHasher.rootHash();
            // Note: stateChangesHash is computed AFTER final STATE_CHANGES item
            var traceDataHashFuture = traceDataHasher.rootHash();

            // Wait for these root hashes (REAL)
            var consensusHeaderHash = consensusHeaderHashFuture.join();
            var inputsHash = inputsHashFuture.join();
            var outputsHash = outputsHashFuture.join();
            var traceDataHash = traceDataHashFuture.join();

            // Note: In production, stateChangesHasher.status() is called here to get penultimate status
            // Then BlockStreamInfo is written to state, causing one more state change
            // We simulate this by adding another STATE_CHANGES item below

            // REAL: Compute previousBlockHashes tree root
            var prevBlockRootsHash = Bytes.wrap(previousBlockHashes.computeRootHash());

            // SIMULATED STATE COMMIT: Mimic VirtualMap.put() + commit() cost
            // Production writes BlockStreamInfo to VirtualMap here
            simulateStateCommit();

            // REAL: Add final STATE_CHANGES item (after BlockStreamInfo write)
            // This captures the final state change (writing BlockStreamInfo itself)
            BlockItem finalStateChanges = createStateChangesItem(lastUsedTime);
            worker.addItem(finalStateChanges);
            worker.sync();

            // NOW compute final stateChangesHash (includes both STATE_CHANGES items)
            var stateChangesHash = stateChangesHasher.rootHash().join();

            // REAL: Combine 8 merkle branches into final block hash
            var blockHash = combineTreeRoots(
                    previousBlockHash, // prevBlockHash (Branch 1) - hash of previous block
                    prevBlockRootsHash, // REAL computed hash (Branch 2) - tree of all prev blocks
                    blockStartStateHash, // REAL tracked hash (Branch 3) - state at block start
                    consensusHeaderHash, // REAL computed hash (Branch 4)
                    inputsHash, // REAL computed hash (Branch 5)
                    outputsHash, // REAL computed hash (Branch 6)
                    stateChangesHash, // REAL computed hash (Branch 7) - includes 2 STATE_CHANGES items
                    traceDataHash, // REAL computed hash (Branch 8)
                    lastUsedTime // REAL timestamp for depth1Node0
                    );

            // REAL: Add this block's hash to the previousBlockHashes tree
            // This prepares Branch 2 for the next block
            previousBlockHashes.addLeaf(blockHash.toByteArray());

            // Store for stats and next block
            lastComputedBlockHash = blockHash;

            // REAL: Create BlockFooter with the three essential hashes
            BlockFooter blockFooter = BlockFooter.newBuilder()
                    .previousBlockRootHash(
                            previousBlockHash.length() > 0
                                    ? previousBlockHash
                                    : Bytes.wrap(new byte[48])) // Use null hash for first block
                    .rootHashOfAllBlockHashesTree(prevBlockRootsHash)
                    .startOfBlockStateRootHash(blockStartStateHash) // REAL tracked value
                    .build();

            // REAL: Add BlockFooter to block stream (last item before BlockProof)
            BlockItem footerItem =
                    BlockItem.newBuilder().blockFooter(blockFooter).build();
            worker.addItem(footerItem);
            worker.sync();

            // Update for next block
            previousBlockHash = blockHash;
            // Update blockStartStateHash for next block (use current block hash as proxy for state hash)
            blockStartStateHash = computeStateHash(blockHash);
        } catch (Exception e) {
            System.err.println("Error computing root hashes: " + e.getMessage());
        }

        // Add proof
        worker.addItem(proof);
        worker.sync();

        // Output block
        blockConsumer.accept(new ArrayList<>(currentBlockItems));
        totalBlocksSealed++;
    }

    public long getTotalItemsWritten() {
        return totalItemsWritten;
    }

    public long getTotalBlocksSealed() {
        return totalBlocksSealed;
    }

    public Bytes getLastComputedBlockHash() {
        return lastComputedBlockHash;
    }

    public Bytes getLastStateCommitHash() {
        return lastStateCommitHash;
    }

    /**
     * REAL: Combines 8 merkle tree roots into final block hash.
     * INCLUDES COMPLETE depth1 + timestamp + final root computation
     * Copied from BlockStreamManagerImpl.combine()
     */
    private static Bytes combineTreeRoots(
            Bytes prevBlockHash,
            Bytes prevBlockRootsHash,
            Bytes startingStateHash,
            Bytes consensusHeaderHash,
            Bytes inputsHash,
            Bytes outputsHash,
            Bytes stateChangesHash,
            Bytes traceDataHash,
            Timestamp firstConsensusTimeOfCurrentBlock) {

        // Use null hash (all zeros) for empty inputs (production pattern)
        final var NULL_HASH = Bytes.wrap(new byte[48]); // SHA-384 = 48 bytes

        prevBlockHash = prevBlockHash.length() == 0 ? NULL_HASH : prevBlockHash;
        prevBlockRootsHash = prevBlockRootsHash.length() == 0 ? NULL_HASH : prevBlockRootsHash;
        startingStateHash = startingStateHash.length() == 0 ? NULL_HASH : startingStateHash;
        consensusHeaderHash = consensusHeaderHash.length() == 0 ? NULL_HASH : consensusHeaderHash;
        inputsHash = inputsHash.length() == 0 ? NULL_HASH : inputsHash;
        outputsHash = outputsHash.length() == 0 ? NULL_HASH : outputsHash;
        stateChangesHash = stateChangesHash.length() == 0 ? NULL_HASH : stateChangesHash;
        traceDataHash = traceDataHash.length() == 0 ? NULL_HASH : traceDataHash;

        // REAL: Compute merkle tree combining (all depth levels)
        // Compute depth four hashes (4 combines)
        final var depth4Node1 = combineSha384(prevBlockHash, prevBlockRootsHash);
        final var depth4Node2 = combineSha384(startingStateHash, consensusHeaderHash);
        final var depth4Node3 = combineSha384(inputsHash, outputsHash);
        final var depth4Node4 = combineSha384(stateChangesHash, traceDataHash);

        // Compute depth three hashes (2 combines)
        final var depth3Node1 = combineSha384(depth4Node1, depth4Node2);
        final var depth3Node2 = combineSha384(depth4Node3, depth4Node4);

        // Compute depth two hashes (1 combine)
        final var depth2Node1 = combineSha384(depth3Node1, depth3Node2);

        // REAL: Compute depth one hashes (2 combines + 1 timestamp hash)
        // Pre-computed constant for depth2Node2
        final var DEPTH_2_NODE_2_COMBINED = combineSha384(
                combineSha384(combineSha384(NULL_HASH, NULL_HASH), combineSha384(NULL_HASH, NULL_HASH)),
                combineSha384(combineSha384(NULL_HASH, NULL_HASH), combineSha384(NULL_HASH, NULL_HASH)));
        final var depth1Node1 = combineSha384(depth2Node1, DEPTH_2_NODE_2_COMBINED);

        // REAL: Hash the timestamp (depth1Node0)
        final var timestamp = Timestamp.PROTOBUF.toBytes(firstConsensusTimeOfCurrentBlock);
        final var depth1Node0 = sha384HashOf(timestamp);

        // REAL: Final root hash combine
        final var rootHash = combineSha384(depth1Node0, depth1Node1);

        return rootHash;
    }

    /**
     * REAL: Combines two hashes using SHA-384.
     * Copied from BlockImplUtils.combine()
     */
    private static Bytes combineSha384(Bytes leftHash, Bytes rightHash) {
        try {
            final var digest = java.security.MessageDigest.getInstance("SHA-384");
            digest.update(leftHash.toByteArray());
            digest.update(rightHash.toByteArray());
            return Bytes.wrap(digest.digest());
        } catch (Exception e) {
            throw new RuntimeException("SHA-384 not available", e);
        }
    }

    /**
     * REAL: Computes SHA-384 hash of bytes.
     * Used for hashing the timestamp in depth1Node0.
     */
    private static Bytes sha384HashOf(Bytes data) {
        try {
            final var digest = java.security.MessageDigest.getInstance("SHA-384");
            digest.update(data.toByteArray());
            return Bytes.wrap(digest.digest());
        } catch (Exception e) {
            throw new RuntimeException("SHA-384 not available", e);
        }
    }

    /**
     * SIMULATED STATE COMMIT: Mimics the cost of VirtualMap.put() + commit().
     *
     * In production, committing BlockStreamInfo to VirtualMap triggers VirtualHasher
     * to rehash the merkle path from the modified leaf to the root. This involves
     * ~log(N) SHA-384 hash operations where N is the total number of leaves in the
     * VirtualMap.
     *
     * For a typical VirtualMap with millions of entries, the tree depth is ~25-30,
     * so we simulate 28 SHA-384 hashes (path from leaf to root).
     *
     * This mimics the REAL CPU cost without requiring actual VirtualMap infrastructure.
     */
    private void simulateStateCommit() {
        final int VIRTUAL_MAP_TREE_DEPTH = 28; // Typical depth for large VirtualMap
        final byte[] NULL_HASH = new byte[48]; // SHA-384 = 48 bytes

        try {
            final var digest = java.security.MessageDigest.getInstance("SHA-384");
            byte[] hash = digest.digest(lastComputedBlockHash.toByteArray()); // Leaf hash

            // REAL: Rehash from leaf to root (log N hashes)
            // This is what VirtualHasher does when you commit a write
            for (int depth = 0; depth < VIRTUAL_MAP_TREE_DEPTH; depth++) {
                digest.reset();
                digest.update(hash);
                digest.update(NULL_HASH); // Sibling hash (simplified - we use null)
                hash = digest.digest();
            }

            // Prevent JIT from optimizing away the work
            lastStateCommitHash = Bytes.wrap(hash);
        } catch (Exception e) {
            System.err.println("Error simulating state commit: " + e.getMessage());
        }
    }

    /**
     * SIMULATED STATE READ: Mimics the cost of VirtualMap.get() to read BlockStreamInfo.
     *
     * In production, reading BlockStreamInfo from VirtualMap at startRound() involves
     * VirtualMap.get() which traverses the merkle path from root to leaf. This involves
     * ~log(N) hash verifications where N is the total number of leaves.
     *
     * For efficiency, we simulate a smaller read cost (~15 hashes) since reads are
     * typically cached and don't require full path rehashing like writes do.
     */
    private void simulateStateRead() {
        final int READ_HASH_COUNT = 15; // Reads are cheaper than writes (cached paths)
        final byte[] NULL_HASH = new byte[48];

        try {
            final var digest = java.security.MessageDigest.getInstance("SHA-384");
            byte[] hash = digest.digest(previousBlockHash.toByteArray());

            // Simulate read cost (path traversal with cached hashes)
            for (int i = 0; i < READ_HASH_COUNT; i++) {
                digest.reset();
                digest.update(hash);
                digest.update(NULL_HASH);
                hash = digest.digest();
            }
        } catch (Exception e) {
            System.err.println("Error simulating state read: " + e.getMessage());
        }
    }

    /**
     * Creates a STATE_CHANGES BlockItem (simulated).
     *
     * In production, BoundaryStateChangeListener captures state changes and produces
     * STATE_CHANGES items via flushChangesFromListener(). We create a minimal
     * STATE_CHANGES item to trigger the hashing and merkle tree updates.
     */
    private BlockItem createStateChangesItem(Timestamp consensusTimestamp) {
        StateChanges stateChanges = StateChanges.newBuilder()
                .consensusTimestamp(consensusTimestamp)
                // In production this would have stateChanges list, but we just need
                // the item to be processed by the pipeline
                .build();

        return BlockItem.newBuilder().stateChanges(stateChanges).build();
    }

    /**
     * Computes a simulated state hash from the block hash.
     *
     * In production, the state hash is computed by hashing the entire state tree.
     * We simulate this by deriving it from the block hash (good enough for benchmarking).
     */
    private Bytes computeStateHash(Bytes blockHash) {
        try {
            final var digest = java.security.MessageDigest.getInstance("SHA-384");
            // Derive state hash from block hash
            digest.update(blockHash.toByteArray());
            digest.update("STATE_HASH".getBytes());
            return Bytes.wrap(digest.digest());
        } catch (Exception e) {
            return Bytes.EMPTY;
        }
    }

    /**
     * ============================================================================
     * REAL PRODUCTION CODE BELOW - Copied from BlockStreamManagerImpl
     * ============================================================================
     */

    /**
     * REAL production class - copied from BlockStreamManagerImpl.BlockStreamManagerTask
     */
    class BlockStreamManagerTask {
        SequentialTask prevTask;
        SequentialTask currentTask;

        BlockStreamManagerTask() {
            prevTask = null;
            currentTask = new SequentialTask();
            currentTask.send();
        }

        void addItem(BlockItem item) {
            new ParallelTask(item, currentTask).send();
            SequentialTask nextTask = new SequentialTask();
            currentTask.send(nextTask);
            prevTask = currentTask;
            currentTask = nextTask;
        }

        void sync() {
            if (prevTask != null) {
                prevTask.join();
            }
        }
    }

    /**
     * REAL production class - copied from BlockStreamManagerImpl.ParallelTask
     */
    class ParallelTask extends AbstractTask {
        BlockItem item;
        SequentialTask out;

        ParallelTask(BlockItem item, SequentialTask out) {
            super(executor, 1);
            this.item = item;
            this.out = out;
        }

        @Override
        protected boolean onExecute() {
            try {
                // REAL production logic: Serialize and hash
                Bytes bytes = BlockItem.PROTOBUF.toBytes(item);
                final var kind = item.item().kind();
                ByteBuffer hash = null;
                switch (kind) {
                    case EVENT_HEADER,
                            SIGNED_TRANSACTION,
                            TRANSACTION_RESULT,
                            TRANSACTION_OUTPUT,
                            STATE_CHANGES,
                            ROUND_HEADER,
                            BLOCK_HEADER,
                            TRACE_DATA -> {
                        MessageDigest digest = sha384DigestOrThrow();
                        bytes.writeTo(digest);
                        hash = ByteBuffer.wrap(digest.digest());
                    }
                    case BLOCK_FOOTER, BLOCK_PROOF, RECORD_FILE, UNSET, REDACTED_ITEM, FILTERED_SINGLE_ITEM -> {
                        // No hashing needed for these item types
                    }
                }
                out.send(item, hash, bytes);
                return true;
            } catch (Exception e) {
                System.err.println("Error hashing item: " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * REAL production class - copied from BlockStreamManagerImpl.SequentialTask
     */
    class SequentialTask extends AbstractTask {
        SequentialTask next;
        BlockItem item;
        Bytes serialized;
        ByteBuffer hash;

        SequentialTask() {
            super(executor, 3);
        }

        @Override
        protected boolean onExecute() {
            if (item != null) {
                // Add to block items list
                currentBlockItems.add(item);

                // REAL production code - merkle tree updates
                final var kind = item.item().kind();
                switch (kind) {
                    case ROUND_HEADER, EVENT_HEADER -> consensusHeaderHasher.addLeaf(hash);
                    case SIGNED_TRANSACTION -> inputTreeHasher.addLeaf(hash);
                    case TRANSACTION_RESULT -> {
                        // REAL running hash computation (n-3 running hash with SHA-384)
                        runningHashManager.nextResultHash(hash);
                        hash.rewind();
                        outputTreeHasher.addLeaf(hash);
                    }
                    case TRANSACTION_OUTPUT, BLOCK_HEADER -> outputTreeHasher.addLeaf(hash);
                    case STATE_CHANGES -> stateChangesHasher.addLeaf(hash);
                    case TRACE_DATA -> traceDataHasher.addLeaf(hash);
                    case BLOCK_FOOTER, BLOCK_PROOF, RECORD_FILE, UNSET, REDACTED_ITEM, FILTERED_SINGLE_ITEM -> {
                        // No merkle tree update for these item types
                    }
                }
            }

            if (next != null) {
                next.send();
            }
            return true;
        }

        @Override
        protected void onException(final Throwable t) {
            System.err.println("Error in sequential task: " + t.getMessage());
        }

        void send(SequentialTask next) {
            this.next = next;
            send();
        }

        void send(BlockItem item, ByteBuffer hash, Bytes serialized) {
            this.item = item;
            this.hash = hash;
            this.serialized = serialized;
            send();
        }
    }

    /**
     * REAL production class - copied from BlockStreamManagerImpl.RunningHashManager
     * Computes n-3 running hash using SHA-384!
     */
    @SuppressWarnings("unused") // nMinus3Hash kept for production code parity
    private static class RunningHashManager {
        private static final ThreadLocal<byte[]> HASHES = ThreadLocal.withInitial(() -> new byte[HASH_SIZE]);
        private static final ThreadLocal<MessageDigest> DIGESTS =
                ThreadLocal.withInitial(CommonUtils::sha384DigestOrThrow);

        byte[] nMinus3Hash;
        byte[] nMinus2Hash;
        byte[] nMinus1Hash;
        byte[] hash;

        RunningHashManager() {
            this.hash = new byte[HASH_SIZE];
        }

        /**
         * Updates the running hashes for the given serialized output block item.
         * REAL production code - does SHA-384 hash computation
         *
         * @param hash the serialized output block item hash
         */
        void nextResultHash(@NonNull final ByteBuffer hash) {
            nMinus3Hash = nMinus2Hash;
            nMinus2Hash = nMinus1Hash;
            nMinus1Hash = this.hash;

            final var digest = DIGESTS.get();
            digest.update(this.hash);
            final var resultHash = HASHES.get();
            hash.get(resultHash);
            digest.update(resultHash);
            this.hash = digest.digest();
        }
    }
}

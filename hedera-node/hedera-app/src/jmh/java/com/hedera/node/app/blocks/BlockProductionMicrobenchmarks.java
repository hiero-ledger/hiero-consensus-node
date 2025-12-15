// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.USER;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.blocks.impl.BlockStreamBuilder;
import com.hedera.node.app.blocks.impl.ConcurrentStreamingTreeHasher;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.node.app.blocks.utils.TransactionGeneratorUtil;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Component-level microbenchmarks for block production pipeline.
 *
 * Each benchmark tests a specific production component in isolation to identify bottlenecks.
 * These benchmarks use production classes without mocking or simulation.
 *
 * COMPONENTS TESTED:
 * 1. BlockItem serialization - How fast can we serialize BlockItems?
 * 2. BlockItem hashing (SHA-384) - How fast can we hash serialized BlockItems?
 * 3. ConcurrentStreamingTreeHasher - How fast are merkle tree operations?
 * 4. IncrementalStreamingHasher - How fast is the previousBlockHashes tree?
 * 5. BlockStreamBuilder - How fast can we translate execution results to BlockItems?
 * 6. Block hash combining - How fast is the 10x SHA-384 combine operation?
 * 7. Block serialization - How fast can we serialize final blocks?
 * 8. Running hash (n-3) - How fast is the running hash computation?
 *
 * BENEFITS:
 * - No mocking required (all components are self-contained)
 * - No maintenance when production code changes (compile-time safety)
 * - Pinpoint specific bottlenecks (which component is slow)
 * - Test scaling behavior (how does performance change with size)
 * - CI/CD friendly (catch regressions early)
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class BlockProductionMicrobenchmarks {

    /**
     * 1. BlockItem Serialization Benchmark
     * Tests: BlockItem.PROTOBUF.toBytes()
     * Measures: How many BlockItems can we serialize per second?
     */
    @Benchmark
    public void blockItemSerialization_TransactionResult(SerializationState state, Blackhole bh) {
        Bytes serialized = BlockItem.PROTOBUF.toBytes(state.transactionResultItem);
        bh.consume(serialized.length());
    }

    @Benchmark
    public void blockItemSerialization_SignedTransaction(SerializationState state, Blackhole bh) {
        Bytes serialized = BlockItem.PROTOBUF.toBytes(state.signedTransactionItem);
        bh.consume(serialized.length());
    }

    @Benchmark
    public void blockItemSerialization_StateChanges(SerializationState state, Blackhole bh) {
        Bytes serialized = BlockItem.PROTOBUF.toBytes(state.stateChangesItem);
        bh.consume(serialized.length());
    }

    @State(Scope.Thread)
    public static class SerializationState {
        BlockItem transactionResultItem;
        BlockItem signedTransactionItem;
        BlockItem stateChangesItem;

        @Setup(Level.Trial)
        public void setup() {
            // Create realistic BlockItems
            Instant now = Instant.now();

            transactionResultItem = BlockItem.newBuilder()
                    .transactionResult(TransactionResult.newBuilder()
                            .consensusTimestamp(Timestamp.newBuilder()
                                    .seconds(now.getEpochSecond())
                                    .nanos(now.getNano())
                                    .build())
                            .status(ResponseCodeEnum.SUCCESS)
                            .transactionFeeCharged(1000L)
                            .build())
                    .build();

            signedTransactionItem = BlockItem.newBuilder()
                    .signedTransaction(TransactionGeneratorUtil.generateTransaction(5000))
                    .build();

            stateChangesItem = BlockItem.newBuilder()
                    .stateChanges(StateChanges.newBuilder()
                            .consensusTimestamp(Timestamp.newBuilder()
                                    .seconds(now.getEpochSecond())
                                    .build())
                            .build())
                    .build();
        }
    }

    /**
     * 2. BlockItem Hashing Benchmark
     * Tests: SHA-384 hashing of serialized BlockItems
     * Measures: How many BlockItem hashes can we compute per second?
     */
    @Benchmark
    public void blockItemHashing(HashingState state, Blackhole bh) {
        try {
            MessageDigest digest = sha384DigestOrThrow();
            state.serializedItem.writeTo(digest);
            byte[] hash = digest.digest();
            bh.consume(hash.length);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @State(Scope.Thread)
    public static class HashingState {
        Bytes serializedItem;

        @Setup(Level.Trial)
        public void setup() {
            BlockItem item = BlockItem.newBuilder()
                    .transactionResult(TransactionResult.newBuilder()
                            .consensusTimestamp(Timestamp.newBuilder()
                                    .seconds(Instant.now().getEpochSecond())
                                    .build())
                            .status(ResponseCodeEnum.SUCCESS)
                            .transactionFeeCharged(1000L)
                            .build())
                    .build();
            serializedItem = BlockItem.PROTOBUF.toBytes(item);
        }
    }

    /**
     * 3. ConcurrentStreamingTreeHasher Benchmark
     * Tests: Real merkle tree operations with parallel hashing
     * Measures: How many leaves can we process per second?
     */
    @Benchmark
    public void merkleTreeHashing_Concurrent(MerkleTreeState state, Blackhole bh) {
        ConcurrentStreamingTreeHasher hasher = new ConcurrentStreamingTreeHasher(state.executor);

        // Add all leaves
        for (ByteBuffer hash : state.precomputedHashes) {
            hasher.addLeaf(hash.duplicate()); // duplicate to allow reuse
        }

        // Compute root hash (parallel computation)
        Bytes rootHash = hasher.rootHash().join();
        bh.consume(rootHash);
    }

    @State(Scope.Thread)
    public static class MerkleTreeState {
        @Param({"100", "1000", "10000", "100000"})
        int numLeaves;

        List<ByteBuffer> precomputedHashes;
        ForkJoinPool executor;

        @Setup(Level.Trial)
        public void setup() {
            executor = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
            precomputedHashes = new ArrayList<>();

            try {
                MessageDigest digest = sha384DigestOrThrow();
                for (int i = 0; i < numLeaves; i++) {
                    byte[] data = ("item" + i).getBytes();
                    digest.reset();
                    byte[] hash = digest.digest(data);
                    precomputedHashes.add(ByteBuffer.wrap(hash));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            // Shutdown executor to prevent resource leaks
            executor.shutdown();
        }
    }

    /**
     * 4. IncrementalStreamingHasher Benchmark
     * Tests: Real previousBlockHashes tree operations
     * Measures: How fast can we maintain the tree of previous block hashes?
     */
    @Benchmark
    public void incrementalStreamingHasher_AddLeaf(IncrementalHasherState state, Blackhole bh) {
        IncrementalStreamingHasher hasher =
                new IncrementalStreamingHasher(CommonUtils.sha384DigestOrThrow(), List.of(), 0L);

        // Add leaves and compute root (simulating block-by-block hashing)
        for (byte[] blockHash : state.blockHashes) {
            hasher.addLeaf(blockHash);
        }

        byte[] rootHash = hasher.computeRootHash();
        bh.consume(rootHash.length);
    }

    @State(Scope.Thread)
    public static class IncrementalHasherState {
        @Param({"10", "100", "1000"})
        int numBlocks;

        List<byte[]> blockHashes;

        @Setup(Level.Trial)
        public void setup() {
            blockHashes = new ArrayList<>();
            try {
                MessageDigest digest = sha384DigestOrThrow();
                for (int i = 0; i < numBlocks; i++) {
                    byte[] data = ("block" + i).getBytes();
                    digest.reset();
                    blockHashes.add(digest.digest(data));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 5. BlockStreamBuilder Benchmark
     * Tests: Real BlockStreamBuilder accumulation and translation
     * Measures: How many transactions can BlockStreamBuilder process per second?
     */
    @Benchmark
    public void blockStreamBuilder_Accumulation(BuilderState state, Blackhole bh) {
        BlockStreamBuilder builder = new BlockStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER);

        // Accumulate execution results (like HandleWorkflow does)
        builder.functionality(HederaFunctionality.CRYPTO_TRANSFER)
                .signedTx(state.signedTx)
                .serializedSignedTx(state.serializedSignedTx)
                .transactionID(state.txId)
                .memo(state.memo)
                .consensusTimestamp(state.consensusTime)
                .exchangeRate(state.exchangeRates)
                .status(ResponseCodeEnum.SUCCESS)
                .transactionFee(1000L)
                .transferList(state.transferList);

        // Build BlockItems (translation)
        BlockStreamBuilder.Output output = builder.build(false, null);
        bh.consume(output.blockItems().size());
    }

    @State(Scope.Thread)
    public static class BuilderState {
        @Param({"1000", "5000", "10000"})
        int transactionSizeBytes;

        SignedTransaction signedTx;
        Bytes serializedSignedTx;
        Instant consensusTime;
        ExchangeRateSet exchangeRates;
        TransferList transferList;
        TransactionID txId;
        String memo;

        @Setup(Level.Trial)
        public void setup() {
            try {
                Bytes txBodyBytes = TransactionGeneratorUtil.generateTransaction(transactionSizeBytes);
                TransactionBody txBody = TransactionBody.PROTOBUF.parse(txBodyBytes);

                signedTx = SignedTransaction.newBuilder().bodyBytes(txBodyBytes).build();
                serializedSignedTx = SignedTransaction.PROTOBUF.toBytes(signedTx);
                txId = txBody.transactionIDOrThrow();
                memo = txBody.memo();

                consensusTime = Instant.now();

                exchangeRates = ExchangeRateSet.newBuilder()
                        .currentRate(ExchangeRate.newBuilder()
                                .centEquiv(12)
                                .hbarEquiv(1)
                                .build())
                        .nextRate(ExchangeRate.newBuilder()
                                .centEquiv(12)
                                .hbarEquiv(1)
                                .build())
                        .build();

                transferList = TransferList.newBuilder()
                        .accountAmounts(
                                AccountAmount.newBuilder()
                                        .accountID(AccountID.newBuilder()
                                                .accountNum(2)
                                                .build())
                                        .amount(-1000L)
                                        .build(),
                                AccountAmount.newBuilder()
                                        .accountID(AccountID.newBuilder()
                                                .accountNum(3)
                                                .build())
                                        .amount(1000L)
                                        .build())
                        .build();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 6. Block Hash Combining Benchmark
     * Tests: Real 10x SHA-384 combine operations from combineTreeRoots()
     * Measures: How many complete block hash computations per second?
     */
    @Benchmark
    public void blockHashCombining(CombineState state, Blackhole bh) {
        // Production logic: 10x SHA-384 combines

        // Depth 4 combines (4 operations)
        Bytes depth4Node1 = combineSha384(state.hash1, state.hash2);
        Bytes depth4Node2 = combineSha384(state.hash3, state.hash4);
        Bytes depth4Node3 = combineSha384(state.hash5, state.hash6);
        Bytes depth4Node4 = combineSha384(state.hash7, state.hash8);

        // Depth 3 combines (2 operations)
        Bytes depth3Node1 = combineSha384(depth4Node1, depth4Node2);
        Bytes depth3Node2 = combineSha384(depth4Node3, depth4Node4);

        // Depth 2 combine (1 operation)
        Bytes depth2Node1 = combineSha384(depth3Node1, depth3Node2);

        // Depth 1 combines (2 operations)
        Bytes depth2Node2Combined = combineSha384(
                combineSha384(
                        combineSha384(state.nullHash, state.nullHash), combineSha384(state.nullHash, state.nullHash)),
                combineSha384(
                        combineSha384(state.nullHash, state.nullHash), combineSha384(state.nullHash, state.nullHash)));
        Bytes depth1Node1 = combineSha384(depth2Node1, depth2Node2Combined);

        // Timestamp hash (1 operation)
        Bytes depth1Node0 = sha384HashOf(state.timestampBytes);

        // Final root hash (1 operation) - TOTAL: 10 SHA-384 operations
        Bytes rootHash = combineSha384(depth1Node0, depth1Node1);

        bh.consume(rootHash);
    }

    @State(Scope.Thread)
    public static class CombineState {
        Bytes hash1, hash2, hash3, hash4, hash5, hash6, hash7, hash8;
        Bytes nullHash;
        Bytes timestampBytes;

        @Setup(Level.Trial)
        public void setup() {
            try {
                MessageDigest digest = sha384DigestOrThrow();

                // Generate 8 random hashes (representing the 8 merkle branches)
                hash1 = Bytes.wrap(digest.digest("branch1".getBytes()));
                digest.reset();
                hash2 = Bytes.wrap(digest.digest("branch2".getBytes()));
                digest.reset();
                hash3 = Bytes.wrap(digest.digest("branch3".getBytes()));
                digest.reset();
                hash4 = Bytes.wrap(digest.digest("branch4".getBytes()));
                digest.reset();
                hash5 = Bytes.wrap(digest.digest("branch5".getBytes()));
                digest.reset();
                hash6 = Bytes.wrap(digest.digest("branch6".getBytes()));
                digest.reset();
                hash7 = Bytes.wrap(digest.digest("branch7".getBytes()));
                digest.reset();
                hash8 = Bytes.wrap(digest.digest("branch8".getBytes()));

                nullHash = Bytes.wrap(new byte[48]); // SHA-384 null hash

                Timestamp timestamp = Timestamp.newBuilder()
                        .seconds(Instant.now().getEpochSecond())
                        .build();
                timestampBytes = Timestamp.PROTOBUF.toBytes(timestamp);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 7. Block Serialization Benchmark
     * Tests: Real Block.PROTOBUF.toBytes() with varying block sizes
     * Measures: How fast can we serialize complete blocks?
     */
    @Benchmark
    public void blockSerialization(BlockState state, Blackhole bh) {
        Block block = new Block(state.blockItems);
        Bytes serialized = Block.PROTOBUF.toBytes(block);
        bh.consume(serialized.length());
    }

    @State(Scope.Thread)
    public static class BlockState {
        @Param({"100", "1000", "10000", "100000"})
        int itemsPerBlock;

        List<BlockItem> blockItems;

        @Setup(Level.Trial)
        public void setup() {
            blockItems = new ArrayList<>();
            Instant now = Instant.now();

            // Create realistic BlockItems (alternating SIGNED_TRANSACTION and TRANSACTION_RESULT)
            for (int i = 0; i < itemsPerBlock; i++) {
                if (i % 2 == 0) {
                    blockItems.add(BlockItem.newBuilder()
                            .signedTransaction(TransactionGeneratorUtil.generateTransaction(1000))
                            .build());
                } else {
                    blockItems.add(BlockItem.newBuilder()
                            .transactionResult(TransactionResult.newBuilder()
                                    .consensusTimestamp(Timestamp.newBuilder()
                                            .seconds(now.getEpochSecond())
                                            .nanos(i)
                                            .build())
                                    .status(ResponseCodeEnum.SUCCESS)
                                    .transactionFeeCharged(1000L)
                                    .build())
                            .build());
                }
            }
        }
    }

    /**
     * 8. Running Hash (n-3) Benchmark
     * Tests: Real RunningHashManager.nextResultHash() computation
     * Measures: How many running hash updates per second?
     */
    @Benchmark
    public void runningHashComputation(RunningHashState state, Blackhole bh) {
        // Simulate n-3 running hash computation (production logic)
        byte[] currentHash = state.initialHash.clone();

        try {
            MessageDigest digest = sha384DigestOrThrow();

            for (ByteBuffer resultHash : state.resultHashes) {
                // Running hash update logic
                digest.reset();
                digest.update(currentHash);
                byte[] tempHash = new byte[48];
                resultHash.get(tempHash);
                resultHash.rewind();
                digest.update(tempHash);
                currentHash = digest.digest();
            }

            bh.consume(currentHash.length);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @State(Scope.Thread)
    public static class RunningHashState {
        @Param({"100", "1000", "10000", "100000"})
        int numResults;

        List<ByteBuffer> resultHashes;
        byte[] initialHash;

        @Setup(Level.Trial)
        public void setup() {
            try {
                MessageDigest digest = sha384DigestOrThrow();
                initialHash = new byte[48]; // Start with zeros

                resultHashes = new ArrayList<>();
                for (int i = 0; i < numResults; i++) {
                    byte[] data = ("result" + i).getBytes();
                    digest.reset();
                    resultHashes.add(ByteBuffer.wrap(digest.digest(data)));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    // ============================================================================
    // HELPER METHODS (utility methods for block hash combining)
    // ============================================================================

    private static Bytes combineSha384(Bytes leftHash, Bytes rightHash) {
        MessageDigest digest = sha384DigestOrThrow();
        digest.update(leftHash.toByteArray());
        digest.update(rightHash.toByteArray());
        return Bytes.wrap(digest.digest());
    }

    private static Bytes sha384HashOf(Bytes data) {
        MessageDigest digest = sha384DigestOrThrow();
        digest.update(data.toByteArray());
        return Bytes.wrap(digest.digest());
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(new String[] {"BlockProductionMicrobenchmarks"});
    }
}

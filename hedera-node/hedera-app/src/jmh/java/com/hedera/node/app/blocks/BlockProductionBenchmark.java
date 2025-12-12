// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.USER;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.blocks.impl.BlockStreamBuilder;
import com.hedera.node.app.blocks.utils.BenchmarkBlockStreamManager;
import com.hedera.node.app.blocks.utils.TransactionGeneratorUtil;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Block production benchmark using production components.
 *
 * PRODUCTION COMPONENTS TESTED:
 * 1. BlockStreamBuilder - Accumulates execution results and generates BlockItems
 * 2. BlockStreamManagerTask - REAL parallel hashing (SHA-384) of BlockItems
 * 3. BlockStreamManagerTask - REAL sequential merkle tree updates
 * 4. ConcurrentStreamingTreeHasher - REAL merkle tree root computation (parallel) - ALL 5 TREES
 * 5. IncrementalStreamingHasher - REAL previousBlockHashes tree computation
 * 6. Block root hash combining - REAL 10x SHA-384 combines (depth4-2-1-0 + timestamp + final root)
 * 7. BlockFooter creation - REAL footer with 3 essential hashes
 * 8. State operations - SIMULATED (read: 15x SHA-384, commit: 28x SHA-384 = VirtualMap costs)
 * 9. STATE_CHANGES items - SIMULATED (2 per block: penultimate + final)
 * 10. blockStartStateHash tracking - SIMULATED (derived from previous block hash)
 * 11. Block assembly and serialization
 *
 * FLOW (matches BlockStreamManagerImpl):
 * 1. openBlock() → SIMULATE state read, create task worker + 5 merkle tree hashers
 * 2. For each transaction:
 *    - BlockStreamBuilder accumulates results
 *    - builder.build() → generates BlockItems
 *    - writeItem() → REAL parallel hash + sequential add (BlockStreamManagerTask pattern)
 * 3. sealBlock() → sync tasks, add penultimate STATE_CHANGES, compute 5 merkle roots in parallel,
 *                  SIMULATE state commit, add final STATE_CHANGES, compute final stateChangesHash,
 *                  combine into block hash, add to previousBlockHashes tree, create BlockFooter,
 *                  add proof, finalize
 *
 * MEASURES:
 * - BlockStreamBuilder accumulation and translation
 * - Parallel BlockItem hashing (SHA-384) - REAL production task system
 * - Sequential processing and block accumulation
 * - Merkle tree leaf additions (per BlockItem) - ALL 5 TREES
 * - Merkle tree root computation (per block) - PARALLEL via ConcurrentStreamingTreeHasher
 * - IncrementalStreamingHasher for previousBlockHashes tree (1 SHA-384 per block)
 * - Block root hash combining (COMPLETE: 10x SHA-384 per block = depth4-2-1-0 + timestamp + final)
 * - BlockFooter creation with 3 essential hashes including REAL blockStartStateHash
 * - State read cost (SIMULATED - 15x SHA-384 per block = VirtualMap.get() cost)
 * - State commit cost (SIMULATED - 28x SHA-384 per block = VirtualMap.put() + commit() cost)
 * - STATE_CHANGES items (2 per block) with merkle tree updates
 * - Block sealing and serialization
 * - Memory allocation under load
 *
 * DOES NOT TEST:
 * - Transaction execution (happens before)
 * - Actual VirtualMap infrastructure (CPU cost is simulated with equivalent SHA-384 work)
 * - Network streaming (happens after)
 * - Block signing (async, doesn't block pipeline)
 * - Disk I/O (intentionally excluded)
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(1)
public class BlockProductionBenchmark {

    @Param({"20"}) // Number of blocks to produce (reduced for faster feedback at 100K TPS)
    private int blocksPerRun;

    @Param({"2000"}) // Block time in milliseconds
    private int blockTimeMs;

    @Param({"100000"}) // Target TPS
    private int targetTPS;

    @Param({"6000"}) // Transaction size in bytes
    private int transactionSizeBytes;

    @Param({/*"true", */"false"}) // Enable rate limiting (false = max capacity stress test)
    private boolean enableRateLimiting;

    private int maxTransactionsPerBlock;
    private long benchmarkStartTime;
    private long benchmarkEndTime;
    private int totalTransactionsProcessed;
    private long totalBytesGenerated;

    // Transaction tracking - verify system keeps up with target TPS
    private int totalTransactionsGenerated = 0; // How many we tried to send
    private int totalBlockItemsInBlocks = 0; // How many actually made it to blocks
    private long totalTimeSpentWaiting = 0; // Time spent sleeping for rate limiting

    // Production manager (copied from BlockStreamManagerImpl)
    private BenchmarkBlockStreamManager blockStreamManager;

    // Template data for creating realistic transactions
    private SignedTransaction signedTxTemplate;
    private Bytes serializedSignedTxTemplate;
    private TransactionBody txBodyTemplate;
    private ExchangeRateSet exchangeRates;
    private TransferList sampleTransferList;

    @Setup(Level.Trial)
    public void setupTrial() {
        maxTransactionsPerBlock = (blockTimeMs * targetTPS) / 1000;

        try {
            // Generate template TransactionBody
            Bytes txBodyBytes = TransactionGeneratorUtil.generateTransaction(transactionSizeBytes);
            txBodyTemplate = TransactionBody.PROTOBUF.parse(txBodyBytes);

            // Wrap in SignedTransaction (production pattern)
            signedTxTemplate =
                    SignedTransaction.newBuilder().bodyBytes(txBodyBytes).build();
            serializedSignedTxTemplate = SignedTransaction.PROTOBUF.toBytes(signedTxTemplate);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse template transaction", e);
        }

        // Create realistic exchange rates (used in TransactionResult)
        exchangeRates = ExchangeRateSet.newBuilder()
                .currentRate(
                        ExchangeRate.newBuilder().centEquiv(12).hbarEquiv(1).build())
                .nextRate(ExchangeRate.newBuilder().centEquiv(12).hbarEquiv(1).build())
                .build();

        // Create sample transfer list (realistic execution result)
        sampleTransferList = TransferList.newBuilder()
                .accountAmounts(
                        AccountAmount.newBuilder()
                                .accountID(AccountID.newBuilder().accountNum(2).build())
                                .amount(-1000L)
                                .build(),
                        AccountAmount.newBuilder()
                                .accountID(AccountID.newBuilder().accountNum(3).build())
                                .amount(1000L)
                                .build())
                .build();

        System.out.printf(
                ">>> Config: Blocks/run=%d, Block time=%dms, Target TPS=%d, Tx size=%dB, Max tx/block=%d%n",
                blocksPerRun, blockTimeMs, targetTPS, transactionSizeBytes, maxTransactionsPerBlock);
        System.out.printf(
                ">>> Mode: %s%n",
                enableRateLimiting ? "Rate-limited (target TPS)" : "MAX CAPACITY STRESS TEST (no rate limiting)");
        System.out.printf(">>> Using REAL BlockStreamManagerImpl code:%n");
        System.out.printf(">>>   - Parallel SHA-384 hashing (REAL ParallelTask)%n");
        System.out.printf(">>>   - Sequential task coordination (REAL SequentialTask)%n");
        System.out.printf(">>>   - Merkle tree leaf addition (REAL ConcurrentStreamingTreeHasher) - ALL 5 TREES%n");
        System.out.printf(">>>   - Merkle tree root computation (REAL parallel .rootHash()) - ALL 5 TREES%n");
        System.out.printf(">>>   - IncrementalStreamingHasher for previousBlockHashes tree%n");
        System.out.printf(
                ">>>   - Block hash combining (COMPLETE: 10x SHA-384 per block incl. depth1+timestamp+final)%n");
        System.out.printf(">>>   - BlockFooter creation with 3 essential hashes (REAL blockStartStateHash)%n");
        System.out.printf(">>>   - State read simulation (15x SHA-384 per block = VirtualMap.get() cost)%n");
        System.out.printf(">>>   - State commit simulation (28x SHA-384 per block = VirtualMap.put() cost)%n");
        System.out.printf(">>>   - STATE_CHANGES items (2 per block: penultimate + final)%n");
        System.out.printf(">>>   - Running hash computation (REAL RunningHashManager - n-3 hash with SHA-384)%n");
    }

    @Setup(Level.Iteration)
    public void setupIteration() {
        benchmarkStartTime = 0;
        benchmarkEndTime = 0;
        totalTransactionsProcessed = 0;
        totalBytesGenerated = 0;
        totalTransactionsGenerated = 0;
        totalBlockItemsInBlocks = 0;
        totalTimeSpentWaiting = 0;

        // Create fresh manager for each iteration to reset counters
        blockStreamManager = new BenchmarkBlockStreamManager(blockItems -> {
            // Consumer for sealed blocks
            Block block = new Block(blockItems);
            Bytes serialized = Block.PROTOBUF.toBytes(block);
            totalBytesGenerated += serialized.length();
        });
    }

    /**
     * Main benchmark: Uses REAL BlockStreamManagerImpl task system.
     * startBlock() → writeItem() for each transaction → sealBlock()
     */
    @Benchmark
    public int produceBlocks(Blackhole bh) {
        benchmarkStartTime = System.nanoTime();

        // Produce configured number of blocks
        for (int blockNum = 0; blockNum < blocksPerRun; blockNum++) {
            produceOneBlock(blockNum);
        }

        benchmarkEndTime = System.nanoTime();

        // Consume computed hashes to prevent JIT optimization
        bh.consume(blockStreamManager.getTotalItemsWritten());
        bh.consume(blockStreamManager.getLastComputedBlockHash());
        bh.consume(blockStreamManager.getLastStateCommitHash());
        return blocksPerRun;
    }

    /**
     * Produces a single block using REAL BlockStreamManagerImpl pattern:
     * 1. Start block
     * 2. Process transactions → writeItem() (REAL production method!)
     * 3. Seal block
     */
    private void produceOneBlock(long blockNumber) {
        long blockStartTime = System.currentTimeMillis();
        int txGeneratedThisBlock = 0;
        long blockItemsBeforeBlock = blockStreamManager.getTotalItemsWritten();

        // 1. START BLOCK (production pattern)
        Instant timestamp = Instant.now();
        BlockItem header = BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder()
                        .number(blockNumber)
                        .blockTimestamp(Timestamp.newBuilder()
                                .seconds(timestamp.getEpochSecond())
                                .nanos(timestamp.getNano())
                                .build())
                        .softwareVersion(SemanticVersion.newBuilder()
                                .major(0)
                                .minor(56)
                                .patch(0)
                                .build())
                        .build())
                .build();

        blockStreamManager.startBlock(blockNumber, header);

        Instant consensusNow = timestamp;
        int txInBlock = 0;

        // 2. PROCESS TRANSACTIONS - via REAL writeItem()
        // In max capacity mode: process all transactions as fast as possible
        // In rate-limited mode: respect block time boundary
        while (txInBlock < maxTransactionsPerBlock) {
            if (enableRateLimiting && (System.currentTimeMillis() - blockStartTime >= blockTimeMs)) {
                break;
            }

            // Create BlockStreamBuilder for this transaction
            BlockStreamBuilder builder = new BlockStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER);

            // Accumulate execution results (simulating HandleWorkflow)
            builder.functionality(HederaFunctionality.CRYPTO_TRANSFER)
                    .signedTx(signedTxTemplate)
                    .serializedSignedTx(serializedSignedTxTemplate)
                    .transactionID(txBodyTemplate.transactionIDOrThrow())
                    .memo(txBodyTemplate.memo())
                    .consensusTimestamp(consensusNow)
                    .exchangeRate(exchangeRates)
                    .status(ResponseCodeEnum.SUCCESS)
                    .transactionFee(1000L)
                    .transferList(sampleTransferList);

            // Build BlockItems from accumulated results
            BlockStreamBuilder.Output output = builder.build(false, null);

            // WRITE ITEMS via REAL BlockStreamManagerImpl.writeItem()
            for (BlockItem item : output.blockItems()) {
                blockStreamManager.writeItem(item);
            }

            txInBlock++;
            txGeneratedThisBlock++;
            totalTransactionsProcessed++;
            consensusNow = consensusNow.plusNanos(1);

            // Rate limiting - simulate transaction arrival (only if enabled)
            if (enableRateLimiting) {
                long targetIntervalNanos = TimeUnit.SECONDS.toNanos(1) / targetTPS;
                long now = System.nanoTime();
                long nextTxTime = benchmarkStartTime + (totalTransactionsProcessed * targetIntervalNanos);
                long sleepNanos = nextTxTime - now;

                if (sleepNanos > 0 && sleepNanos < TimeUnit.MILLISECONDS.toNanos(blockTimeMs)) {
                    try {
                        long sleepStart = System.nanoTime();
                        TimeUnit.NANOSECONDS.sleep(sleepNanos);
                        totalTimeSpentWaiting += (System.nanoTime() - sleepStart);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        // 3. SEAL BLOCK (production pattern)
        BlockItem proof = BlockItem.newBuilder()
                .blockProof(com.hedera.hapi.block.stream.BlockProof.newBuilder()
                        .block(blockNumber)
                        .build())
                .build();
        blockStreamManager.sealBlock(proof);

        // Track what actually made it into the block
        long blockItemsAfterBlock = blockStreamManager.getTotalItemsWritten();
        long itemsInThisBlock = blockItemsAfterBlock - blockItemsBeforeBlock;
        totalTransactionsGenerated += txGeneratedThisBlock;
        totalBlockItemsInBlocks += (int) itemsInThisBlock;

        System.out.printf(
                "[Block %d] Generated %d tx → %d BlockItems in block (%.1f items/tx)%n",
                blockNumber, txGeneratedThisBlock, itemsInThisBlock, (double) itemsInThisBlock / txGeneratedThisBlock);
    }

    @TearDown(Level.Iteration)
    public void teardownIteration() {
        if (benchmarkStartTime > 0 && benchmarkEndTime > 0) {
            double elapsedSeconds = (benchmarkEndTime - benchmarkStartTime) / 1_000_000_000.0;
            double actualTPS = totalTransactionsProcessed / elapsedSeconds;
            double avgTxPerBlock = (double) totalTransactionsProcessed / blocksPerRun;
            double avgBlockSizeMB = (totalBytesGenerated / (double) blocksPerRun) / (1024 * 1024);
            double avgItemsPerTx = (double) blockStreamManager.getTotalItemsWritten() / totalTransactionsProcessed;
            double waitTimeSeconds = totalTimeSpentWaiting / 1_000_000_000.0;
            double utilization = ((elapsedSeconds - waitTimeSeconds) / elapsedSeconds) * 100;

            System.out.printf("%n>>> SUMMARY:%n");
            System.out.printf(
                    ">>> Mode: %s%n",
                    enableRateLimiting ? "Rate-limited (target TPS)" : "MAX CAPACITY STRESS TEST");
            System.out.printf(
                    ">>> Target TPS: %d, Actual TPS: %.0f (%.1f%% of target)%n",
                    targetTPS, actualTPS, (actualTPS / targetTPS) * 100);
            System.out.printf(
                    ">>> Transactions: Generated=%d, Processed=%d%n",
                    totalTransactionsGenerated, totalTransactionsProcessed);
            System.out.printf(
                    ">>> BlockItems: In blocks=%d, From manager=%d (should match!)%n",
                    totalBlockItemsInBlocks, blockStreamManager.getTotalItemsWritten());
            System.out.printf(
                    ">>> Time: Total=%.3fs, Waiting=%.3fs (%.1f%% utilized)%n",
                    elapsedSeconds, waitTimeSeconds, utilization);
            System.out.printf(
                    ">>> Blocks: %d blocks, %.0f tx/block, %.2f MB/block, %.1f items/tx%n",
                    blocksPerRun, avgTxPerBlock, avgBlockSizeMB, avgItemsPerTx);

            // WARNING if system couldn't keep up (only relevant when rate limiting is enabled)
            if (enableRateLimiting) {
                if (actualTPS < targetTPS * 0.95) {
                    System.out.printf(
                            "%n⚠️  WARNING: System only achieved %.0f TPS (%.1f%% of target %d TPS)%n",
                            actualTPS, (actualTPS / targetTPS) * 100, targetTPS);
                    System.out.printf("⚠️  This indicates a REAL bottleneck in block production!%n");
                } else {
                    System.out.printf(
                            "%n✅ System kept up with target TPS (%.1f%% achieved)%n", (actualTPS / targetTPS) * 100);
                }
            } else {
                System.out.printf(
                        "%n🚀 MAX CAPACITY: System achieved %.0f TPS without rate limiting%n", actualTPS);
                System.out.printf(
                        "🚀 This is %.1fx the target rate of %d TPS%n", actualTPS / targetTPS, targetTPS);
                System.out.printf("🚀 Utilization: %.1f%% (%.3fs total time)%n", utilization, elapsedSeconds);
            }
        }

        System.gc();
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(new String[] {"BlockProductionBenchmark"});
    }
}

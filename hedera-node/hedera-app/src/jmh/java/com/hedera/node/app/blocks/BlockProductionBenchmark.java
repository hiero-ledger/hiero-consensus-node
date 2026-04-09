// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ACCOUNTS;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.USER;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.input.RoundHeader;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.blocks.impl.BlockStreamBuilder;
import com.hedera.node.app.blocks.utils.BlockStreamManagerWrapper;
import com.hedera.node.app.blocks.utils.NoOpDependencies;
import com.hedera.node.app.blocks.utils.TransactionGeneratorUtil;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Block production benchmark using BlockStreamManagerImpl.
 *
 * This benchmark uses the actual production BlockStreamManagerImpl class with
 * NoOpDependencies
 * to provide all required dependencies. This provides the most realistic
 * performance measurement
 * as it uses the exact production code path.
 *
 * PRODUCTION COMPONENTS TESTED:
 * - REAL BlockStreamManagerImpl (complete production implementation)
 * - REAL task system (ParallelTask, SequentialTask)
 * - REAL merkle tree operations (all 5 trees)
 * - REAL block hash computation
 * - REAL state management (via NoOpDependencies)
 *
 * DOES NOT TEST:
 * - Transaction execution (happens before)
 * - Network streaming (happens after)
 * - Block signing (async, doesn't block pipeline)
 * - Disk I/O (NoOpBlockItemWriter)
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(1)
public class BlockProductionBenchmark {

    @Param({"20"}) // Number of blocks to produce
    private int blocksPerRun;

    @Param({"2000"}) // Block time in milliseconds
    private int blockTimeMs;

    @Param({"100000"}) // Target TPS
    private int targetTPS;

    @Param({"6000"}) // Transaction size in bytes
    private int transactionSizeBytes;

    @Param({"false"}) // Enable rate limiting (false = max capacity stress test)
    private boolean enableRateLimiting;

    private int maxTransactionsPerBlock;
    private long benchmarkStartTime;
    private long benchmarkEndTime;
    private int totalTransactionsProcessed;

    // Transaction tracking
    private int totalTransactionsGenerated = 0;
    private int totalBlockItemsInBlocks = 0;
    private long totalTimeSpentWaiting = 0;

    // REAL production manager (wrapped for simpler API)
    private BlockStreamManagerWrapper blockStreamManager;

    // Template data
    // BENCHMARK LIMITATION: Same transaction template is reused for all
    // transactions.
    // This improves benchmark performance but doesn't reflect production diversity.
    // TODO: Add parameter to control transaction diversity (different types, sizes,
    // accounts)
    private SignedTransaction signedTxTemplate;
    private Bytes serializedSignedTxTemplate;
    private TransactionBody txBodyTemplate;
    private ExchangeRateSet exchangeRates;
    private TransferList sampleTransferList;

    private Recording jfrRecording;

    @Setup(Level.Trial)
    public void setupTrial() {
        maxTransactionsPerBlock = (blockTimeMs * targetTPS) / 1000;

        try {
            Bytes txBodyBytes = TransactionGeneratorUtil.generateTransaction(transactionSizeBytes);
            txBodyTemplate = TransactionBody.PROTOBUF.parse(txBodyBytes);
            signedTxTemplate =
                    SignedTransaction.newBuilder().bodyBytes(txBodyBytes).build();
            serializedSignedTxTemplate = SignedTransaction.PROTOBUF.toBytes(signedTxTemplate);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse template transaction", e);
        }

        exchangeRates = ExchangeRateSet.newBuilder()
                .currentRate(
                        ExchangeRate.newBuilder().centEquiv(12).hbarEquiv(1).build())
                .nextRate(ExchangeRate.newBuilder().centEquiv(12).hbarEquiv(1).build())
                .build();

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
                ">>> Mode: %s%n", enableRateLimiting ? "Rate-limited (target TPS)" : "MAX CAPACITY STRESS TEST");
        System.out.printf(">>> Using REAL BlockStreamManagerImpl with NoOpDependencies%n");

        // Start JFR recording
        try {
            String projectRoot = System.getProperty("user.dir");
            String jfrPath = String.format(
                    "%s/hedera-node/hedera-app/src/jmh/java/com/hedera/node/app/blocks/jfr/block-prod-tps%d-size%d.jfr",
                    projectRoot, targetTPS, transactionSizeBytes);
            jfrRecording = new Recording(Configuration.getConfiguration("profile"));
            jfrRecording.setDestination(Paths.get(jfrPath));
            jfrRecording.start();
            System.out.println(">>> JFR recording started: " + jfrPath);
        } catch (Exception e) {
            System.err.println(">>> Failed to start JFR recording: " + e.getMessage());
        }
    }

    @Setup(Level.Iteration)
    public void setupIteration() {
        benchmarkStartTime = 0;
        benchmarkEndTime = 0;
        totalTransactionsProcessed = 0;
        totalTransactionsGenerated = 0;
        totalBlockItemsInBlocks = 0;
        totalTimeSpentWaiting = 0;

        // Create REAL BlockStreamManagerImpl wrapper with NoOpDependencies
        blockStreamManager = new BlockStreamManagerWrapper(NoOpDependencies.NoOpBlockItemWriter::new);
    }

    /**
     * Main benchmark: Uses BlockStreamManagerImpl.
     */
    @Benchmark
    public int produceBlocks(Blackhole bh) {
        benchmarkStartTime = System.nanoTime();

        for (int blockNum = 0; blockNum < blocksPerRun; blockNum++) {
            produceOneBlock(blockNum);
        }

        benchmarkEndTime = System.nanoTime();

        bh.consume(blockStreamManager.getTotalItemsWritten());
        bh.consume(blockStreamManager.getLastComputedBlockHash());
        return blocksPerRun;
    }

    /**
     * Produces a single block using REAL BlockStreamManagerImpl.
     */
    private void produceOneBlock(long blockNumber) {
        long blockStartTime = System.currentTimeMillis();
        int txGeneratedThisBlock = 0;
        long blockItemsBeforeBlock = blockStreamManager.getTotalItemsWritten();

        // START BLOCK (production pattern via wrapper)
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

        // Add round header
        blockStreamManager.writeItem(BlockItem.newBuilder()
                .roundHeader(RoundHeader.newBuilder().roundNumber(blockNumber).build())
                .build());

        Instant consensusNow = timestamp;
        int txInBlock = 0;

        // PROCESS TRANSACTIONS
        while (txInBlock < maxTransactionsPerBlock) {

            BlockStreamBuilder builder = new BlockStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER);

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

            // BENCHMARK LIMITATION: State changes are simplified for performance testing.
            // In production, HandleWorkflow would provide actual state mutations from
            // transaction execution.
            // This benchmark focuses on block production performance, not transaction
            // execution.
            // The state changes below are minimal but structurally correct for benchmarking
            // purposes.
            List<StateChange> mockStateChanges = new ArrayList<>();
            for (AccountAmount accountAmount : sampleTransferList.accountAmounts()) {
                // Create minimal state change (no hardcoded balances - just structure)
                Account mockAccount = Account.newBuilder()
                        .accountId(accountAmount.accountIDOrThrow())
                        .build();

                StateChange stateChange = StateChange.newBuilder()
                        .stateId(STATE_ID_ACCOUNTS.protoOrdinal())
                        .mapUpdate(MapUpdateChange.newBuilder()
                                .key(MapChangeKey.newBuilder()
                                        .accountIdKey(accountAmount.accountIDOrThrow())
                                        .build())
                                .value(MapChangeValue.newBuilder()
                                        .accountValue(mockAccount)
                                        .build())
                                .identical(false)
                                .build())
                        .build();
                mockStateChanges.add(stateChange);
            }
            builder.stateChanges(mockStateChanges);

            // BENCHMARK LIMITATION: groupStateChanges is null because this benchmark tests
            // individual transactions only, not grouped transactions (atomic batch/hook
            // dispatch).
            // TODO: Add separate benchmark variant for grouped transaction scenarios.
            BlockStreamBuilder.Output output = builder.build(false, null);

            // WRITE ITEMS via REAL BlockStreamManagerImpl.writeItem()
            for (BlockItem item : output.blockItems()) {
                blockStreamManager.writeItem(item);
            }

            txInBlock++;
            txGeneratedThisBlock++;
            totalTransactionsProcessed++;

            // Realistic timestamp spacing based on target TPS (not 1ns increments)
            long nanosBetweenTx = TimeUnit.SECONDS.toNanos(1) / targetTPS;
            consensusNow = consensusNow.plusNanos(nanosBetweenTx);

            // Rate limiting
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

        // SEAL BLOCK (production pattern via wrapper)
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
            double avgItemsPerTx = totalTransactionsProcessed > 0
                    ? (double) totalBlockItemsInBlocks / totalTransactionsProcessed
                    : 0.0;
            double waitTimeSeconds = totalTimeSpentWaiting / 1_000_000_000.0;
            double utilization = ((elapsedSeconds - waitTimeSeconds) / elapsedSeconds) * 100;

            System.out.printf("%n>>> SUMMARY:%n");
            System.out.printf(
                    ">>> Mode: %s%n", enableRateLimiting ? "Rate-limited (target TPS)" : "MAX CAPACITY STRESS TEST");
            System.out.printf(
                    ">>> Target TPS: %d, Actual TPS: %.0f (%.1f%% of target)%n",
                    targetTPS, actualTPS, (actualTPS / targetTPS) * 100);
            System.out.printf(
                    ">>> Transactions: Generated=%d, Processed=%d%n",
                    totalTransactionsGenerated, totalTransactionsProcessed);
            System.out.printf(
                    ">>> BlockItems: In blocks=%d, From manager=%d%n",
                    totalBlockItemsInBlocks, blockStreamManager.getTotalItemsWritten());
            System.out.printf(
                    ">>> Time: Total=%.3fs, Waiting=%.3fs (%.1f%% utilized)%n",
                    elapsedSeconds, waitTimeSeconds, utilization);
            System.out.printf(
                    ">>> Blocks: %d blocks, %.0f tx/block, %.1f items/tx%n",
                    blocksPerRun, avgTxPerBlock, avgItemsPerTx);

            if (enableRateLimiting) {
                if (actualTPS < targetTPS * 0.95) {
                    System.out.printf(
                            "%n⚠️  WARNING: System only achieved %.0f TPS (%.1f%% of target %d TPS)%n",
                            actualTPS, (actualTPS / targetTPS) * 100, targetTPS);
                } else {
                    System.out.printf(
                            "%n✅ System kept up with target TPS (%.1f%% achieved)%n", (actualTPS / targetTPS) * 100);
                }
            } else {
                System.out.printf("%nMAX CAPACITY: System achieved %.0f TPS without rate limiting%n", actualTPS);
                System.out.printf("This is %.1fx the target rate of %d TPS%n", actualTPS / targetTPS, targetTPS);
                System.out.printf("Utilization: %.1f%% (%.3fs total time)%n", utilization, elapsedSeconds);
            }
        }

        System.gc();
    }

    @TearDown(Level.Trial)
    public void teardownTrial() {
        // Stop JFR recording
        if (jfrRecording != null) {
            try {
                jfrRecording.stop();
                jfrRecording.close();
                System.out.println(">>> JFR recording stopped and saved.");
            } catch (Exception e) {
                System.err.println(">>> Failed to stop JFR recording: " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) throws Exception {
        // JFR profiling managed manually in setupTrial/teardownTrial
        // Files saved to: hedera-app/src/jmh/java/com/hedera/node/app/blocks/jfr/
        // Filename format: block-prod-tps<tps>-size<bytes>.jfr
        // Same parameter combinations will overwrite previous runs
        org.openjdk.jmh.Main.main(new String[] {"BlockProductionBenchmark", "-v", "EXTRA" /* , "-prof", "gc" */});
    }
}

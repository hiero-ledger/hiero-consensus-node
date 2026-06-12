// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import static com.hedera.node.app.blocks.impl.streaming.obs.ObsUtils.MATH_CONTEXT_10;
import static java.util.Objects.requireNonNull;

import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Central singleton for enhanced block-streaming observability.
 *
 * <p>Tracks the full lifecycle of every block from initialisation through acknowledgement, as well
 * as per-item buffering and send timing. Data is aggregated and logged at {@code INFO} level every
 * {@value #PERIOD_SECONDS} seconds by an internal scheduled task.
 *
 * <p>All public methods are no-ops when
 * {@link com.hedera.node.config.data.BlockStreamConfig#enhancedObservabilityEnabled()} is
 * {@code false}, so the feature has negligible performance impact when disabled.
 *
 * <p>The feature flag is re-read on every periodic cycle, allowing dynamic enable/disable without
 * restart. Disabling mid-run clears all accumulated data immediately.
 */
@Singleton
public class BlockStreamingObs {

    private static final Logger log = LogManager.getLogger(BlockStreamingObs.class);

    private static final int PERIOD_SECONDS = 60;
    private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final int MAX_THROUGHPUT_BUCKETS = (PERIOD_SECONDS * 2) + 10;
    /**
     * A block that has not been acked this long after its init is considered abandoned (e.g. block node down,
     * streaming disabled, or pruned from the buffer without an ack) and is evicted from tracking so that
     * {@link #blockStatistics} cannot grow without bound. Abandoned blocks are reported in the summary.
     */
    private static final long ABANDONED_AFTER_NANOS = TimeUnit.SECONDS.toNanos(PERIOD_SECONDS * 5L);

    private final ConfigProvider configProvider;
    private volatile boolean isEnabled;
    // ConcurrentMap<BlockNumber, BlockStats>
    private final ConcurrentMap<Long, BlockStats> blockStatistics = new ConcurrentHashMap<>();
    // ConcurrentMap<SecondTick, ThroughputBucket>
    private final ConcurrentMap<Long, ThroughputBucket> throughputBuckets =
            new ConcurrentHashMap<>(MAX_THROUGHPUT_BUCKETS);
    private final long initialNanosTick;
    private final ScheduledExecutorService scheduledExecutorService;

    @Inject
    public BlockStreamingObs(@NonNull final ConfigProvider configProvider) {
        this.configProvider = requireNonNull(configProvider);
        initialNanosTick = System.nanoTime();

        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        scheduledExecutorService.schedule(new ObsGatherAndLogTask(), PERIOD_SECONDS, TimeUnit.SECONDS);

        isEnabled = configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .enhancedObservabilityEnabled();
    }

    /** Called when a block is first created; opens the block stats entry. */
    public void onBlockInit(final long blockNumber, final long nanosTick) {
        if (!isEnabled) {
            return;
        }

        blockStatistics.put(blockNumber, new BlockStats(nanosTick));
    }

    /** Called when a block transitions to the open/buffered state */
    public void onBlockOpen(final long blockNumber, final long nanosTick) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats != null && stats.openedNanosTick.compareAndSet(-1, nanosTick)) {
            getThroughputBucket(nanosTick).blocksOpened.increment();
        }
    }

    /**
     * Called when a block item is added to the buffer. {@code sizeInBytes} is used to compute
     * per-second byte throughput and the per-item size distribution.
     */
    public void onBlockItemAdd(
            final long blockNumber, final int itemIndex, final long nanosTick, final int sizeInBytes) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats != null && stats.items.putIfAbsent(itemIndex, new BlockItemStats(sizeInBytes, nanosTick)) == null) {
            getThroughputBucket(nanosTick).itemsCreated.add(sizeInBytes);
        }
    }

    /**
     * Called when a range of block items [{@code itemIndexStart}, {@code itemIndexEnd}] have been
     * written to the gRPC stream. Records idle time (buffered-to-send) and send latency per item.
     */
    public void onBlockItemsSend(
            final long blockNumber,
            final int itemIndexStart,
            final int itemIndexEnd,
            final long nanosTickStart,
            final long nanosTickEnd) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats == null) {
            return;
        }

        final StartAndEndTicks ticks = new StartAndEndTicks(nanosTickStart, nanosTickEnd);
        final ThroughputBucket throughputBucket = getThroughputBucket(nanosTickStart);

        for (int index = itemIndexStart; index <= itemIndexEnd; ++index) {
            final BlockItemStats itemStats = stats.items.get(index);
            if (itemStats != null && itemStats.itemSendNanosTicks.compareAndSet(null, ticks)) {
                throughputBucket.itemsSent.add(itemStats.itemSizeInBytes);
            }
        }
    }

    /** Records when the {@code BlockEnd} message for a block was written to the gRPC stream. */
    public void onBlockEndSend(final long blockNumber, final long nanosTickStart, final long nanosTickEnd) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats != null) {
            stats.endSentNanosTicks.compareAndSet(null, new StartAndEndTicks(nanosTickStart, nanosTickEnd));
        }
    }

    /** Records when a block transitioned to the closed state (all items written to the buffer). */
    public void onBlockClose(final long blockNumber, final long nanosTick) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats != null && stats.closedNanosTick.compareAndSet(-1, nanosTick)) {
            getThroughputBucket(nanosTick).blocksClosed.increment();
        }
    }

    /**
     * Called when a block is acknowledged by the block node. Per-block aggregation is deliberately
     * NOT done here: it happens only on the gather thread, once the ack is older than the grace
     * period, so the ack thread can never race the gather task inside the probes.
     */
    public void onBlockAcknowledge(final long blockNumber, final long nanosTick) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats != null && stats.ackedNanosTick.compareAndSet(-1, nanosTick)) {
            getThroughputBucket(nanosTick).blocksAcked.increment();
        }
    }

    /**
     * Marks all items for blocks below {@code startingBlockNumber} as {@link StartAndEndTicks#NEVER_SENT}
     * when a new connection opens at that block. Prevents {@link BlockStats#aggregate()} from treating
     * those items as a bug (null send ticks) rather than an expected gap.
     */
    public void onConnectionStartedAt(final long startingBlockNumber) {
        if (!isEnabled) {
            return;
        }

        for (final Map.Entry<Long, BlockStats> entry : blockStatistics.entrySet()) {
            if (entry.getKey() < startingBlockNumber) {
                for (final BlockItemStats itemStats : entry.getValue().items.values()) {
                    itemStats.itemSendNanosTicks.compareAndSet(null, StartAndEndTicks.NEVER_SENT);
                }
            }
        }
    }

    /** Records when the block proof item was created by the consensus layer. */
    public void onBlockProofCreate(final long blockNumber, final long nanosTick) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats != null) {
            stats.proofCreatedNanosTick.compareAndSet(-1, nanosTick);
        }
    }

    /** Records when the block proof item was added to the streaming buffer. */
    public void onBlockProofAdd(final long blockNumber, final long nanosTick) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats != null) {
            stats.proofAddedNanosTick.compareAndSet(-1, nanosTick);
        }
    }

    /** Records when the block header item was written to the gRPC stream. */
    public void onBlockHeaderSend(final long blockNumber, final long nanosTickStart, final long nanosTickEnd) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats != null) {
            stats.headerSentNanosTicks.compareAndSet(null, new StartAndEndTicks(nanosTickStart, nanosTickEnd));
        }
    }

    /** Records when the block footer item was created. */
    public void onBlockFooterCreate(final long blockNumber, final long nanosTick) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats != null) {
            stats.footerNanosTick.compareAndSet(-1, nanosTick);
        }
    }

    private long toSecondTick(final long nanosTick) {
        final long nanos = nanosTick - initialNanosTick;
        return nanos / NANOS_PER_SECOND;
    }

    private ThroughputBucket getThroughputBucket(final long nanosTick) {
        final long second = toSecondTick(nanosTick);
        return throughputBuckets.computeIfAbsent(second, _ -> new ThroughputBucket(second));
    }

    // =================================================================================================================

    /** Periodic task that re-reads the feature flag, then gathers and logs the accumulated stats. */
    private class ObsGatherAndLogTask implements Runnable {
        @Override
        public void run() {
            try {
                isEnabled = configProvider
                        .getConfiguration()
                        .getConfigData(BlockStreamConfig.class)
                        .enhancedObservabilityEnabled();

                gatherAndLogObsData();
            } finally {
                scheduledExecutorService.schedule(new ObsGatherAndLogTask(), PERIOD_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    private void gatherAndLogObsData() {
        if (!isEnabled) {
            // enhanced obs may have been dynamically disabled... clear everything and exit
            blockStatistics.clear();
            throughputBuckets.clear();
            return;
        }

        // calculate the seconds tick that will act as the threshold for what data to process
        // we will only look at data 2 seconds and older; this will give any long-running in-flight operations a chance
        // to submit their observations
        final long nanosTick = System.nanoTime();
        final long thresholdSecondTick = toSecondTick(nanosTick) - 2;
        final long thresholdNanosTick = initialNanosTick + (NANOS_PER_SECOND * thresholdSecondTick);

        // gather the throughput buckets we care about
        final Map<Long, ThroughputBucket> throughputBucketsToProcess = new HashMap<>();
        final Iterator<Map.Entry<Long, ThroughputBucket>> throughputBucketsIt =
                throughputBuckets.entrySet().iterator();

        while (throughputBucketsIt.hasNext()) {
            final Map.Entry<Long, ThroughputBucket> entry = throughputBucketsIt.next();
            final long bucketSecondTick = entry.getKey();
            if (bucketSecondTick <= thresholdSecondTick) {
                throughputBucketsIt.remove();
                throughputBucketsToProcess.put(entry.getKey(), entry.getValue());
            }
        }

        // gather the blocks we care about
        final BlockStatsAggregation blocksAggregation = new BlockStatsAggregation();
        long blocksAbandoned = 0;
        final Iterator<Map.Entry<Long, BlockStats>> blockStatisticsIt =
                blockStatistics.entrySet().iterator();

        while (blockStatisticsIt.hasNext()) {
            final Map.Entry<Long, BlockStats> entry = blockStatisticsIt.next();
            final BlockStats blockStats = entry.getValue();
            // only blocks acked before the threshold are eligible; the ack is terminal, so by now
            // no other thread is still recording events for the block and it is safe to aggregate
            final long ackedNanosTick = blockStats.ackedNanosTick.get();
            if (ackedNanosTick != -1 && ackedNanosTick <= thresholdNanosTick) {
                blockStatisticsIt.remove();
                blocksAggregation.add(blockStats);
            } else if (ackedNanosTick == -1 && nanosTick - blockStats.initNanosTick >= ABANDONED_AFTER_NANOS) {
                // never acked and too old: evict without aggregating (other threads may still be
                // mutating the block, so its probes must not be touched) and only count it
                blockStatisticsIt.remove();
                ++blocksAbandoned;
            }
        }

        blocksAggregation.complete();

        // now process all the collected data
        long earliestSecondTick = Long.MAX_VALUE;
        long latestSecondTick = Long.MIN_VALUE;
        long totalBlocksOpened = 0;
        long totalBlocksClosed = 0;
        long totalBlocksAcked = 0;
        final SimpleAggregator totalItemsCreated = new SimpleAggregator();
        final SimpleAggregator totalItemsSent = new SimpleAggregator();

        for (final ThroughputBucket throughputBucket : throughputBucketsToProcess.values()) {
            if (earliestSecondTick > throughputBucket.secondTick) {
                earliestSecondTick = throughputBucket.secondTick;
            }
            if (latestSecondTick < throughputBucket.secondTick) {
                latestSecondTick = throughputBucket.secondTick;
            }

            totalBlocksOpened += throughputBucket.blocksOpened.sum();
            totalBlocksClosed += throughputBucket.blocksClosed.sum();
            totalBlocksAcked += throughputBucket.blocksAcked.sum();

            final Statistics itemsCreatedStats = throughputBucket.itemsCreated.aggregate();
            final Statistics itemsSentStats = throughputBucket.itemsSent.aggregate();

            totalItemsCreated.add(itemsCreatedStats.numSamples(), itemsCreatedStats.sum());
            totalItemsSent.add(itemsSentStats.numSamples(), itemsSentStats.sum());
        }

        long numberOfSeconds = latestSecondTick - earliestSecondTick;
        if (numberOfSeconds == 0) {
            // if there was just one bucket, the latest and earliest second ticks are the same so record the number
            // of seconds as being 1
            numberOfSeconds = 1;
        }
        final BigDecimal seconds = BigDecimal.valueOf(numberOfSeconds);

        // calculate per-second throughput data
        final BigDecimal itemsCreatedPerSecondCount =
                new BigDecimal(totalItemsCreated.numSamples).divide(seconds, MATH_CONTEXT_10);
        final BigDecimal itemsCreatedPerSecondBytes =
                new BigDecimal(totalItemsCreated.sum).divide(seconds, MATH_CONTEXT_10);
        final BigDecimal itemsSentPerSecondCount =
                new BigDecimal(totalItemsSent.numSamples).divide(seconds, MATH_CONTEXT_10);
        final BigDecimal itemsSentPerSecondBytes = new BigDecimal(totalItemsSent.sum).divide(seconds, MATH_CONTEXT_10);

        // spotless:off
        // create the log output
        final StringBuilder output = new StringBuilder("\nBlockStreamingStats {\n");

        output.append("  Summary {\n");
        output.append("    Seconds { (Unit:COUNT|Sum:").append(numberOfSeconds).append(") }\n");
        output.append("    Blocks {\n");
        output.append("      Opened { (Unit:COUNT|Sum:").append(totalBlocksOpened).append(") }\n");
        output.append("      Closed { (Unit:COUNT|Sum:").append(totalBlocksClosed).append(") }\n");
        output.append("      Acknowledged { (Unit:COUNT|Sum:").append(totalBlocksAcked).append(") }\n");
        output.append("      Abandoned { (Unit:COUNT|Sum:").append(blocksAbandoned).append(") }\n");
        output.append("    }\n");
        output.append("    Items {\n");
        output.append("      Created-Total { (Unit:COUNT|Sum:").append(totalItemsCreated.numSamples).append(")");
        output.append("(Unit:BYTES|Sum:").append(totalItemsCreated.sum).append(") }\n");
        output.append("      Created-PerSecond { (Unit:COUNT|Avg:").append(toString(itemsCreatedPerSecondCount)).append(")");
        output.append("(Unit:BYTES|Avg:").append(toString(itemsCreatedPerSecondBytes)).append(") }\n");
        output.append("      Sent-Total { (Unit:COUNT|Sum:").append(totalItemsSent.numSamples).append(")");
        output.append("(Unit:BYTES|Sum:").append(totalItemsSent.sum).append(") }\n");
        output.append("      Sent-PerSecond { (Unit:COUNT|Avg:").append(toString(itemsSentPerSecondCount)).append(")");
        output.append("(Unit:BYTES|Avg:").append(toString(itemsSentPerSecondBytes)).append(") }\n");
        output.append("    }\n");
        output.append("  }\n");

        // append block details
        output.append("  BlockDetails {\n");
        output.append("    ").append(blocksAggregation.initToOpen).append("\n");
        output.append("    ").append(blocksAggregation.openToClose).append("\n");
        output.append("    ").append(blocksAggregation.openToEndSent).append("\n");
        output.append("    ").append(blocksAggregation.openToAck).append("\n");
        output.append("    ").append(blocksAggregation.closedToAck).append("\n");
        output.append("    ").append(blocksAggregation.headerSendStartedToAck).append("\n");
        output.append("    ").append(blocksAggregation.headerSentToAck).append("\n");
        output.append("    ").append(blocksAggregation.endSentToAck).append("\n");
        output.append("    ").append(blocksAggregation.headerSentToEndSent).append("\n");
        output.append("    ").append(blocksAggregation.openToProofAdded).append("\n");
        output.append("    ").append(blocksAggregation.openToProofCreated).append("\n");
        output.append("    ").append(blocksAggregation.footerCreatedToProofCreated).append("\n");
        output.append("    ").append(blocksAggregation.blockSize).append("\n");
        output.append("    ").append(blocksAggregation.itemsPerBlock).append("\n");
        output.append("  }\n");

        // append item details
        output.append("  ItemDetails {\n");
        output.append("    ItemIdle ").append(Statistics.toString(blocksAggregation.itemIdleComposite)).append("\n");
        output.append("    ItemSendLatency ").append(Statistics.toString(blocksAggregation.itemSendLatencyComposite)).append("\n");
        output.append("    ItemSize ").append(Statistics.toString(blocksAggregation.itemSizeComposite)).append("\n");
        output.append("  }\n");

        output.append("}");
        // spotless:on

        log.info("{}", output);
    }

    private String toString(final BigDecimal bd) {
        return bd.setScale(4, RoundingMode.HALF_EVEN).toPlainString();
    }

    // =================================================================================================================

    /** Accumulates a running count and byte-sum across multiple {@link Statistics} contributions. */
    private static class SimpleAggregator {
        private BigInteger numSamples = BigInteger.ZERO;
        private BigInteger sum = BigInteger.ZERO;

        void add(final BigInteger numSamples, final BigInteger sum) {
            this.numSamples = this.numSamples.add(numSamples);
            this.sum = this.sum.add(sum);
        }
    }

    /** Aggregates per-block latency and per-item statistics across all blocks in a reporting window. */
    private static class BlockStatsAggregation {
        private final StatisticsProbe initToOpen = new StatisticsProbe("InitToOpen", ObsUnit.NANOS);
        private final StatisticsProbe openToClose = new StatisticsProbe("OpenToClose", ObsUnit.NANOS);
        private final StatisticsProbe openToEndSent = new StatisticsProbe("OpenToEndSent", ObsUnit.NANOS);
        private final StatisticsProbe openToAck = new StatisticsProbe("OpenToAck", ObsUnit.NANOS);
        private final StatisticsProbe closedToAck = new StatisticsProbe("ClosedToAck", ObsUnit.NANOS);
        private final StatisticsProbe headerSendStartedToAck =
                new StatisticsProbe("HeaderSendStartedToAck", ObsUnit.NANOS);
        private final StatisticsProbe headerSentToAck = new StatisticsProbe("HeaderSentToAck", ObsUnit.NANOS);
        private final StatisticsProbe endSentToAck = new StatisticsProbe("EndSentToAck", ObsUnit.NANOS);
        private final StatisticsProbe headerSentToEndSent = new StatisticsProbe("HeaderSentToEndSent", ObsUnit.NANOS);
        private final StatisticsProbe openToProofAdded = new StatisticsProbe("OpenToProofAdded", ObsUnit.NANOS);
        private final StatisticsProbe openToProofCreated = new StatisticsProbe("OpenToProofCreated", ObsUnit.NANOS);
        private final StatisticsProbe footerCreatedToProofCreated =
                new StatisticsProbe("FooterCreatedToProofCreated", ObsUnit.NANOS);

        private final StatisticsProbe blockSize = new StatisticsProbe("BlockSize", ObsUnit.BYTES);
        private final StatisticsProbe itemsPerBlock = new StatisticsProbe("ItemsPerBlock", ObsUnit.COUNT);

        private final CompositeStatistics itemIdleComposite = new CompositeStatistics(ObsUnit.NANOS);
        private final CompositeStatistics itemSendLatencyComposite = new CompositeStatistics(ObsUnit.NANOS);
        private final CompositeStatistics itemSizeComposite = new CompositeStatistics(ObsUnit.BYTES);

        void add(final BlockStats blockStats) {
            // computes the per-item probes and blockSize; must only ever run on the gather thread
            blockStats.aggregate();

            initToOpen.add(blockStats.initToOpen());
            openToClose.add(blockStats.openToClose());
            openToAck.add(blockStats.openToAck());
            closedToAck.add(blockStats.closedToAck());
            openToProofAdded.add(blockStats.openToProofAdded());
            openToProofCreated.add(blockStats.openToProofCreated());
            footerCreatedToProofCreated.add(blockStats.footerCreatedToProofCreated());

            final StartAndEndTicks endTicks = blockStats.endSentNanosTicks.get();
            final StartAndEndTicks headerTicks = blockStats.headerSentNanosTicks.get();
            if (endTicks != null) {
                openToEndSent.add(blockStats.openToEndSent());
                endSentToAck.add(blockStats.endSentToAck());
            }
            if (headerTicks != null) {
                headerSendStartedToAck.add(blockStats.headerSendStartedToAck());
                headerSentToAck.add(blockStats.headerSentToAck());
                if (endTicks != null) {
                    headerSentToEndSent.add(blockStats.headerSentToEndSent());
                }
            }

            itemIdleComposite.add(blockStats.itemIdleProbe.aggregate());
            itemSendLatencyComposite.add(blockStats.itemSendLatencyProbe.aggregate());
            itemSizeComposite.add(blockStats.itemSizeProbe.aggregate());

            blockSize.add(blockStats.blockSize);
            itemsPerBlock.add(blockStats.items.size());
        }

        void complete() {
            initToOpen.aggregate();
            openToClose.aggregate();
            openToEndSent.aggregate();
            openToAck.aggregate();
            closedToAck.aggregate();
            headerSendStartedToAck.aggregate();
            headerSentToAck.aggregate();
            endSentToAck.aggregate();
            headerSentToEndSent.aggregate();
            openToProofAdded.aggregate();
            openToProofCreated.aggregate();
            footerCreatedToProofCreated.aggregate();
            blockSize.aggregate();
            itemsPerBlock.aggregate();
        }
    }

    /** Counts block and item events that occurred within a single wall-clock second. */
    private static class ThroughputBucket {
        private final long secondTick;
        private final BasicProbe itemsCreated = new BasicProbe("ItemsCreated", ObsUnit.COUNT);
        private final BasicProbe itemsSent = new BasicProbe("ItemsSent", ObsUnit.COUNT);
        private final LongAdder blocksOpened = new LongAdder();
        private final LongAdder blocksClosed = new LongAdder();
        private final LongAdder blocksAcked = new LongAdder();

        ThroughputBucket(final long secondTick) {
            this.secondTick = secondTick;
        }
    }

    /**
     * Holds all lifecycle timestamps for a single block.
     * AtomicLong fields use {@code -1} as a sentinel meaning "not yet recorded".
     * AtomicReference fields use {@code null} as sentinel for the same purpose.
     */
    private static class BlockStats {
        private final long initNanosTick;
        private final AtomicLong openedNanosTick = new AtomicLong(-1);
        private final AtomicLong closedNanosTick = new AtomicLong(-1);
        private final AtomicLong ackedNanosTick = new AtomicLong(-1);
        private final AtomicLong proofCreatedNanosTick = new AtomicLong(-1);
        private final AtomicLong proofAddedNanosTick = new AtomicLong(-1);
        private final AtomicLong footerNanosTick = new AtomicLong(-1);
        private final AtomicReference<StartAndEndTicks> headerSentNanosTicks = new AtomicReference<>();
        private final AtomicReference<StartAndEndTicks> endSentNanosTicks = new AtomicReference<>();
        private final ConcurrentMap<Integer, BlockItemStats> items = new ConcurrentHashMap<>();

        private final StatisticsProbe itemIdleProbe = new StatisticsProbe("ItemIdle", ObsUnit.NANOS);
        private final StatisticsProbe itemSendLatencyProbe = new StatisticsProbe("ItemSendLatency", ObsUnit.NANOS);
        private final StatisticsProbe itemSizeProbe = new StatisticsProbe("ItemSize", ObsUnit.BYTES);

        private long blockSize = 0;

        public BlockStats(final long initNanosTick) {
            this.initNanosTick = initNanosTick;
        }

        void aggregate() {
            for (final Map.Entry<Integer, BlockItemStats> itemStatsEntry : items.entrySet()) {
                final BlockItemStats itemStats = itemStatsEntry.getValue();
                final StartAndEndTicks sendTicks = itemStats.itemSendNanosTicks.get();
                if (sendTicks == null) {
                    // safety net: send time was not recorded (unexpected)
                    continue;
                }
                if (sendTicks == StartAndEndTicks.NEVER_SENT) {
                    // item was in buffer when connection started at a later block; size still counts
                    itemSizeProbe.add(itemStats.itemSizeInBytes);
                    blockSize += itemStats.itemSizeInBytes;
                    continue;
                }
                itemIdleProbe.add(sendTicks.start - itemStats.itemBufferedNanosTick);
                itemSendLatencyProbe.add(sendTicks.diff());
                itemSizeProbe.add(itemStats.itemSizeInBytes);
                blockSize += itemStats.itemSizeInBytes;
            }

            itemIdleProbe.aggregate();
            itemSendLatencyProbe.aggregate();
            itemSizeProbe.aggregate();
        }

        long initToOpen() {
            return openedNanosTick.get() - initNanosTick;
        }

        long openToClose() {
            return closedNanosTick.get() - openedNanosTick.get();
        }

        long openToEndSent() {
            return endSentNanosTicks.get().end() - openedNanosTick.get();
        }

        long openToAck() {
            return ackedNanosTick.get() - openedNanosTick.get();
        }

        long closedToAck() {
            return ackedNanosTick.get() - closedNanosTick.get();
        }

        long headerSendStartedToAck() {
            return ackedNanosTick.get() - headerSentNanosTicks.get().start();
        }

        long headerSentToAck() {
            return ackedNanosTick.get() - headerSentNanosTicks.get().end();
        }

        long endSentToAck() {
            return ackedNanosTick.get() - endSentNanosTicks.get().end();
        }

        long headerSentToEndSent() {
            return endSentNanosTicks.get().end() - headerSentNanosTicks.get().end();
        }

        long openToProofAdded() {
            return proofAddedNanosTick.get() - openedNanosTick.get();
        }

        long openToProofCreated() {
            return proofCreatedNanosTick.get() - openedNanosTick.get();
        }

        long footerCreatedToProofCreated() {
            return proofCreatedNanosTick.get() - footerNanosTick.get();
        }
    }

    /** Holds the buffered timestamp and send timing for a single block item. */
    private static class BlockItemStats {
        private final long itemSizeInBytes;
        private final long itemBufferedNanosTick;
        private final AtomicReference<StartAndEndTicks> itemSendNanosTicks = new AtomicReference<>();

        public BlockItemStats(final long itemSizeInBytes, final long itemBufferedNanosTick) {
            this.itemSizeInBytes = itemSizeInBytes;
            this.itemBufferedNanosTick = itemBufferedNanosTick;
        }
    }

    /** A pair of {@code System.nanoTime()} values bracketing the start and end of an operation. */
    private record StartAndEndTicks(long start, long end) {
        /** Sentinel indicating the item was in the buffer when the connection started at a later block. */
        static final StartAndEndTicks NEVER_SENT = new StartAndEndTicks(-1L, -1L);

        long diff() {
            return end - start;
        }
    }
}

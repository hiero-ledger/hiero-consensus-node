// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import static com.hedera.node.app.blocks.impl.streaming.obs.ObsUtils.round;
import static java.util.Objects.requireNonNull;

import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigDecimal;
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

@Singleton
public class BlockStreamingObs {

    private static final Logger log = LogManager.getLogger(BlockStreamingObs.class);

    private static final int PERIOD_SECONDS = 60;
    private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final int MAX_THROUGHPUT_BUCKETS = (PERIOD_SECONDS * 2) + 10;

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

    public void onBlockInit(final long blockNumber, final long nanosTick) {
        if (!isEnabled) {
            return;
        }

        blockStatistics.put(blockNumber, new BlockStats(blockNumber, nanosTick));
    }

    public void onBlockOpen(final long blockNumber, final long nanosTick) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats != null && stats.openedNanosTick.compareAndSet(-1, nanosTick)) {
            getThroughputBucket(nanosTick).blocksOpened.increment();
        }
    }

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

    public void onBlockEndSend(final long blockNumber, final long nanosTickStart, final long nanosTickEnd) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats != null) {
            stats.endSentNanosTicks.compareAndSet(null, new StartAndEndTicks(nanosTickStart, nanosTickEnd));
        }
    }

    public void onBlockClose(final long blockNumber, final long nanosTick) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats != null && stats.closedNanosTick.compareAndSet(-1, nanosTick)) {
            getThroughputBucket(nanosTick).blocksClosed.increment();
        }
    }

    public void onBlockAcknowledge(final long blockNumber, final long nanosTick) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats != null && stats.ackedNanosTick.compareAndSet(-1, nanosTick)) {
            getThroughputBucket(nanosTick).blocksAcked.increment();
            stats.aggregate();
        }
    }

    public void onBlockProofCreate(final long blockNumber, final long nanosTick) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats != null) {
            stats.proofCreatedNanosTick.compareAndSet(-1, nanosTick);
        }
    }

    public void onBlockProofAdd(final long blockNumber, final long nanosTick) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats != null) {
            stats.proofAddedNanosTick.compareAndSet(-1, nanosTick);
        }
    }

    public void onBlockHeaderSend(final long blockNumber, final long nanosTickStart, final long nanosTickEnd) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats != null) {
            stats.headerSentNanosTicks.compareAndSet(null, new StartAndEndTicks(nanosTickStart, nanosTickEnd));
        }
    }

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
        final Iterator<Map.Entry<Long, BlockStats>> blockStatisticsIt =
                blockStatistics.entrySet().iterator();

        while (blockStatisticsIt.hasNext()) {
            final Map.Entry<Long, BlockStats> entry = blockStatisticsIt.next();
            final BlockStats blockStats = entry.getValue();
            if (blockStats.isAcked() && blockStats.initNanosTick <= thresholdNanosTick) {
                blockStatisticsIt.remove();
                blocksAggregation.add(blockStats);
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

            totalItemsCreated.add(itemsCreatedStats.samples(), itemsCreatedStats.sum());
            totalItemsSent.add(itemsSentStats.samples(), itemsSentStats.sum());
        }

        long numberOfSeconds = latestSecondTick - earliestSecondTick;
        if (numberOfSeconds == 0) {
            // if there was just one bucket, the latest and earliest second ticks are the same so record the number
            // of seconds as being 1
            numberOfSeconds = 1;
        }

        // calculate per-second throughput data
        final BigDecimal itemsCreatedPerSecondCount = round(totalItemsCreated.samples / (numberOfSeconds * 1.0D));
        final BigDecimal itemsCreatedPerSecondBytes = round(totalItemsCreated.sum / (numberOfSeconds * 1.0D));
        final BigDecimal itemsSentPerSecondCount = round(totalItemsSent.samples / (numberOfSeconds * 1.0D));
        final BigDecimal itemsSentPerSecondBytes = round(totalItemsSent.sum / (numberOfSeconds * 1.0D));

        // spotless:off
        // create the log output
        final StringBuilder output = new StringBuilder("\nBlockStreamingStats {\n");

        output.append("  Summary {\n");
        output.append("    Seconds { (Unit:COUNT|Sum:").append(numberOfSeconds).append(") }\n");
        output.append("    Blocks {\n");
        output.append("      Opened { (Unit:COUNT|Sum:").append(totalBlocksOpened).append(") }\n");
        output.append("      Closed { (Unit:COUNT|Sum:").append(totalBlocksClosed).append(") }\n");
        output.append("      Acknowledged { (Unit:COUNT|Sum:").append(totalBlocksAcked).append(") }\n");
        output.append("    }\n");
        output.append("    Items {\n");
        output.append("      Created-Total { (Unit:COUNT|Sum:").append(totalItemsCreated.samples).append(")");
        output.append("(Unit:BYTES|Sum:").append(totalItemsCreated.sum).append(") }\n");
        output.append("      Created-PerSecond { (Unit:COUNT|Avg:").append(itemsCreatedPerSecondCount.toPlainString()).append(")");
        output.append("(Unit:BYTES|Avg:").append(itemsCreatedPerSecondBytes.toPlainString()).append(") }\n");
        output.append("      Sent-Total { (Unit:COUNT|Sum:").append(totalItemsSent.samples).append(")");
        output.append("(Unit:BYTES|Sum:").append(totalItemsSent.sum).append(") }\n");
        output.append("      Sent-PerSecond { (Unit:COUNT|Avg:").append(itemsSentPerSecondCount.toPlainString()).append(")");
        output.append("(Unit:BYTES|Avg:").append(itemsSentPerSecondBytes.toPlainString()).append(") }\n");
        output.append("    }\n");
        output.append("  }\n");

        // append block details
        output.append("  BlockDetails {\n");
        output.append("    ").append(blocksAggregation.initToOpen).append("\n");
        output.append("    ").append(blocksAggregation.openToClose).append("\n");
        output.append("    ").append(blocksAggregation.openToEndSent).append("\n");
        output.append("    ").append(blocksAggregation.openToAck).append("\n");
        output.append("    ").append(blocksAggregation.closedToAck).append("\n");
        output.append("    ").append(blocksAggregation.headerProducedToAck).append("\n");
        output.append("    ").append(blocksAggregation.headerSentToAck).append("\n");
        output.append("    ").append(blocksAggregation.endSentToAck).append("\n");
        output.append("    ").append(blocksAggregation.headerSentToEndSent).append("\n");
        output.append("    ").append(blocksAggregation.openToProofAdded).append("\n");
        output.append("    ").append(blocksAggregation.openToProofCreated).append("\n");
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

    // =================================================================================================================

    private static class SimpleAggregator {
        private long samples = 0;
        private long sum = 0;

        void add(final long samples, final long sum) {
            this.samples += samples;
            this.sum += sum;
        }
    }

    private static class BlockStatsAggregation {
        private final StatisticsProbe initToOpen = new StatisticsProbe("InitToOpen", ObsUnit.NANOS);
        private final StatisticsProbe openToClose = new StatisticsProbe("OpenToClose", ObsUnit.NANOS);
        private final StatisticsProbe openToEndSent = new StatisticsProbe("OpenToEndSent", ObsUnit.NANOS);
        private final StatisticsProbe openToAck = new StatisticsProbe("OpenToAck", ObsUnit.NANOS);
        private final StatisticsProbe closedToAck = new StatisticsProbe("ClosedToAck", ObsUnit.NANOS);
        private final StatisticsProbe headerProducedToAck = new StatisticsProbe("HeaderProducedToAck", ObsUnit.NANOS);
        private final StatisticsProbe headerSentToAck = new StatisticsProbe("HeaderSentToAck", ObsUnit.NANOS);
        private final StatisticsProbe endSentToAck = new StatisticsProbe("EndSentToAck", ObsUnit.NANOS);
        private final StatisticsProbe headerSentToEndSent = new StatisticsProbe("HeaderSentToEndSent", ObsUnit.NANOS);
        private final StatisticsProbe openToProofAdded = new StatisticsProbe("OpenToProofAdded", ObsUnit.NANOS);
        private final StatisticsProbe openToProofCreated = new StatisticsProbe("OpenToProofCreated", ObsUnit.NANOS);

        private final StatisticsProbe blockSize = new StatisticsProbe("BlockSize", ObsUnit.BYTES);
        private final StatisticsProbe itemsPerBlock = new StatisticsProbe("ItemsPerBlock", ObsUnit.COUNT);

        private final CompositeStatistics itemIdleComposite = new CompositeStatistics(ObsUnit.NANOS);
        private final CompositeStatistics itemSendLatencyComposite = new CompositeStatistics(ObsUnit.NANOS);
        private final CompositeStatistics itemSizeComposite = new CompositeStatistics(ObsUnit.BYTES);

        void add(final BlockStats blockStats) {
            initToOpen.add(blockStats.initToOpen());
            openToClose.add(blockStats.openToClose());
            openToEndSent.add(blockStats.openToEndSent());
            openToAck.add(blockStats.openToAck());
            closedToAck.add(blockStats.closedToAck());
            headerProducedToAck.add(blockStats.headerProducedToAck());
            headerSentToAck.add(blockStats.headerSentToAck());
            endSentToAck.add(blockStats.endSentToAck());
            headerSentToEndSent.add(blockStats.headerSentToEndSent());
            openToProofAdded.add(blockStats.openToProofAdded());
            openToProofCreated.add(blockStats.openToProofCreated());

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
            headerProducedToAck.aggregate();
            headerSentToAck.aggregate();
            endSentToAck.aggregate();
            headerSentToEndSent.aggregate();
            openToProofAdded.aggregate();
            openToProofCreated.aggregate();
            itemsPerBlock.aggregate();
        }
    }

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

    private static class BlockStats {
        private final long blockNumber;
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

        public BlockStats(final long blockNumber, final long initNanosTick) {
            this.blockNumber = blockNumber;
            this.initNanosTick = initNanosTick;
        }

        void aggregate() {
            for (final Map.Entry<Integer, BlockItemStats> itemStatsEntry : items.entrySet()) {
                final BlockItemStats itemStats = itemStatsEntry.getValue();
                itemIdleProbe.add(itemStats.itemSendNanosTicks.get().start - itemStats.itemBufferedNanosTick);
                itemSendLatencyProbe.add(itemStats.itemSendNanosTicks.get().diff());
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

        long headerProducedToAck() {
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

        boolean isAcked() {
            return ackedNanosTick.get() != -1;
        }
    }

    private static class BlockItemStats {
        private final long itemSizeInBytes;
        private final long itemBufferedNanosTick;
        private final AtomicReference<StartAndEndTicks> itemSendNanosTicks = new AtomicReference<>();

        public BlockItemStats(final long itemSizeInBytes, final long itemBufferedNanosTick) {
            this.itemSizeInBytes = itemSizeInBytes;
            this.itemBufferedNanosTick = itemBufferedNanosTick;
        }
    }

    private record StartAndEndTicks(long start, long end) {
        long diff() {
            return end - start;
        }
    }
}

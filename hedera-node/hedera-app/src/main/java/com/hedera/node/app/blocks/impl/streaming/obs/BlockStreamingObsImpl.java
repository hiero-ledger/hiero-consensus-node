package com.hedera.node.app.blocks.impl.streaming.obs;

import static java.util.Objects.requireNonNull;

import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
public class BlockStreamingObsImpl implements BlockStreamingObs {
    private static final Logger log = LogManager.getLogger(BlockStreamingObsImpl.class);

    private static final int INTERVAL_SECONDS = 60;
    private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final int MAX_BUCKETS = (INTERVAL_SECONDS * 2) + 10;

    private volatile boolean enhancedObservabilityEnabled;
    private final ConfigProvider configProvider;
    // Map<BlockNumber, BlockStats>
    private final ConcurrentMap<Long, BlockStats> blockStats = new ConcurrentHashMap<>();
    // Map<SecondTick, ThroughputBucket>
    private final ConcurrentMap<Long, ThroughputBucket> throughputBuckets = new ConcurrentHashMap<>(MAX_BUCKETS);
    private final long initialNanosTick;

    private final ScheduledExecutorService executorService;

    @Inject
    public BlockStreamingObsImpl(@NonNull final ConfigProvider configProvider) {
        this.configProvider = requireNonNull(configProvider);
        initialNanosTick = System.nanoTime();

        executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.schedule(new StatsTask(), INTERVAL_SECONDS, TimeUnit.SECONDS);
        enhancedObservabilityEnabled = configProvider.getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .enhancedObservabilityEnabled();
    }

    private class StatsTask implements Runnable {
        @Override
        public void run() {
            try {
                enhancedObservabilityEnabled = configProvider.getConfiguration()
                        .getConfigData(BlockStreamConfig.class)
                        .enhancedObservabilityEnabled();

                logStatistics();
            } finally {
                executorService.schedule(new StatsTask(), INTERVAL_SECONDS, TimeUnit.SECONDS);
            }
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

    @Override
    public void onBlockInit(final long blockNumber, final long nanosTick) {
        if (!enhancedObservabilityEnabled) {
            return;
        }

        blockStats.put(blockNumber, new BlockStats(blockNumber, nanosTick));
    }

    @Override
    public void onBlockOpen(final long blockNumber, final long nanosTick) {
        if (!enhancedObservabilityEnabled) {
            return;
        }

        final BlockStats stats = blockStats.get(blockNumber);
        if (stats != null && stats.openedNanosTick.compareAndSet(-1, nanosTick)) {
            getThroughputBucket(nanosTick).blocksOpened.increment();
        }
    }

    @Override
    public void onBlockItemAdd(final long blockNumber, final int itemIndex, final long nanosTick, final int sizeInBytes) {
        if (!enhancedObservabilityEnabled) {
            return;
        }

        final BlockStats stats = blockStats.get(blockNumber);
        if (stats == null) {
            return;
        }

        if (stats.items.putIfAbsent(itemIndex, new BlockItemStats(sizeInBytes, nanosTick)) == null) {
            getThroughputBucket(nanosTick).itemsCreated.add(sizeInBytes);
        }
    }

    @Override
    public void onBlockItemSend(final long blockNumber, final int itemIndexStart, final int itemIndexEnd, final long startNanosTick, final long endNanosTick) {
        if (!enhancedObservabilityEnabled) {
            return;
        }

        final BlockStats stats = blockStats.get(blockNumber);
        if (stats == null) {
            return;
        }

        final StartAndEndTicks ticks = new StartAndEndTicks(startNanosTick, endNanosTick);
        final ThroughputBucket throughputBucket = getThroughputBucket(startNanosTick);

        for (int index = itemIndexStart; index <= itemIndexEnd; ++index) {
            final BlockItemStats itemStats = stats.items.get(index);
            if (itemStats == null) {
                log.warn("Untracked item found (itemIndex: {}, blockNumber: {}, indexStart: {}, indexEnd: {})",
                        index, blockNumber, itemIndexStart, itemIndexEnd);
                continue;
            }
            if (itemStats.itemSendNanosTicks.compareAndSet(null, ticks)) {
                throughputBucket.itemsSent.add(itemStats.itemSizeInBytes);
            }
        }
    }

    @Override
    public void onBlockEndSend(final long blockNumber, final long startNanosTick, final long endNanosTick) {
        if (!enhancedObservabilityEnabled) {
            return;
        }

        final BlockStats stats = blockStats.get(blockNumber);
        if (stats == null) {
            return;
        }

        stats.endSentNanosTicks.compareAndSet(null, new StartAndEndTicks(startNanosTick, endNanosTick));
    }

    @Override
    public void onBlockClose(final long blockNumber, final long nanosTick) {
        if (!enhancedObservabilityEnabled) {
            return;
        }

        final BlockStats stats = blockStats.get(blockNumber);
        if (stats != null) {
            if (stats.closedNanosTick.compareAndSet(-1, nanosTick)) {
                getThroughputBucket(nanosTick).blocksClosed.increment();
            }
        }
    }

    @Override
    public void onBlockAcked(final long blockNumber, final long nanosTick) {
        if (!enhancedObservabilityEnabled) {
            return;
        }

        final BlockStats stats = blockStats.get(blockNumber);
        if (stats == null) {
            return;
        }

        if (!stats.ackedNanosTick.compareAndSet(-1, nanosTick)) {
            // the block has already been acked
            return;
        }

        getThroughputBucket(nanosTick).blocksAcked.increment();
        stats.aggregate();
    }

    @Override
    public void onBlockProofCreate(final long blockNumber, final long nanosTick) {
        if (!enhancedObservabilityEnabled) {
            return;
        }

        final BlockStats stats = blockStats.get(blockNumber);
        if (stats != null) {
            stats.proofCreatedNanosTick.compareAndSet(-1, nanosTick);
        }
    }

    @Override
    public void onBlockProofAdd(final long blockNumber, final long nanosTick) {
        if (!enhancedObservabilityEnabled) {
            return;
        }

        final BlockStats stats = blockStats.get(blockNumber);
        if (stats != null) {
            stats.proofAddedNanosTick.compareAndSet(-1, nanosTick);
        }
    }

    @Override
    public void onBlockHeaderSend(final long blockNumber, final long startNanosTick, final long endNanosTick) {
        if (!enhancedObservabilityEnabled) {
            return;
        }

        final BlockStats stats = blockStats.get(blockNumber);
        if (stats != null) {
            stats.headerSentNanosTicks.compareAndSet(null, new StartAndEndTicks(startNanosTick, endNanosTick));
        }
    }

    @Override
    public void onBlockFooterCreate(final long blockNumber, final long nanosTick) {
        if (!enhancedObservabilityEnabled) {
            return;
        }

        final BlockStats stats = blockStats.get(blockNumber);
        if (stats != null) {
            stats.footerNanosTick.compareAndSet(-1, nanosTick);
        }
    }

    private void logStatistics() {
        if (!enhancedObservabilityEnabled) {
            // clear everything and exit
            blockStats.clear();
            throughputBuckets.clear();
            return;
        }

        // determine the seconds tick
        final long nanosTick = System.nanoTime();
        final long thresholdSecondTick = toSecondTick(nanosTick) - 2; // skip the most recent 2 seconds since they may still be in flight
        final long thresholdNanosTick = (NANOS_PER_SECOND * thresholdSecondTick) + initialNanosTick;

        // collect all stats older than the calculated second tick
        final Map<Long, ThroughputBucket> localThroughputBuckets = new HashMap<>();
        final Iterator<Map.Entry<Long, ThroughputBucket>> throughputBucketsIt = throughputBuckets.entrySet().iterator();

        while (throughputBucketsIt.hasNext()) {
            final Map.Entry<Long, ThroughputBucket> entry = throughputBucketsIt.next();
            if (entry.getKey() <= thresholdSecondTick) {
                throughputBucketsIt.remove();
                localThroughputBuckets.put(entry.getKey(), entry.getValue());
            }
        }

        final BlockStatsAggregation blockAggregation = new BlockStatsAggregation();
        final Iterator<Map.Entry<Long, BlockStats>> blockStatsIt = blockStats.entrySet().iterator();

        while (blockStatsIt.hasNext()) {
            final Map.Entry<Long, BlockStats> entry = blockStatsIt.next();
            final BlockStats stats = entry.getValue();
            if (stats.isAcked() && stats.initNanosTick <= thresholdNanosTick) {
                blockStatsIt.remove();
                blockAggregation.add(stats);
            }
        }

        // append throughput data to output
        long earliestSecondTick = Long.MAX_VALUE;
        long latestSecondTick = Long.MIN_VALUE;
        final SummingCounter totalItemsCreated = new SummingCounter();
        final SummingCounter totalItemsSent = new SummingCounter();
        long blocksOpened = 0;
        long blocksClosed = 0;
        long blocksAcked = 0;

        for (final ThroughputBucket bucket : localThroughputBuckets.values()) {
            if (earliestSecondTick > bucket.secondTick) {
                earliestSecondTick = bucket.secondTick;
            }
            if (latestSecondTick < bucket.secondTick) {
                latestSecondTick = bucket.secondTick;
            }

            totalItemsCreated.add(bucket.itemsCreated.count.sum(), bucket.itemsCreated.sum.sum());
            totalItemsSent.add(bucket.itemsSent.count.sum(), bucket.itemsSent.sum.sum());
            blocksOpened += bucket.blocksOpened.sum();
            blocksClosed += bucket.blocksClosed.sum();
            blocksAcked += bucket.blocksAcked.sum();
        }

        long numberOfSeconds = latestSecondTick - earliestSecondTick;
        if (numberOfSeconds == 0) {
            // if there was only one bucket, set the number of seconds to 1
            numberOfSeconds = 1;
        }

        final BigDecimal itemsPerSecondCreatedCount = round(totalItemsCreated.count.sum() / (numberOfSeconds * 1.0D));
        final BigDecimal itemsPerSecondCreatedBytes = round(totalItemsCreated.sum.sum() / (numberOfSeconds * 1.0D));
        final BigDecimal itemsPerSecondSentCount = round(totalItemsSent.count.sum() / (numberOfSeconds * 1.0D));
        final BigDecimal itemsPerSecondSentBytes = round(totalItemsSent.sum.sum() / (numberOfSeconds * 1.0D));

        blockAggregation.complete();

        final StringBuilder output = new StringBuilder("\nBlockStreamingStats {\n");

        output.append("  Summary {\n");
        output.append("    Seconds { (Unit:COUNT|Sum:").append(numberOfSeconds).append(") }\n");
        output.append("    Blocks {\n");
        output.append("      Opened { (Unit:COUNT|Sum:").append(blocksOpened).append(") }\n");
        output.append("      Closed { (Unit:COUNT|Sum:").append(blocksClosed).append(") }\n");
        output.append("      Acknowledged { (Unit:COUNT|Sum:").append(blocksAcked).append(") }\n");
        output.append("    }\n");
        output.append("    Items {\n");
        output.append("      Created-Total { (Unit:COUNT|Sum:").append(totalItemsCreated.count.sum()).append(")");
        output.append("(Unit:BYTES|Sum:").append(totalItemsCreated.sum.sum()).append(") }\n");
        output.append("      Created-PerSecond { (Unit:COUNT|Avg:").append(itemsPerSecondCreatedCount.toPlainString()).append(")");
        output.append("(Unit:BYTES|Avg:").append(itemsPerSecondCreatedBytes.toPlainString()).append(") }\n");
        output.append("      Sent-Total { (Unit:COUNT|Sum:").append(totalItemsSent.count.sum()).append(")");
        output.append("(Unit:BYTES|Sum:").append(totalItemsSent.sum.sum()).append(") }\n");
        output.append("      Sent-PerSecond { (Unit:COUNT|Avg:").append(itemsPerSecondSentCount.toPlainString()).append(")");
        output.append("(Unit:BYTES|Avg:").append(itemsPerSecondSentBytes.toPlainString()).append(") }\n");
        output.append("    }\n");
        output.append("  }\n");

        // append block details
        output.append("  BlockDetails {\n");
        output.append("    ").append(blockAggregation.initToOpen).append("\n");
        output.append("    ").append(blockAggregation.openToClose).append("\n");
        output.append("    ").append(blockAggregation.openToEndSent).append("\n");
        output.append("    ").append(blockAggregation.openToAck).append("\n");
        output.append("    ").append(blockAggregation.closedToAck).append("\n");
        output.append("    ").append(blockAggregation.headerProducedToAck).append("\n");
        output.append("    ").append(blockAggregation.headerSentToAck).append("\n");
        output.append("    ").append(blockAggregation.endSentToAck).append("\n");
        output.append("    ").append(blockAggregation.headerSentToEndSent).append("\n");
        output.append("    ").append(blockAggregation.openToProofAdded).append("\n");
        output.append("    ").append(blockAggregation.openToProofCreated).append("\n");
        output.append("    ").append(blockAggregation.itemsPerBlock).append("\n");
        output.append("  }\n");

        // append item details
        output.append("  ItemDetails {\n");
        output.append("    ").append(blockAggregation.itemIdleCombiner).append("\n");
        output.append("    ").append(blockAggregation.itemSendLatencyCombiner).append("\n");
        output.append("    ").append(blockAggregation.itemSizeCombiner).append("\n");
        output.append("  }\n");

        output.append("}");
        log.info("{}", output);
    }

    private static BigDecimal round(final double d) {
        return BigDecimal.valueOf(d).setScale(3, RoundingMode.HALF_EVEN);
    }

    private static class BlockStatsAggregation {
        private final LongProbe initToOpen = new LongProbe("InitToOpen", ObsUnit.NANOS);
        private final LongProbe openToClose = new LongProbe("OpenToClose", ObsUnit.NANOS);
        private final LongProbe openToEndSent = new LongProbe("OpenToEndSent", ObsUnit.NANOS);
        private final LongProbe openToAck = new LongProbe("OpenToAck", ObsUnit.NANOS);
        private final LongProbe closedToAck = new LongProbe("ClosedToAck", ObsUnit.NANOS);
        private final LongProbe headerProducedToAck = new LongProbe("HeaderProducedToAck", ObsUnit.NANOS);
        private final LongProbe headerSentToAck = new LongProbe("HeaderSentToAck", ObsUnit.NANOS);
        private final LongProbe endSentToAck = new LongProbe("EndSentToAck", ObsUnit.NANOS);
        private final LongProbe headerSentToEndSent = new LongProbe("HeaderSentToEndSent", ObsUnit.NANOS);
        private final LongProbe openToProofAdded = new LongProbe("OpenToProofAdded", ObsUnit.NANOS);
        private final LongProbe openToProofCreated = new LongProbe("OpenToProofCreated", ObsUnit.NANOS);

        private final LongProbe blockSize = new LongProbe("BlockSize", ObsUnit.BYTES);
        private final LongProbe itemsPerBlock = new LongProbe("ItemsPerBlock", ObsUnit.COUNT);

        private final StatisticsJoiner itemIdleCombiner = new StatisticsJoiner("ItemIdleTime", ObsUnit.NANOS);
        private final StatisticsJoiner itemSendLatencyCombiner = new StatisticsJoiner("ItemSendTime", ObsUnit.NANOS);
        private final StatisticsJoiner itemSizeCombiner = new StatisticsJoiner("ItemSize", ObsUnit.BYTES);

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

            itemIdleCombiner.add(blockStats.itemIdleProbe.aggregate());
            itemSendLatencyCombiner.add(blockStats.itemSendLatencyProbe.aggregate());
            itemSizeCombiner.add(blockStats.itemSizeProbe.aggregate());

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
        private final SummingCounter itemsCreated = new SummingCounter();
        private final SummingCounter itemsSent = new SummingCounter();
        private final LongAdder blocksOpened = new LongAdder();
        private final LongAdder blocksClosed = new LongAdder();
        private final LongAdder blocksAcked = new LongAdder();

        ThroughputBucket(final long secondTick) {
            this.secondTick = secondTick;
        }
    }

    private static class SummingCounter {
        private final LongAdder count = new LongAdder();
        private final LongAdder sum = new LongAdder();

        void add(final long value) {
            count.increment();
            sum.add(value);
        }

        void add(final long countVal, final long sumVal) {
            count.add(countVal);
            sum.add(sumVal);
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

        private final LongProbe itemIdleProbe = new LongProbe("ItemIdle", ObsUnit.NANOS);
        private final LongProbe itemSendLatencyProbe = new LongProbe("ItemSendLatency", ObsUnit.NANOS);
        private final LongProbe itemSizeProbe = new LongProbe("ItemSize", ObsUnit.BYTES);

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

        @Override
        public String toString() {
            return "BlockStats{" +
                    "blockNumber=" + blockNumber +
                    ", initNanosTick=" + initNanosTick +
                    ", openedNanosTick=" + openedNanosTick +
                    ", closedNanosTick=" + closedNanosTick +
                    ", ackedNanosTick=" + ackedNanosTick +
                    ", proofCreatedNanosTick=" + proofCreatedNanosTick +
                    ", proofAddedNanosTick=" + proofAddedNanosTick +
                    ", footerNanosTick=" + footerNanosTick +
                    ", headerSentNanosTicks=" + headerSentNanosTicks +
                    ", endSentNanosTicks=" + endSentNanosTicks +
                    ", items=" + items.size() +
                    '}';
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

    private static class StatisticsJoiner {
        private final String name;
        private final ObsUnit unit;
        private final Queue<Statistics> statsQueue = new ConcurrentLinkedQueue<>();

        public StatisticsJoiner(final String name, final ObsUnit unit) {
            this.name = name;
            this.unit = unit;
        }

        public void add(final Statistics statistics) {
            if (unit != statistics.unit()) {
                throw new IllegalArgumentException("Cannot add statistics with different unit");
            }

            statsQueue.add(statistics);
        }

        public Statistics statistics() {
            long count = 0;
            long total = 0;
            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;

            for (final Statistics stats : statsQueue) {
                count += stats.count();
                total += stats.total();
                if (min > stats.min()) {
                    min = stats.min();
                }
                if (max < stats.max()) {
                    max = stats.max();
                }
            }

            if (count == 0) {
                return Statistics.NIL;
            }

            final double avg = total / (count * 1.0D);
            double stdDev = 0.0D;

            for (final Statistics stats : statsQueue) {
                final double d1 = stats.count() * Math.pow(stats.stdDev(), 2);
                final double d2 = stats.count() * Math.pow(stats.avg() - avg, 2);
                stdDev += d1 + d2;
            }

            stdDev = stdDev / count;
            stdDev = Math.sqrt(stdDev);

            return new Statistics(unit, count, total, min, max, avg, stdDev);
        }

        @Override
        public String toString() {
            String s = name;

            s += " { (Unit:" + unit;

            final Statistics statistics = statistics();

            s += "|Samples:" + statistics.count();
            s += "|Sum:" + statistics.total();
            s += "|Min:" + statistics.min();
            s += "|Max:" + statistics.max();
            s += "|Avg:" + round(statistics.avg()).toPlainString();
            s += "|StdDev:" + round(statistics.stdDev()).toPlainString();

            s += ") }";
            return s;
        }
    }
}

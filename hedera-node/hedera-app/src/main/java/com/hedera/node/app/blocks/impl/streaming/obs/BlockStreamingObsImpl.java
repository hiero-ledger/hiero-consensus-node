package com.hedera.node.app.blocks.impl.streaming.obs;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigDecimal;
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

@Singleton
public class BlockStreamingObsImpl implements BlockStreamingObs {
    private static final Logger log = LogManager.getLogger(BlockStreamingObs.class);

    private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final int MAX_BUCKETS = 150;

    //    private final boolean isEnhancedObsEnabled;
    private final BlockStreamMetrics metrics;
    // Map<BlockNumber, BlockStats>
    private final ConcurrentMap<Long, BlockStats> blockStats = new ConcurrentHashMap<>();
    // Map<SecondTick, ThroughputBucket>
    private final ConcurrentMap<Long, ThroughputBucket> throughputBuckets = new ConcurrentHashMap<>(MAX_BUCKETS);
    private final AtomicLong latestBlockNumber = new AtomicLong(-1);
    private final long initialNanosTick;

    private final ScheduledExecutorService executorService;

    @Inject
    public BlockStreamingObsImpl(@NonNull final BlockStreamMetrics metrics, @NonNull ConfigProvider configProvider) {
        this.metrics = requireNonNull(metrics);
        initialNanosTick = System.nanoTime();

        executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.schedule(new StatsTask(), 30, TimeUnit.SECONDS);
    }

    private class StatsTask implements Runnable {
        @Override
        public void run() {
            try {
                logStatistics();
            } finally {
                executorService.schedule(new StatsTask(), 30, TimeUnit.SECONDS);
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
        blockStats.put(blockNumber, new BlockStats(blockNumber, nanosTick));
        latestBlockNumber.updateAndGet(old -> Math.max(old, blockNumber));
    }

    @Override
    public void onBlockOpen(final long blockNumber, final long nanosTick) {
        final BlockStats stats = blockStats.get(blockNumber);
        if (stats != null) {
            stats.openedNanosTick.compareAndSet(-1, nanosTick);
        }
    }

    @Override
    public void onBlockItemAdd(final long blockNumber, final int itemIndex, final long nanosTick, final int sizeInBytes) {
        final BlockStats stats = blockStats.get(blockNumber);
        if (stats == null) {
            return;
        }

        if (stats.items.putIfAbsent(itemIndex, new BlockItemStats(itemIndex, sizeInBytes, nanosTick)) == null) {
            getThroughputBucket(nanosTick).itemsCreated.add(sizeInBytes);
        }
    }

    @Override
    public void onBlockItemSend(final long blockNumber, final int itemIndexStart, final int itemIndexEnd, final long startNanosTick, final long endNanosTick) {
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
        final BlockStats stats = blockStats.get(blockNumber);
        if (stats == null) {
            return;
        }

        stats.endSentNanosTicks.compareAndSet(null, new StartAndEndTicks(startNanosTick, endNanosTick));
    }

    @Override
    public void onBlockClose(final long blockNumber, final long nanosTick) {
        final BlockStats stats = blockStats.get(blockNumber);
        if (stats != null) {
            stats.closedNanosTick.compareAndSet(-1, nanosTick);
        }
    }

    @Override
    public void onBlockAcked(final long blockNumber, final long nanosTick) {
        final BlockStats stats = blockStats.get(blockNumber);
        if (stats == null) {
            return;
        }

        if (!stats.ackedNanosTick.compareAndSet(-1, nanosTick)) {
            // the block has already been acked
            return;
        }

        // calculate and emit block timing metrics
        final long closedToAckNanos = stats.ackedNanosTick.get() - stats.closedNanosTick.get();
        final long headerProducedToAckNanos = stats.ackedNanosTick.get() - stats.headerSentNanosTicks.get().start;
        final long headerSentToAckNanos = stats.ackedNanosTick.get() - stats.headerSentNanosTicks.get().end;
        final long endSentToAckNanos = stats.ackedNanosTick.get() - stats.endSentNanosTicks.get().end;
        final long headerSentToEndSentNanos = stats.endSentNanosTicks.get().end - stats.headerSentNanosTicks.get().end;

        metrics.recordHeaderProducedToAckLatency(headerProducedToAckNanos);
        metrics.recordBlockClosedToAckLatency(closedToAckNanos);
        metrics.recordHeaderSentAckLatency(headerSentToAckNanos);
        metrics.recordBlockEndSentToAckLatency(endSentToAckNanos);
        metrics.recordHeaderSentToBlockEndSentLatency(headerSentToEndSentNanos);

        stats.aggregate();

        final StringBuilder sb = new StringBuilder();

        sb.append("BlockTimingData {\n");
        sb.append("  [(Name:Block#").append(stats.blockNumber).append(")(Unit:NANOS)");
        sb.append("(I2O:").append(stats.initToOpen()).append(")"); // I2O = Init-2-Open
        sb.append("(O2Pc:").append(stats.openToProofCreated()).append(")"); // O2Pc = Open-2-Proof Created
        sb.append("(O2Pa:").append(stats.openToProofAdded()).append(")"); // O2Pa = Open-2-Proof Added
        sb.append("(O2C:").append(stats.openToClose()).append(")"); // O2C = Open-2-Close
        sb.append("(O2Es:").append(stats.openToEndSent()).append(")"); // O2E = Open-2-End Sent
        sb.append("(O2A:").append(stats.openToAck()).append(")"); // O2A = Open-2-Ack
        sb.append("(E2A:").append(stats.endSentToAck()).append(")"); // E2A = End-2-Ack
        sb.append("(C2A:").append(stats.closedToAck()).append(")"); // C2A = Close-2-Ack
        sb.append("(Hp2A:").append(stats.headerProducedToAck()).append(")"); // Hp2A = Header Produced-2-Ack
        sb.append("(Hs2Es:").append(stats.headerSentToEndSent()).append(")"); // Hs2Es = Header Sent-2-End Sent
        sb.append("(Hs2A:").append(stats.headerSentToAck()).append(")"); // Hs2A = Header Sent-2-Ack
        sb.append("]\n");
        sb.append("  ").append(stats.itemIdleProbe).append("\n");
        sb.append("  ").append(stats.itemSendLatencyProbe).append("\n");
        sb.append("  ").append(stats.itemSizeProbe).append("\n");
        sb.append("}");

        log.info("{}", sb);
    }

    @Override
    public void onBlockProofCreate(final long blockNumber, final long nanosTick) {
        final BlockStats stats = blockStats.get(blockNumber);
        if (stats != null) {
            stats.proofCreatedNanosTick.compareAndSet(-1, nanosTick);
        }
    }

    @Override
    public void onBlockProofAdd(final long blockNumber, final long nanosTick) {
        final BlockStats stats = blockStats.get(blockNumber);
        if (stats != null) {
            stats.proofAddedNanosTick.compareAndSet(-1, nanosTick);
        }
    }

    @Override
    public void onBlockHeaderSend(final long blockNumber, final long startNanosTick, final long endNanosTick) {
        final BlockStats stats = blockStats.get(blockNumber);
        if (stats != null) {
            stats.headerSentNanosTicks.compareAndSet(null, new StartAndEndTicks(startNanosTick, endNanosTick));
        }
    }

    @Override
    public void onBlockFooterCreate(final long blockNumber, final long nanosTick) {
        final BlockStats stats = blockStats.get(blockNumber);
        if (stats != null) {
            stats.footerNanosTick.compareAndSet(-1, nanosTick);
        }
    }

    private void logStatistics() {
        final StringBuilder output = new StringBuilder("BlockStreamingStatistics\n");

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

        for (final ThroughputBucket bucket : localThroughputBuckets.values()) {
            if (earliestSecondTick > bucket.secondTick) {
                earliestSecondTick = bucket.secondTick;
            }
            if (latestSecondTick < bucket.secondTick) {
                latestSecondTick = bucket.secondTick;
            }

            totalItemsCreated.add(bucket.itemsCreated.count.sum(), bucket.itemsCreated.sum.sum());
            totalItemsSent.add(bucket.itemsSent.count.sum(), bucket.itemsSent.sum.sum());
        }

        long numberOfSeconds = latestSecondTick - earliestSecondTick;
        if (numberOfSeconds == 0) {
            // if there was only one bucket, set the number of seconds to 1
            numberOfSeconds = 1;
        }

        final BigDecimal itemsPerSecondCreated_count = round(totalItemsCreated.count.sum() / (numberOfSeconds * 1.0D));
        final BigDecimal itemsPerSecondCreated_bytes = round(totalItemsCreated.sum.sum() / (numberOfSeconds * 1.0D));
        final BigDecimal itemsPerSecondSent_count = round(totalItemsSent.count.sum() / (numberOfSeconds * 1.0D));
        final BigDecimal itemsPerSecondSent_bytes = round(totalItemsSent.sum.sum() / (numberOfSeconds * 1.0D));

        output.append("  Throughput {\n");
        output.append("    [(Seconds:").append(numberOfSeconds).append(")]\n");
        output.append("    [(Name:ItemsCreatedTotal)(Unit:COUNT)(Sum:").append(totalItemsCreated.count.sum()).append(")");
        output.append("(Unit:BYTES)(Sum:").append(totalItemsCreated.sum.sum()).append(")]\n");
        output.append("    [(Name:ItemsCreatedPerSecond)(Unit:COUNT)(Avg:").append(itemsPerSecondCreated_count.toPlainString()).append(")");
        output.append("(Unit:BYTES)(Avg:").append(itemsPerSecondCreated_bytes.toPlainString()).append(")]\n");
        output.append("    [(Name:ItemsSentTotal)(Unit:COUNT)(Sum:").append(totalItemsSent.count.sum()).append(")");
        output.append("(Unit:BYTES)(Sum:").append(totalItemsSent.sum.sum()).append(")]\n");
        output.append("    [(Name:ItemsSentPerSecond)(Unit:COUNT)(Avg:").append(itemsPerSecondSent_count.toPlainString()).append(")");
        output.append("(Unit:BYTES)(Avg:").append(itemsPerSecondSent_bytes.toPlainString()).append(")]\n");
        output.append("  }");

        output.append("}");
        log.info("{}", output);
    }

    private BigDecimal round(final double d) {
        return BigDecimal.valueOf(d).setScale(3, RoundingMode.HALF_EVEN);
    }

    // ----------------

    private static class BlockStatsAggregation {
        private final LongProbe initToOpen = new LongProbe("BlockInitToOpen", ObsUnit.NANOS);
        private final LongProbe openToClose = new LongProbe("BlockOpenToClose", ObsUnit.NANOS);
        private final LongProbe openToEndSent = new LongProbe("BlockOpenToEndSent", ObsUnit.NANOS);
        private final LongProbe openToAck = new LongProbe("BlockOpenToAck", ObsUnit.NANOS);
        private final LongProbe closedToAck = new LongProbe("BlockClosedToAck", ObsUnit.NANOS);
        private final LongProbe headerProducedToAck = new LongProbe("BlockHeaderProducedToAck", ObsUnit.NANOS);
        private final LongProbe headerSentToAck = new LongProbe("BlockHeaderSentToAck", ObsUnit.NANOS);
        private final LongProbe endSentToAck = new LongProbe("BlockEndSentToAck", ObsUnit.NANOS);
        private final LongProbe headerSentToEndSent = new LongProbe("BlockHeaderSentToEndSent", ObsUnit.NANOS);
        private final LongProbe openToProofAdded = new LongProbe("BlockOpenToProofAdded", ObsUnit.NANOS);
        private final LongProbe openToProofCreated = new LongProbe("BlockOpenToProofCreated", ObsUnit.NANOS);


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
        }
    }

    private static class ThroughputBucket {
        private final long secondTick;
        private final SummingCounter itemsCreated = new SummingCounter();
        private final SummingCounter itemsSent = new SummingCounter();

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
        private final LongProbe itemSizeProbe = new LongProbe("ItemSendSize", ObsUnit.BYTES);

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
        private final int itemIndex;
        private final long itemSizeInBytes;
        private final long itemBufferedNanosTick;
        private final AtomicReference<StartAndEndTicks> itemSendNanosTicks = new AtomicReference<>();

        public BlockItemStats(final int itemIndex, final long itemSizeInBytes, final long itemBufferedNanosTick) {
            this.itemIndex = itemIndex;
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

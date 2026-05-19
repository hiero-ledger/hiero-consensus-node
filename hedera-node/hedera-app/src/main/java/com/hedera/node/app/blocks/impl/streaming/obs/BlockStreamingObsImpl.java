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
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
        final long thresholdNanosTick = NANOS_PER_SECOND * thresholdSecondTick;

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

        final SortedMap<Long, BlockStats> localBlockStats = new TreeMap<>();
        final Iterator<Map.Entry<Long, BlockStats>> blockStatsIt = blockStats.entrySet().iterator();
        while (blockStatsIt.hasNext()) {
            final Map.Entry<Long, BlockStats> entry = blockStatsIt.next();
            final BlockStats stats = entry.getValue();
            if (stats.isAcked() && stats.initNanosTick <= thresholdNanosTick) {
                blockStatsIt.remove();
                localBlockStats.put(entry.getKey(), entry.getValue());
            }
        }

        // append block stats to output
        for (final BlockStats bStats : localBlockStats.values()) {
            appendBlockStatistics(output, bStats);
            output.append("\n");
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

            totalItemsCreated.add(bucket.itemsCreated.count.get(), bucket.itemsCreated.sum.get());
            totalItemsSent.add(bucket.itemsSent.count.get(), bucket.itemsSent.sum.get());
        }

        long numberOfSeconds = latestSecondTick - earliestSecondTick;
        if (numberOfSeconds == 0) {
            // if there was only one bucket, set the number of seconds to 1
            numberOfSeconds = 1;
        }

        final BigDecimal itemsPerSecondCreated_count = round(totalItemsCreated.count.get() / (numberOfSeconds * 1.0D));
        final BigDecimal itemsPerSecondCreated_bytes = round(totalItemsCreated.sum.get() / (numberOfSeconds * 1.0D));
        final BigDecimal itemsPerSecondSent_count = round(totalItemsSent.count.get() / (numberOfSeconds * 1.0D));
        final BigDecimal itemsPerSecondSent_bytes = round(totalItemsSent.sum.get() / (numberOfSeconds * 1.0D));

        output.append("  Throughput {\n");
        output.append("    (Seconds:").append(numberOfSeconds).append(")\n");
        output.append("(Name:ItemsCreated)(Unit:COUNT_PER_SECOND)(Avg:").append(itemsPerSecondCreated_count.toPlainString()).append(")\n");
        output.append("(Name:ItemsCreated)(Unit:BYTES_PER_SECOND)(Avg:").append(itemsPerSecondCreated_bytes.toPlainString()).append(")\n");
        output.append("(Name:ItemsSent)(Unit:COUNT_PER_SECOND)(Avg:").append(itemsPerSecondSent_count.toPlainString()).append(")\n");
        output.append("(Name:ItemsSent)(Unit:BYTES_PER_SECOND)(Avg:").append(itemsPerSecondSent_bytes.toPlainString()).append(")\n");
        output.append("}");

        log.info("{}", output);
    }

    private void appendBlockStatistics(final StringBuilder sb, final BlockStats stats) {
        final long initToOpenNanos = stats.openedNanosTick.get() - stats.initNanosTick;
        final long openToCloseNanos = stats.closedNanosTick.get() - stats.openedNanosTick.get();
        final long openToEndSentNanos = stats.endSentNanosTicks.get().end - stats.openedNanosTick.get();
        final long openToAckNanos = stats.ackedNanosTick.get() - stats.openedNanosTick.get();
        final long closedToAckNanos = stats.ackedNanosTick.get() - stats.closedNanosTick.get();
        final long headerProducedToAckNanos = stats.ackedNanosTick.get() - stats.headerSentNanosTicks.get().start;
        final long headerSentToAckNanos = stats.ackedNanosTick.get() - stats.headerSentNanosTicks.get().end;
        final long endSentToAckNanos = stats.ackedNanosTick.get() - stats.endSentNanosTicks.get().end;
        final long headerSentToEndSentNanos = stats.endSentNanosTicks.get().end - stats.headerSentNanosTicks.get().end;
        final long openToProofAddedNanos = stats.proofAddedNanosTick.get() - stats.openedNanosTick.get();
        final long openToProofCreatedNanos = stats.proofCreatedNanosTick.get() - stats.openedNanosTick.get();

        sb.append("  BlockTimingData {\n");
        sb.append("    [(Name:Block#").append(stats.blockNumber).append(")(Unit:NANOS)");
        sb.append("(I2O:").append(initToOpenNanos).append(")"); // I2O = Init-2-Open
        sb.append("(O2Pc:").append(openToProofCreatedNanos).append(")"); // O2Pc = Open-2-Proof Created
        sb.append("(O2Pa:").append(openToProofAddedNanos).append(")"); // O2Pa = Open-2-Proof Added
        sb.append("(O2C:").append(openToCloseNanos).append(")"); // O2C = Open-2-Close
        sb.append("(O2Es:").append(openToEndSentNanos).append(")"); // O2E = Open-2-End Sent
        sb.append("(O2A:").append(openToAckNanos).append(")"); // O2A = Open-2-Ack
        sb.append("(E2A:").append(endSentToAckNanos).append(")"); // E2A = End-2-Ack
        sb.append("(C2A:").append(closedToAckNanos).append(")"); // C2A = Close-2-Ack
        sb.append("(Hp2A:").append(headerProducedToAckNanos).append(")"); // Hp2A = Header Produced-2-Ack
        sb.append("(Hs2Es:").append(headerSentToEndSentNanos).append(")"); // Hs2Es = Header Sent-2-End Sent
        sb.append("(Hs2A:").append(headerSentToAckNanos).append(")"); // Hs2A = Header Sent-2-Ack
        sb.append("]\n");
        sb.append("    ").append(stats.itemIdleProbe).append("\n");
        sb.append("    ").append(stats.itemSendLatencyProbe).append("\n");
        sb.append("    ").append(stats.itemSizeProbe).append("\n");
        sb.append("}");
    }

    private BigDecimal round(final double d) {
        return BigDecimal.valueOf(d).setScale(3, RoundingMode.HALF_EVEN);
    }

    // ----------------

    private static class ThroughputBucket {
        private final long secondTick;
        private final SummingCounter itemsCreated = new SummingCounter();
        private final SummingCounter itemsSent = new SummingCounter();

        ThroughputBucket(final long secondTick) {
            this.secondTick = secondTick;
        }
    }

    private static class SummingCounter {
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicLong sum = new AtomicLong(0);

        void add(final long value) {
            count.incrementAndGet();
            sum.addAndGet(value);
        }

        void add(final long countVal, final long sumVal) {
            count.addAndGet(countVal);
            sum.addAndGet(sumVal);
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

        boolean isAcked() {
            return ackedNanosTick.get() != -1;
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

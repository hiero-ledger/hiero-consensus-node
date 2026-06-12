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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
public class BlockStreamingObs implements AutoCloseable {

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

        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "BlockStreamingObsGather");
            thread.setDaemon(true);
            return thread;
        });
        scheduledExecutorService.schedule(new ObsGatherAndLogTask(), PERIOD_SECONDS, TimeUnit.SECONDS);

        isEnabled = configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .enhancedObservabilityEnabled();
    }

    /** Stops the periodic gather-and-log task. The instance must not be used after closing. */
    @Override
    public void close() {
        scheduledExecutorService.shutdownNow();
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

        final List<ThroughputBucket> buckets = drainThroughputBuckets(thresholdSecondTick);
        final BlockStatsAggregation blocksAggregation = drainBlocks(nanosTick, thresholdNanosTick);

        if (buckets.isEmpty() && blocksAggregation.isEmpty()) {
            // nothing was recorded in this window; skip the report entirely
            return;
        }

        blocksAggregation.complete();

        log.info("{}", formatReport(ThroughputSummary.of(buckets), blocksAggregation));
    }

    /** Removes and returns all throughput buckets at or before the threshold second. */
    private List<ThroughputBucket> drainThroughputBuckets(final long thresholdSecondTick) {
        final List<ThroughputBucket> drained = new ArrayList<>();
        final Iterator<Map.Entry<Long, ThroughputBucket>> it =
                throughputBuckets.entrySet().iterator();

        while (it.hasNext()) {
            final Map.Entry<Long, ThroughputBucket> entry = it.next();
            if (entry.getKey() <= thresholdSecondTick) {
                it.remove();
                drained.add(entry.getValue());
            }
        }
        return drained;
    }

    /** Removes terminal blocks from {@link #blockStatistics} and aggregates the acked ones. */
    private BlockStatsAggregation drainBlocks(final long nanosTick, final long thresholdNanosTick) {
        final BlockStatsAggregation aggregation = new BlockStatsAggregation();
        final Iterator<Map.Entry<Long, BlockStats>> it = blockStatistics.entrySet().iterator();

        while (it.hasNext()) {
            final BlockStats blockStats = it.next().getValue();
            // only blocks acked before the threshold are eligible; the ack is terminal, so by now
            // no other thread is still recording events for the block, and it is safe to aggregate
            final long ackedNanosTick = blockStats.ackedNanosTick.get();
            if (ackedNanosTick != -1 && ackedNanosTick <= thresholdNanosTick) {
                it.remove();
                aggregation.add(blockStats);
            } else if (ackedNanosTick == -1 && nanosTick - blockStats.initNanosTick >= ABANDONED_AFTER_NANOS) {
                // never acked and too old: evict without aggregating (other threads may still be
                // mutating the block, so its probes must not be touched) and only count it
                it.remove();
                aggregation.markAbandoned();
            }
        }
        return aggregation;
    }

    private static String formatReport(
            final ThroughputSummary summary, final BlockStatsAggregation blocksAggregation) {
        // spotless:off
        final StringBuilder output = new StringBuilder("\nBlockStreamingStats {\n");

        output.append("  Summary {\n");
        output.append("    Seconds { (Unit:COUNT|Sum:").append(summary.numberOfSeconds()).append(") }\n");
        output.append("    Blocks {\n");
        output.append("      Opened { (Unit:COUNT|Sum:").append(summary.blocksOpened()).append(") }\n");
        output.append("      Closed { (Unit:COUNT|Sum:").append(summary.blocksClosed()).append(") }\n");
        output.append("      Acknowledged { (Unit:COUNT|Sum:").append(summary.blocksAcked()).append(") }\n");
        output.append("      Abandoned { (Unit:COUNT|Sum:").append(blocksAggregation.blocksAbandoned).append(") }\n");
        output.append("    }\n");
        output.append("    Items {\n");
        output.append("      Created-Total { (Unit:COUNT|Sum:").append(summary.itemsCreated().numSamples).append(")");
        output.append("(Unit:BYTES|Sum:").append(summary.itemsCreated().sum).append(") }\n");
        output.append("      Created-PerSecond { (Unit:COUNT|Avg:").append(toString(summary.perSecond(summary.itemsCreated().numSamples))).append(")");
        output.append("(Unit:BYTES|Avg:").append(toString(summary.perSecond(summary.itemsCreated().sum))).append(") }\n");
        output.append("      Sent-Total { (Unit:COUNT|Sum:").append(summary.itemsSent().numSamples).append(")");
        output.append("(Unit:BYTES|Sum:").append(summary.itemsSent().sum).append(") }\n");
        output.append("      Sent-PerSecond { (Unit:COUNT|Avg:").append(toString(summary.perSecond(summary.itemsSent().numSamples))).append(")");
        output.append("(Unit:BYTES|Avg:").append(toString(summary.perSecond(summary.itemsSent().sum))).append(") }\n");
        output.append("    }\n");
        output.append("  }\n");

        // append block details
        output.append("  BlockDetails {\n");
        for (final StatisticsProbe probe : blocksAggregation.blockProbes()) {
            output.append("    ").append(probe).append("\n");
        }
        output.append("  }\n");

        // append item details
        output.append("  ItemDetails {\n");
        output.append("    ItemIdle ").append(Statistics.toString(blocksAggregation.itemIdleComposite)).append("\n");
        output.append("    ItemSendLatency ").append(Statistics.toString(blocksAggregation.itemSendLatencyComposite)).append("\n");
        output.append("    ItemSize ").append(Statistics.toString(blocksAggregation.itemSizeComposite)).append("\n");
        output.append("    ItemsNeverSent { (Unit:COUNT|Sum:").append(blocksAggregation.itemsNeverSent).append(") }\n");
        output.append("  }\n");

        output.append("}");
        // spotless:on
        return output.toString();
    }

    private static String toString(final BigDecimal bd) {
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

    /** Block-event totals and item count/byte sums across the drained buckets of one reporting window. */
    private record ThroughputSummary(
            long numberOfSeconds,
            long blocksOpened,
            long blocksClosed,
            long blocksAcked,
            SimpleAggregator itemsCreated,
            SimpleAggregator itemsSent) {

        static ThroughputSummary of(final List<ThroughputBucket> buckets) {
            long earliestSecondTick = Long.MAX_VALUE;
            long latestSecondTick = Long.MIN_VALUE;
            long blocksOpened = 0;
            long blocksClosed = 0;
            long blocksAcked = 0;
            final SimpleAggregator itemsCreated = new SimpleAggregator();
            final SimpleAggregator itemsSent = new SimpleAggregator();

            for (final ThroughputBucket bucket : buckets) {
                earliestSecondTick = Math.min(earliestSecondTick, bucket.secondTick);
                latestSecondTick = Math.max(latestSecondTick, bucket.secondTick);

                blocksOpened += bucket.blocksOpened.sum();
                blocksClosed += bucket.blocksClosed.sum();
                blocksAcked += bucket.blocksAcked.sum();

                final Statistics itemsCreatedStats = bucket.itemsCreated.aggregate();
                final Statistics itemsSentStats = bucket.itemsSent.aggregate();

                itemsCreated.add(itemsCreatedStats.numSamples(), itemsCreatedStats.sum());
                itemsSent.add(itemsSentStats.numSamples(), itemsSentStats.sum());
            }

            // +1 because the bucket tick range is inclusive on both ends (N buckets span N seconds); if there
            // are no buckets (only gathered/abandoned blocks), fall back to 1 to keep the divisions harmless
            final long numberOfSeconds = buckets.isEmpty() ? 1 : (latestSecondTick - earliestSecondTick) + 1;

            return new ThroughputSummary(numberOfSeconds, blocksOpened, blocksClosed, blocksAcked, itemsCreated, itemsSent);
        }

        BigDecimal perSecond(final BigInteger value) {
            return new BigDecimal(value).divide(BigDecimal.valueOf(numberOfSeconds), MATH_CONTEXT_10);
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
        private long itemsNeverSent = 0;
        private long blocksAggregated = 0;
        private long blocksAbandoned = 0;

        void add(final BlockStats blockStats) {
            ++blocksAggregated;

            // computes the per-item probes and blockSize; must only ever run on the gather thread
            blockStats.aggregate();

            // any tick may still be the -1 "never recorded" sentinel; a probe gets a sample only when every
            // tick it depends on was recorded, otherwise a garbage value would pollute the window
            final long opened = blockStats.openedNanosTick.get();
            final long closed = blockStats.closedNanosTick.get();
            final long proofCreated = blockStats.proofCreatedNanosTick.get();
            final long proofAdded = blockStats.proofAddedNanosTick.get();
            final long footerCreated = blockStats.footerNanosTick.get();
            final StartAndEndTicks endTicks = blockStats.endSentNanosTicks.get();
            final StartAndEndTicks headerTicks = blockStats.headerSentNanosTicks.get();

            if (opened != -1) {
                initToOpen.add(blockStats.initToOpen());
                openToAck.add(blockStats.openToAck());
                if (closed != -1) {
                    openToClose.add(blockStats.openToClose());
                }
                if (proofAdded != -1) {
                    openToProofAdded.add(blockStats.openToProofAdded());
                }
                if (proofCreated != -1) {
                    openToProofCreated.add(blockStats.openToProofCreated());
                }
                if (endTicks != null) {
                    openToEndSent.add(blockStats.openToEndSent());
                }
            }
            if (closed != -1) {
                closedToAck.add(blockStats.closedToAck());
            }
            if (footerCreated != -1 && proofCreated != -1) {
                footerCreatedToProofCreated.add(blockStats.footerCreatedToProofCreated());
            }
            if (endTicks != null) {
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
            itemsNeverSent += blockStats.itemsNeverSent;

            blockSize.add(blockStats.blockSize);
            itemsPerBlock.add(blockStats.items.size());
        }

        /** Counts a block evicted without ever being acked; its probes are never touched. */
        void markAbandoned() {
            ++blocksAbandoned;
        }

        boolean isEmpty() {
            return blocksAggregated == 0 && blocksAbandoned == 0;
        }

        /** The per-block probes, in the order they appear in the {@code BlockDetails} report section. */
        List<StatisticsProbe> blockProbes() {
            return List.of(
                    initToOpen,
                    openToClose,
                    openToEndSent,
                    openToAck,
                    closedToAck,
                    headerSendStartedToAck,
                    headerSentToAck,
                    endSentToAck,
                    headerSentToEndSent,
                    openToProofAdded,
                    openToProofCreated,
                    footerCreatedToProofCreated,
                    blockSize,
                    itemsPerBlock);
        }

        void complete() {
            blockProbes().forEach(StatisticsProbe::aggregate);
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
        private long itemsNeverSent = 0;

        public BlockStats(final long initNanosTick) {
            this.initNanosTick = initNanosTick;
        }

        void aggregate() {
            for (final Map.Entry<Integer, BlockItemStats> itemStatsEntry : items.entrySet()) {
                final BlockItemStats itemStats = itemStatsEntry.getValue();
                final StartAndEndTicks sendTicks = itemStats.itemSendNanosTicks.get();
                if (sendTicks != null) {
                    itemIdleProbe.add(sendTicks.start() - itemStats.itemBufferedNanosTick);
                    itemSendLatencyProbe.add(sendTicks.diff());
                } else {
                    ++itemsNeverSent;
                }
                // an item this node never sent (e.g. the block node already had the block when the
                // connection started) still counts toward size, just not toward idle/send latency
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
        long diff() {
            return end - start;
        }
    }
}

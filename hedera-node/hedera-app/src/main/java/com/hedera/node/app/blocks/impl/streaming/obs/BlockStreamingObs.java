// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import static com.hedera.node.app.blocks.impl.streaming.obs.ObsUtils.MATH_CONTEXT_10;
import static java.util.Objects.requireNonNull;

import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
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
    /** Sizing hint only — steady state is ~62 live buckets; {@link #getThroughputBucket} guards runaway growth. */
    private static final int THROUGHPUT_BUCKETS_CAPACITY_HINT = (PERIOD_SECONDS * 2) + 10;
    /**
     * A block that has not been acked this long after its init is considered abandoned (e.g. block node down,
     * streaming disabled, or pruned from the buffer without an ack) and is evicted from tracking so that
     * {@link #blockStatistics} cannot grow without bound. Abandoned blocks are reported in the summary.
     */
    private static final long ABANDONED_AFTER_NANOS = TimeUnit.SECONDS.toNanos(PERIOD_SECONDS * 5L);

    private final ConfigProvider configProvider;
    private final LongSupplier nanoClock;
    private volatile boolean isEnabled;
    // ConcurrentMap<BlockNumber, BlockStats>
    private final ConcurrentMap<Long, BlockStats> blockStatistics = new ConcurrentHashMap<>();
    // ConcurrentMap<SecondTick, ThroughputBucket>
    private final ConcurrentMap<Long, ThroughputBucket> throughputBuckets =
            new ConcurrentHashMap<>(THROUGHPUT_BUCKETS_CAPACITY_HINT);
    private final long initialNanosTick;
    private final ScheduledExecutorService scheduledExecutorService;

    @Inject
    public BlockStreamingObs(@NonNull final ConfigProvider configProvider) {
        this(configProvider, System::nanoTime);
    }

    /**
     * Package-private for tests: allows substituting the clock that all timestamps are read from.
     *
     * @param configProvider provides the {@link BlockStreamConfig} that gates and tunes the feature
     * @param nanoClock the source of monotonic nanosecond timestamps (normally {@code System::nanoTime})
     */
    BlockStreamingObs(@NonNull final ConfigProvider configProvider, @NonNull final LongSupplier nanoClock) {
        this.configProvider = requireNonNull(configProvider);
        this.nanoClock = requireNonNull(nanoClock);
        initialNanosTick = nanoClock.getAsLong();

        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "BlockStreamingObsGather");
            thread.setDaemon(true);
            return thread;
        });
        scheduledExecutorService.schedule(new ObsGatherAndLogTask(), PERIOD_SECONDS, TimeUnit.SECONDS);

        refreshEnabledFlag();
    }

    /** Re-reads the feature flag from config; called by the periodic task before every gather. */
    private void refreshEnabledFlag() {
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

    /**
     * Called when a block is first created; opens the block stats entry.
     *
     * @param blockNumber the block number
     */
    public void onBlockInit(final long blockNumber) {
        if (!isEnabled) {
            return;
        }

        blockStatistics.put(blockNumber, new BlockStats(nanoClock.getAsLong()));
    }

    /**
     * Called when a block transitions to the open/buffered state.
     *
     * @param blockNumber the block number
     */
    public void onBlockOpen(final long blockNumber) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats == null) {
            return;
        }

        final long nanosTick = nanoClock.getAsLong();
        if (stats.markOpened(nanosTick)) {
            getThroughputBucket(nanosTick).blocksOpened.increment();
        }
    }

    /**
     * Called when a block item is added to the buffer. {@code sizeInBytes} is used to compute
     * per-second byte throughput and the per-item size distribution. When {@code isBlockProof} is
     * {@code true}, the block's proof-added timestamp is recorded as well.
     *
     * @param blockNumber the block number
     * @param itemIndex the index of the item within the block
     * @param sizeInBytes the serialized size of the item in bytes
     * @param isBlockProof {@code true} if the item is the block proof
     */
    public void onBlockItemAdd(
            final long blockNumber, final int itemIndex, final int sizeInBytes, final boolean isBlockProof) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats == null) {
            return;
        }

        final long nanosTick = nanoClock.getAsLong();
        if (stats.recordItem(itemIndex, sizeInBytes, nanosTick)) {
            getThroughputBucket(nanosTick).itemsCreated.add(sizeInBytes);
        }
        if (isBlockProof) {
            stats.markProofAdded(nanosTick);
        }
    }

    /**
     * Called when a range of block items [{@code itemIndexStart}, {@code itemIndexEnd}] have been
     * written to the gRPC stream. Records idle time (buffered-to-send) and send latency per item.
     *
     * @param blockNumber the block number
     * @param itemIndexStart the index of the first item in the send range (inclusive)
     * @param itemIndexEnd the index of the last item in the send range (inclusive)
     * @param nanosTickStart the {@code nanoTime} when the send started
     * @param nanosTickEnd the {@code nanoTime} when the send completed
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
            final long sentSize = stats.markItemSent(index, ticks);
            if (sentSize >= 0) {
                throughputBucket.itemsSent.add(sentSize);
            }
        }
    }

    /**
     * Records when the {@code BlockEnd} message for a block was written to the gRPC stream.
     *
     * @param blockNumber the block number
     * @param nanosTickStart the {@code nanoTime} when the send started
     * @param nanosTickEnd the {@code nanoTime} when the send completed
     */
    public void onBlockEndSend(final long blockNumber, final long nanosTickStart, final long nanosTickEnd) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats != null) {
            stats.markEndSent(nanosTickStart, nanosTickEnd);
        }
    }

    /**
     * Records when a block transitioned to the closed state (all items written to the buffer).
     *
     * @param blockNumber the block number
     */
    public void onBlockClose(final long blockNumber) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats == null) {
            return;
        }

        final long nanosTick = nanoClock.getAsLong();
        if (stats.markClosed(nanosTick)) {
            getThroughputBucket(nanosTick).blocksClosed.increment();
        }
    }

    /**
     * Called when a block is acknowledged by the block node. Per-block aggregation is deliberately
     * NOT done here: it happens only on the gather thread, once the ack is older than the grace
     * period, so the ack thread can never race the gather task inside the probes.
     *
     * @param blockNumber the block number
     */
    public void onBlockAcknowledge(final long blockNumber) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats == null) {
            return;
        }

        final long nanosTick = nanoClock.getAsLong();
        if (stats.markAcked(nanosTick)) {
            getThroughputBucket(nanosTick).blocksAcked.increment();
        }
    }

    /**
     * Records when the block proof item was created by the consensus layer.
     *
     * @param blockNumber the block number
     */
    public void onBlockProofCreate(final long blockNumber) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats != null) {
            stats.markProofCreated(nanoClock.getAsLong());
        }
    }

    /**
     * Records when the block header item was written to the gRPC stream.
     *
     * @param blockNumber the block number
     * @param nanosTickStart the {@code nanoTime} when the send started
     * @param nanosTickEnd the {@code nanoTime} when the send completed
     */
    public void onBlockHeaderSend(final long blockNumber, final long nanosTickStart, final long nanosTickEnd) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats != null) {
            stats.markHeaderSent(nanosTickStart, nanosTickEnd);
        }
    }

    /**
     * Records when the block footer item was created.
     *
     * @param blockNumber the block number
     */
    public void onBlockFooterCreate(final long blockNumber) {
        if (!isEnabled) {
            return;
        }

        final BlockStats stats = blockStatistics.get(blockNumber);
        if (stats != null) {
            stats.markFooterCreated(nanoClock.getAsLong());
        }
    }

    private long toSecondTick(final long nanosTick) {
        final long nanos = nanosTick - initialNanosTick;
        return nanos / NANOS_PER_SECOND;
    }

    private ThroughputBucket getThroughputBucket(final long nanosTick) {
        final long second = toSecondTick(nanosTick);
        final ThroughputBucket bucket = throughputBuckets.get(second);
        if (bucket != null) {
            return bucket;
        }

        // a bucket is created at most ~once per second, so this guard is effectively free; the buckets can
        // only pile up if the gather task stopped draining them (e.g. the scheduler thread died), in which
        // case dropping them all is preferable to growing without bound
        if (throughputBuckets.size() > THROUGHPUT_BUCKETS_CAPACITY_HINT * 4) {
            log.warn(
                    "Throughput buckets are not being drained ({} accumulated); Clearing all buckets",
                    throughputBuckets.size());
            throughputBuckets.clear();
        }
        return throughputBuckets.computeIfAbsent(second, _ -> new ThroughputBucket(second));
    }

    // =================================================================================================================

    /** Periodic task that re-reads the feature flag, then gathers and logs the accumulated stats. */
    private class ObsGatherAndLogTask implements Runnable {
        @Override
        public void run() {
            try {
                refreshEnabledFlag();
                gatherAndLogObsData();
            } finally {
                scheduledExecutorService.schedule(new ObsGatherAndLogTask(), PERIOD_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Drains all data eligible for this window and logs the report at {@code INFO}. Nothing is logged
     * when obs is disabled or no data was recorded.
     */
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
        final long nanosTick = nanoClock.getAsLong();
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

    /**
     * Removes and returns all throughput buckets at or before the threshold second.
     *
     * @param thresholdSecondTick the newest second-tick (inclusive) eligible to be drained
     * @return the drained buckets
     */
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

    /**
     * Removes terminal blocks from {@link #blockStatistics} and aggregates the acked ones.
     *
     * @param nanosTick the current {@code nanoTime}, used to detect abandoned blocks
     * @param thresholdNanosTick blocks acked at or before this tick are eligible to aggregate
     * @return the aggregation of the acked blocks drained this cycle
     */
    private BlockStatsAggregation drainBlocks(final long nanosTick, final long thresholdNanosTick) {
        final BlockStatsAggregation aggregation = new BlockStatsAggregation();
        final Iterator<Map.Entry<Long, BlockStats>> it =
                blockStatistics.entrySet().iterator();

        while (it.hasNext()) {
            final BlockStats blockStats = it.next().getValue();
            // only blocks acked before the threshold are eligible; the ack is terminal, so by now
            // no other thread is still recording events for the block, and it is safe to aggregate
            if (blockStats.isAckedAtOrBefore(thresholdNanosTick)) {
                it.remove();
                aggregation.add(blockStats);
            } else if (blockStats.isUnackedOlderThan(nanosTick, ABANDONED_AFTER_NANOS)) {
                // never acked and too old: evict without aggregating (other threads may still be
                // mutating the block, so its probes must not be touched) and only count it
                it.remove();
                aggregation.markAbandoned();
            }
        }
        return aggregation;
    }

    private static String formatReport(final ThroughputSummary summary, final BlockStatsAggregation blocksAggregation) {
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
        output.append("      Created-PerSecond { (Unit:COUNT|Avg:").append(ObsUtils.format(summary.perSecond(summary.itemsCreated().numSamples))).append(")");
        output.append("(Unit:BYTES|Avg:").append(ObsUtils.format(summary.perSecond(summary.itemsCreated().sum))).append(") }\n");
        output.append("      Sent-Total { (Unit:COUNT|Sum:").append(summary.itemsSent().numSamples).append(")");
        output.append("(Unit:BYTES|Sum:").append(summary.itemsSent().sum).append(") }\n");
        output.append("      Sent-PerSecond { (Unit:COUNT|Avg:").append(ObsUtils.format(summary.perSecond(summary.itemsSent().numSamples))).append(")");
        output.append("(Unit:BYTES|Avg:").append(ObsUtils.format(summary.perSecond(summary.itemsSent().sum))).append(") }\n");
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

            return new ThroughputSummary(
                    numberOfSeconds, blocksOpened, blocksClosed, blocksAcked, itemsCreated, itemsSent);
        }

        BigDecimal perSecond(final BigInteger value) {
            return new BigDecimal(value).divide(BigDecimal.valueOf(numberOfSeconds), MATH_CONTEXT_10);
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds all lifecycle timestamps for a single block and owns the invariants over them: every
 * timestamp is recorded at most once (first write wins), with {@code -1} ({@link AtomicLong}) or
 * {@code null} ({@link AtomicReference}) as the "not yet recorded" sentinel. State is mutated only
 * through the {@code mark*}/{@code record*} methods and read only through the accessors below;
 * callers never touch the raw fields, so the sentinel contract stays internal to this class.
 *
 * <p>Magnitudes quoted in the span docs are from a sampled {@code hapiTestSmartContract} run (27
 * acked blocks) and are illustrative of typical behaviour, not guarantees.
 */
class BlockStats {
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

    BlockStats(final long initNanosTick) {
        this.initNanosTick = initNanosTick;
    }

    boolean markOpened(final long tick) {
        return openedNanosTick.compareAndSet(-1, tick);
    }

    boolean markClosed(final long tick) {
        return closedNanosTick.compareAndSet(-1, tick);
    }

    boolean markAcked(final long tick) {
        return ackedNanosTick.compareAndSet(-1, tick);
    }

    void markProofCreated(final long tick) {
        proofCreatedNanosTick.compareAndSet(-1, tick);
    }

    void markProofAdded(final long tick) {
        proofAddedNanosTick.compareAndSet(-1, tick);
    }

    void markFooterCreated(final long tick) {
        footerNanosTick.compareAndSet(-1, tick);
    }

    void markHeaderSent(final long startTick, final long endTick) {
        headerSentNanosTicks.compareAndSet(null, new StartAndEndTicks(startTick, endTick));
    }

    void markEndSent(final long startTick, final long endTick) {
        endSentNanosTicks.compareAndSet(null, new StartAndEndTicks(startTick, endTick));
    }

    /**
     * Records a newly-buffered item.
     *
     * @param index the item's index within the block
     * @param sizeInBytes the serialized size of the item in bytes
     * @param bufferedTick the {@code nanoTime} when the item was buffered
     * @return {@code true} if the item was newly added (the index was not already present)
     */
    boolean recordItem(final int index, final long sizeInBytes, final long bufferedTick) {
        return items.putIfAbsent(index, new BlockItemStats(sizeInBytes, bufferedTick)) == null;
    }

    /**
     * Marks the item at {@code index} as sent, if it is present and not already marked.
     *
     * @param index the item's index within the block
     * @param ticks the send window (start/end {@code nanoTime}) to record
     * @return the item's size in bytes if this call recorded the send, or {@code -1} otherwise
     * (item not found, or already marked as sent)
     */
    long markItemSent(final int index, final StartAndEndTicks ticks) {
        final BlockItemStats item = items.get(index);
        if (item != null && item.itemSendNanosTicks.compareAndSet(null, ticks)) {
            return item.itemSizeInBytes;
        }
        return -1;
    }

    /**
     * @param thresholdNanosTick the newest ack {@code nanoTime} (inclusive) considered eligible
     * @return {@code true} if the block was acked at or before {@code thresholdNanosTick}
     */
    boolean isAckedAtOrBefore(final long thresholdNanosTick) {
        final long acked = ackedNanosTick.get();
        return acked != -1 && acked <= thresholdNanosTick;
    }

    /**
     * @param nowNanosTick the current {@code nanoTime}
     * @param ageNanos the minimum age since init for the block to be considered abandoned
     * @return {@code true} if the block was never acked and was inited at least {@code ageNanos} ago
     */
    boolean isUnackedOlderThan(final long nowNanosTick, final long ageNanos) {
        return ackedNanosTick.get() == -1 && nowNanosTick - initNanosTick >= ageNanos;
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

    boolean hasOpened() {
        return openedNanosTick.get() != -1;
    }

    boolean hasClosed() {
        return closedNanosTick.get() != -1;
    }

    boolean hasProofCreated() {
        return proofCreatedNanosTick.get() != -1;
    }

    boolean hasProofAdded() {
        return proofAddedNanosTick.get() != -1;
    }

    boolean hasFooterCreated() {
        return footerNanosTick.get() != -1;
    }

    boolean hasHeaderSent() {
        return headerSentNanosTicks.get() != null;
    }

    boolean hasEndSent() {
        return endSentNanosTicks.get() != null;
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

    Statistics itemIdleStatistics() {
        return itemIdleProbe.aggregate();
    }

    Statistics itemSendLatencyStatistics() {
        return itemSendLatencyProbe.aggregate();
    }

    Statistics itemSizeStatistics() {
        return itemSizeProbe.aggregate();
    }

    long blockSize() {
        return blockSize;
    }

    long itemsNeverSent() {
        return itemsNeverSent;
    }

    int itemCount() {
        return items.size();
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds all lifecycle timestamps for a single block.
 * AtomicLong fields use {@code -1} as a sentinel meaning "not yet recorded".
 * AtomicReference fields use {@code null} as sentinel for the same purpose.
 */
class BlockStats {
    final long initNanosTick;
    final AtomicLong openedNanosTick = new AtomicLong(-1);
    final AtomicLong closedNanosTick = new AtomicLong(-1);
    final AtomicLong ackedNanosTick = new AtomicLong(-1);
    final AtomicLong proofCreatedNanosTick = new AtomicLong(-1);
    final AtomicLong proofAddedNanosTick = new AtomicLong(-1);
    final AtomicLong footerNanosTick = new AtomicLong(-1);
    final AtomicReference<StartAndEndTicks> headerSentNanosTicks = new AtomicReference<>();
    final AtomicReference<StartAndEndTicks> endSentNanosTicks = new AtomicReference<>();
    final ConcurrentMap<Integer, BlockItemStats> items = new ConcurrentHashMap<>();

    final StatisticsProbe itemIdleProbe = new StatisticsProbe("ItemIdle", ObsUnit.NANOS);
    final StatisticsProbe itemSendLatencyProbe = new StatisticsProbe("ItemSendLatency", ObsUnit.NANOS);
    final StatisticsProbe itemSizeProbe = new StatisticsProbe("ItemSize", ObsUnit.BYTES);

    long blockSize = 0;
    long itemsNeverSent = 0;

    BlockStats(final long initNanosTick) {
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

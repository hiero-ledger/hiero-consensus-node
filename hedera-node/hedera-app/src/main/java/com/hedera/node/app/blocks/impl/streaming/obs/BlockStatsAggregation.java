// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import java.util.List;

/** Aggregates per-block latency and per-item statistics across all blocks in a reporting window. */
class BlockStatsAggregation {
    final StatisticsProbe initToOpen = new StatisticsProbe("InitToOpen", ObsUnit.NANOS);
    final StatisticsProbe openToClose = new StatisticsProbe("OpenToClose", ObsUnit.NANOS);
    final StatisticsProbe openToEndSent = new StatisticsProbe("OpenToEndSent", ObsUnit.NANOS);
    final StatisticsProbe openToAck = new StatisticsProbe("OpenToAck", ObsUnit.NANOS);
    final StatisticsProbe closedToAck = new StatisticsProbe("ClosedToAck", ObsUnit.NANOS);
    final StatisticsProbe headerSendStartedToAck = new StatisticsProbe("HeaderSendStartedToAck", ObsUnit.NANOS);
    final StatisticsProbe headerSentToAck = new StatisticsProbe("HeaderSentToAck", ObsUnit.NANOS);
    final StatisticsProbe endSentToAck = new StatisticsProbe("EndSentToAck", ObsUnit.NANOS);
    final StatisticsProbe headerSentToEndSent = new StatisticsProbe("HeaderSentToEndSent", ObsUnit.NANOS);
    final StatisticsProbe openToProofAdded = new StatisticsProbe("OpenToProofAdded", ObsUnit.NANOS);
    final StatisticsProbe openToProofCreated = new StatisticsProbe("OpenToProofCreated", ObsUnit.NANOS);
    final StatisticsProbe footerCreatedToProofCreated =
            new StatisticsProbe("FooterCreatedToProofCreated", ObsUnit.NANOS);

    final StatisticsProbe blockSize = new StatisticsProbe("BlockSize", ObsUnit.BYTES);
    final StatisticsProbe itemsPerBlock = new StatisticsProbe("ItemsPerBlock", ObsUnit.COUNT);

    final CompositeStatistics itemIdleComposite = new CompositeStatistics(ObsUnit.NANOS);
    final CompositeStatistics itemSendLatencyComposite = new CompositeStatistics(ObsUnit.NANOS);
    final CompositeStatistics itemSizeComposite = new CompositeStatistics(ObsUnit.BYTES);
    long itemsNeverSent = 0;
    long blocksAggregated = 0;
    long blocksAbandoned = 0;

    void add(final BlockStats blockStats) {
        ++blocksAggregated;

        // computes the per-item probes and blockSize; must only ever run on the gather thread
        blockStats.aggregate();

        // a span gets a sample only when every tick it depends on was recorded; the availability
        // predicates keep the -1/null "not recorded" sentinel knowledge inside BlockStats, and skipping
        // an unavailable span avoids polluting the window with a garbage value
        if (blockStats.hasOpened()) {
            initToOpen.add(blockStats.initToOpen());
            openToAck.add(blockStats.openToAck());
            if (blockStats.hasClosed()) {
                openToClose.add(blockStats.openToClose());
            }
            if (blockStats.hasProofAdded()) {
                openToProofAdded.add(blockStats.openToProofAdded());
            }
            if (blockStats.hasProofCreated()) {
                openToProofCreated.add(blockStats.openToProofCreated());
            }
            if (blockStats.hasEndSent()) {
                openToEndSent.add(blockStats.openToEndSent());
            }
        }
        if (blockStats.hasClosed()) {
            closedToAck.add(blockStats.closedToAck());
        }
        if (blockStats.hasFooterCreated() && blockStats.hasProofCreated()) {
            footerCreatedToProofCreated.add(blockStats.footerCreatedToProofCreated());
        }
        if (blockStats.hasEndSent()) {
            endSentToAck.add(blockStats.endSentToAck());
        }
        if (blockStats.hasHeaderSent()) {
            headerSendStartedToAck.add(blockStats.headerSendStartedToAck());
            headerSentToAck.add(blockStats.headerSentToAck());
            if (blockStats.hasEndSent()) {
                headerSentToEndSent.add(blockStats.headerSentToEndSent());
            }
        }

        itemIdleComposite.add(blockStats.itemIdleStatistics());
        itemSendLatencyComposite.add(blockStats.itemSendLatencyStatistics());
        itemSizeComposite.add(blockStats.itemSizeStatistics());
        itemsNeverSent += blockStats.itemsNeverSent();

        blockSize.add(blockStats.blockSize());
        itemsPerBlock.add(blockStats.itemCount());
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

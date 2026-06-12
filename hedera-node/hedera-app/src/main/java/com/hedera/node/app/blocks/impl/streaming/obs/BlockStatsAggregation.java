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

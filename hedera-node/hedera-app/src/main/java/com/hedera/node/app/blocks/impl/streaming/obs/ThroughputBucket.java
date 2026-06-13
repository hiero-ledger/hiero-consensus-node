// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import java.util.concurrent.atomic.LongAdder;

/** Counts block and item events that occurred within a single wall-clock second. */
class ThroughputBucket {
    final long secondTick;
    final BasicProbe itemsCreated = new BasicProbe("ItemsCreated", ObsUnit.COUNT);
    final BasicProbe itemsSent = new BasicProbe("ItemsSent", ObsUnit.COUNT);
    final LongAdder blocksOpened = new LongAdder();
    final LongAdder blocksClosed = new LongAdder();
    final LongAdder blocksAcked = new LongAdder();

    ThroughputBucket(final long secondTick) {
        this.secondTick = secondTick;
    }
}

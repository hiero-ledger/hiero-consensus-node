// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import java.util.concurrent.atomic.AtomicReference;

/** Holds the buffered timestamp and send timing for a single block item. */
class BlockItemStats {
    final long itemSizeInBytes;
    final long itemBufferedNanosTick;
    final AtomicReference<StartAndEndTicks> itemSendNanosTicks = new AtomicReference<>();

    BlockItemStats(final long itemSizeInBytes, final long itemBufferedNanosTick) {
        this.itemSizeInBytes = itemSizeInBytes;
        this.itemBufferedNanosTick = itemBufferedNanosTick;
    }
}

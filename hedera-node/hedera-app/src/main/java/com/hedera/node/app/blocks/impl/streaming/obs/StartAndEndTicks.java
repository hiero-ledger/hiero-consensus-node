// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

/** A pair of {@code System.nanoTime()} values bracketing the start and end of an operation. */
record StartAndEndTicks(long start, long end) {
    long diff() {
        return end - start;
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BlockSizeIngestGateTest {
    @Test
    void expiresWithoutAnotherBlockAndReopensOnNewBlock() {
        final var now = new AtomicLong(123);
        final var gate = new BlockSizeIngestGate(now::get);
        final var maxAge = Duration.ofSeconds(2);
        assertFalse(gate.shouldReject(maxAge));
        gate.onBlockSizeLimitReached();
        assertTrue(gate.shouldReject(maxAge));
        now.addAndGet(maxAge.toNanos() - 1);
        assertTrue(gate.shouldReject(maxAge));
        now.incrementAndGet();
        gate.onBlockSizeLimitReached();
        assertFalse(gate.shouldReject(maxAge));
        gate.onBlockStarted();
        gate.onBlockSizeLimitReached();
        assertTrue(gate.shouldReject(maxAge));
        gate.onBlockStarted();
        assertFalse(gate.shouldReject(maxAge));
    }

    @Test
    void zeroDisablesGate() {
        final var gate = new BlockSizeIngestGate(() -> 0L);
        gate.onBlockSizeLimitReached();
        assertFalse(gate.shouldReject(Duration.ZERO));
    }

    @Test
    void handlesMonotonicClockWraparound() {
        final var now = new AtomicLong(Long.MAX_VALUE - 5);
        final var gate = new BlockSizeIngestGate(now::get);
        gate.onBlockSizeLimitReached();
        now.addAndGet(10);
        assertTrue(gate.shouldReject(Duration.ofNanos(11)));
        assertFalse(gate.shouldReject(Duration.ofNanos(10)));
    }
}

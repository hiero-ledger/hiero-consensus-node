// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.extensions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Checks the order in which {@link RingBuffer} hands out its items.
 */
class RingBufferTest {

    private final AtomicInteger created = new AtomicInteger();

    @Test
    void createsEachItemOnce() {
        new RingBuffer<>(3, created::getAndIncrement);
        assertThat(created).hasValue(3);
    }

    @Test
    void advanceReturnsTheItemsInRingOrder() {
        final RingBuffer<Integer> ring = new RingBuffer<>(3, created::getAndIncrement);
        assertThat(ring.advance()).isZero();
        assertThat(ring.advance()).isEqualTo(1);
        assertThat(ring.advance()).isEqualTo(2);
        assertThat(ring.advance()).as("the ring goes round").isZero();
        assertThat(created).as("items are reused").hasValue(3);
    }

    @Test
    void oldestIsTheItemTheNextAdvanceReturns() {
        final RingBuffer<Integer> ring = new RingBuffer<>(3, created::getAndIncrement);
        for (int i = 0; i < 7; i++) {
            final Integer oldest = ring.oldest();
            assertThat(ring.advance()).as("advance " + i).isSameAs(oldest);
        }
    }

    @Test
    void ringOfOneAlwaysReturnsTheSameItem() {
        final RingBuffer<Integer> ring = new RingBuffer<>(1, created::getAndIncrement);
        assertThat(ring.advance()).isZero();
        assertThat(ring.oldest()).isZero();
        assertThat(ring.advance()).isZero();
    }

    @Test
    void sizeBelowOneThrows() {
        assertThatThrownBy(() -> new RingBuffer<>(0, created::getAndIncrement))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.jctools.queues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MpscVarHandleArrayQueueTest {

    private static <E> MessagePassingQueue<E> newQueue(final int capacity) {
        return new MpscVarHandleArrayQueue<>(capacity);
    }

    private static final int PRODUCERS = 16;
    private static final int PER_PRODUCER = 50_000;
    private static final long STRESS_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(45);

    @ParameterizedTest
    @ValueSource(ints = {2, 64})
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Many producers and one consumer: no loss, no duplicates, per-producer order preserved")
    void multiProducerStress(final int capacity) throws Exception {
        final MessagePassingQueue<Long> queue = newQueue(capacity);
        final long deadline = System.nanoTime() + STRESS_DEADLINE_NANOS;
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService executor = Executors.newFixedThreadPool(PRODUCERS);
        try {
            final List<Future<?>> producers = new ArrayList<>();
            for (int p = 0; p < PRODUCERS; p++) {
                final long producerId = p;
                producers.add(executor.submit(() -> {
                    start.await();
                    for (long seq = 0; seq < PER_PRODUCER; seq++) {
                        final Long value = (producerId << 32) | seq;
                        while (!queue.offer(value)) {
                            if (System.nanoTime() - deadline >= 0) {
                                throw new IllegalStateException("producer " + producerId + " timed out at " + seq);
                            }
                            Thread.yield();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();

            // This (the test) thread is the one and only consumer.
            final long[] nextSeq = new long[PRODUCERS];
            final long total = (long) PRODUCERS * PER_PRODUCER;
            long received = 0;
            while (received < total) {
                final Long value = queue.poll();
                if (value == null) {
                    assertThat(System.nanoTime() - deadline)
                            .as("consumer timed out")
                            .isNegative();
                    Thread.onSpinWait();
                    continue;
                }
                final int producerId = (int) (value >>> 32);
                final long seq = value & 0xFFFFFFFFL;
                // contiguous + increasing per producer also rules out duplicates and loss
                assertThat(seq)
                        .as("producer %d, message %d", producerId, received)
                        .isEqualTo(nextSeq[producerId]);
                nextSeq[producerId]++;
                received++;
            }

            for (final Future<?> producer : producers) {
                producer.get(10, TimeUnit.SECONDS);
            }
            for (int p = 0; p < PRODUCERS; p++) {
                assertThat(nextSeq[p]).as("producer %d count", p).isEqualTo(PER_PRODUCER);
            }
            assertThat(queue.poll()).isNull();
            assertThat(queue.isEmpty()).isTrue();
            assertThat(queue.size()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @CsvSource({"1,1", "2,2", "3,4", "5,8", "16,16", "10000,16384"})
    @DisplayName("Capacity is the next power of two and exactly that many offers succeed")
    void capacityBoundary(final int requested, final int expectedCapacity) {
        final MessagePassingQueue<Integer> queue = newQueue(requested);
        assertThat(queue.capacity()).isEqualTo(expectedCapacity);

        for (int i = 0; i < expectedCapacity; i++) {
            assertThat(queue.offer(i)).as("offer %d of %d", i, expectedCapacity).isTrue();
        }
        assertThat(queue.offer(-1)).as("offer beyond capacity").isFalse();
        assertThat(queue.size()).isEqualTo(expectedCapacity);

        assertThat(queue.poll()).isEqualTo(0);
        assertThat(queue.offer(expectedCapacity)).as("offer after one poll").isTrue();
        assertThat(queue.offer(-1)).as("second offer after one poll").isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 8})
    @DisplayName("Wrap-around keeps FIFO order and poll returns null exactly when empty")
    void wrapAroundSingleThreaded(final int requested) {
        final MessagePassingQueue<Integer> queue = newQueue(requested);
        final int capacity = queue.capacity();
        final int cycles = capacity * 50 + 7;

        assertThat(queue.poll()).isNull();
        int nextOffer = 0;
        int nextPoll = 0;
        for (int cycle = 0; cycle < cycles; cycle++) {
            // fill a varying number of elements (1..capacity) so the indices drift relative to the buffer
            final int batch = 1 + (cycle % capacity);
            for (int i = 0; i < batch; i++) {
                assertThat(queue.offer(nextOffer++)).isTrue();
            }
            for (int i = 0; i < batch; i++) {
                assertThat(queue.poll()).isEqualTo(nextPoll++);
            }
            assertThat(queue.poll()).as("empty after draining batch %d", cycle).isNull();
            assertThat(queue.isEmpty()).isTrue();
        }
        assertThat(nextPoll).isEqualTo(nextOffer);
    }

    @Test
    @DisplayName("offer(null) throws NullPointerException")
    void offerNullThrows() {
        final MessagePassingQueue<Object> queue = newQueue(4);
        assertThatThrownBy(() -> queue.offer(null)).isInstanceOf(NullPointerException.class);
        assertThat(queue.isEmpty()).isTrue();
    }
}

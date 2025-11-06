// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.test.fixtures.ThreadUtils.awaitLatch;
import static org.hiero.metrics.test.fixtures.ThreadUtils.joinThread;
import static org.hiero.metrics.test.fixtures.ThreadUtils.runConcurrentAndWait;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

public class AppendArrayTest {

    @Nested
    class SingleThread {

        @Test
        public void testEmpty() {
            AppendArray<String> array = new AppendArray<>(2);

            assertThat(array.size()).isEqualTo(0);

            assertThatThrownBy(() -> array.get(0)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> array.get(-1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> array.get(1)).isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        public void testReadWithoutReadyToRead() {
            AppendArray<Integer> array = new AppendArray<>(2);
            array.add(1);

            assertThat(array.size()).isEqualTo(0);
            assertThatThrownBy(() -> array.get(0)).isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        public void testReadAfterReadyToRead() {
            AppendArray<String> array = new AppendArray<>(2);
            array.add("one");

            int size = array.readyToRead();

            assertThat(size).isEqualTo(1);
            assertThat(array.size()).isEqualTo(size);
            assertThat(array.get(0)).isEqualTo("one");
        }

        @Test
        public void testResize() {
            AppendArray<String> array = new AppendArray<>(2);
            array.add("one");
            array.add("two");
            array.add("three");

            int size = array.readyToRead();

            assertThat(size).isEqualTo(3);
            assertThat(array.size()).isEqualTo(size);

            assertThat(array.get(0)).isEqualTo("one");
            assertThat(array.get(1)).isEqualTo("two");
            assertThat(array.get(2)).isEqualTo("three");
        }

        @Test
        public void testSubsequentReadsWithoutReadyToRead() {
            AppendArray<String> array = new AppendArray<>(10);
            array.add("one");
            array.add("two");

            int size = array.readyToRead();
            array.add("three");

            assertThat(size).isEqualTo(2);
            assertThat(array.size()).isEqualTo(size);

            assertThat(array.get(0)).isEqualTo("one");
            assertThat(array.get(1)).isEqualTo("two");
        }

        @Test
        public void testSubsequentReadsWithReadyToRead() {
            AppendArray<String> array = new AppendArray<>(10);
            array.add("one");
            array.add("two");

            array.readyToRead();
            array.add("three");

            int size = array.readyToRead();
            assertThat(size).isEqualTo(3);
            assertThat(array.size()).isEqualTo(size);

            assertThat(array.get(0)).isEqualTo("one");
            assertThat(array.get(1)).isEqualTo("two");
            assertThat(array.get(2)).isEqualTo("three");
        }
    }

    @Nested
    class MultiThreaded {

        @Test
        public void testSubsequentReadsWithoutReadyToRead() throws InterruptedException {
            final AppendArray<String> array = new AppendArray<>(10);
            final AtomicBoolean readThreadDone = new AtomicBoolean(false);

            final CountDownLatch writerStartedLatch = new CountDownLatch(1);

            Thread writerThread = new Thread(() -> {
                writerStartedLatch.countDown();
                while (!readThreadDone.get()) {
                    array.add(UUID.randomUUID().toString());
                }
            });
            writerThread.start();

            awaitLatch(writerStartedLatch, "Writer thread did not start in time");
            Thread.sleep(20); // sleep some time to allow some writes

            int size1 = array.readyToRead();
            assertThat(size1).isGreaterThan(0);
            final String[] snapshot = new String[size1];
            for (int i = 0; i < size1; i++) {
                snapshot[i] = array.get(i);
            }

            Thread.sleep(20); // sleep some time to allow more writes

            int size2 = array.size();
            // very size and elements are the same as before since we did not call readyToRead again
            assertThat(size2).isEqualTo(size1);
            for (int i = 0; i < size2; i++) {
                assertThat(array.get(i)).isEqualTo(snapshot[i]);
            }

            readThreadDone.set(true);
            joinThread(writerThread, "Writer thread did not terminate in time after setting done flag");
        }

        @Test
        public void testConcurrentAddsAndSingleRead() throws InterruptedException {
            final int threadCount = 10;
            final int itemsPerThread = 10000;
            final AppendArray<Integer> array = new AppendArray<>(10);

            runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIndex -> () -> {
                for (int j = 0; j < itemsPerThread; j++) {
                    array.add(threadIndex * itemsPerThread + j);
                }
            });

            int size = array.readyToRead();
            assertThat(size).isEqualTo(threadCount * itemsPerThread);

            boolean[] seen = new boolean[threadCount * itemsPerThread];
            for (int i = 0; i < size; i++) {
                int value = array.get(i);
                assertThat(value).isBetween(0, seen.length - 1);
                seen[value] = true;
            }

            for (int i = 0; i < seen.length; i++) {
                assertThat(seen[i]).as("Value is not seen: " + i).isTrue();
            }
        }
    }
}

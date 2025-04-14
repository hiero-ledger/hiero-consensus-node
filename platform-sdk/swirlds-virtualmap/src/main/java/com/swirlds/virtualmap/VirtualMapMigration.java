// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import com.swirlds.base.utility.Pair;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.internal.RecordAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.model.utility.interrupt.InterruptableConsumer;

/**
 * A utility for migrating data within a virtual map from one format to another.
 */
public final class VirtualMapMigration {

    private VirtualMapMigration() {}

    private static final int QUEUE_CAPACITY = 10_000;

    private static final String COMPONENT_NAME = "virtual-map-migration";

    /**
     * Extract all key-value pairs from a virtual map and pass it to a handler in a deterministic order.
     *
     * @param threadManager
     * 		responsible for creating and managing threads
     * @param source
     * 		a virtual map to read from, will not be modified by this method
     * @param threadCount
     * 		the number of threads used for reading from the original map
     * @param <K>
     * 		the type of the key
     * @param <V>
     * 		the type of the value
     */
    public static <K extends VirtualKey, V extends VirtualValue> void extractVirtualMapData(
            final ThreadManager threadManager,
            final VirtualMap<K, V> source,
            final InterruptableConsumer<Pair<K, V>> handler,
            final int threadCount)
            throws InterruptedException {

        final long firstLeafPath = source.getState().getFirstLeafPath();
        final long lastLeafPath = source.getState().getLastLeafPath();
        if (firstLeafPath == Path.INVALID_PATH || lastLeafPath == Path.INVALID_PATH) {
            return;
        }

        final RecordAccessor<K, V> recordAccessor = source.getRoot().getRecords();

        final long size = source.size();

        // A collection of threads iterate over the map. Each thread writes into its own output queue.
        final List<Thread> threads = new ArrayList<>(threadCount);
        final List<BlockingQueue<Pair<K, V>>> threadQueues = new ArrayList<>(threadCount);

        for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {

            final BlockingQueue<Pair<K, V>> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
            threadQueues.add(queue);

            // Java only allows final values to be passed into a lambda
            final int index = threadIndex;

            threads.add(new ThreadConfiguration(threadManager)
                    .setComponent(COMPONENT_NAME)
                    .setThreadName("reader-" + threadCount)
                    .setInterruptableRunnable(() -> {
                        for (long path = firstLeafPath + index; path <= lastLeafPath; path += threadCount) {
                            final VirtualLeafRecord<K, V> leafRecord = recordAccessor.findLeafRecord(path, false);
                            queue.put(Pair.of(leafRecord.getKey(), leafRecord.getValue()));
                        }
                    })
                    .build(true));
        }

        // Buffers for reading from the thread output queues.
        final List<List<Pair<K, V>>> buffers = new ArrayList<>(threadCount);
        final List<Integer> bufferIndices = new ArrayList<>(threadCount);
        for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {
            buffers.add(new ArrayList<>(QUEUE_CAPACITY));
            bufferIndices.add(0);
        }

        // Aggregate values from queues and pass them to the handler.
        for (int index = 0; index < size; index++) {
            try {

                final int queueIndex = index % threadCount;

                final BlockingQueue<Pair<K, V>> queue = threadQueues.get(queueIndex);
                final List<Pair<K, V>> buffer = buffers.get(queueIndex);

                // Fill the buffer if needed
                int bufferIndex = bufferIndices.get(queueIndex);
                while (buffer.size() <= bufferIndex) {
                    buffer.clear();
                    queue.drainTo(buffer, QUEUE_CAPACITY);
                    bufferIndex = 0;
                }

                handler.accept(buffer.get(bufferIndex));

                bufferIndices.set(queueIndex, bufferIndex + 1);

            } catch (final InterruptedException e) {
                // If we are interrupted, stop all of the reader threads
                threads.forEach(Thread::interrupt);
                throw e;
            }
        }
    }

    /**
     * Extract all key-value pairs from a virtual map and pass it to a handler concurrently.
     *
     * @param threadManager
     * 		responsible for creating and managing threads
     * @param source
     * 		a virtual map to read from, will not be modified by this method
     * @param threadCount
     * 		the number of threads used for reading from the original map
     * @param <K>
     * 		the type of the key
     * @param <V>
     * 		the type of the value
     */
    public static <K extends VirtualKey, V extends VirtualValue> void extractVirtualMapDataC(
            final ThreadManager threadManager,
            final VirtualMap<K, V> source,
            final InterruptableConsumer<Pair<K, V>> handler,
            final int threadCount)
            throws InterruptedException {

        final long firstLeafPath = source.getState().getFirstLeafPath();
        final long lastLeafPath = source.getState().getLastLeafPath();
        if (firstLeafPath == Path.INVALID_PATH || lastLeafPath == Path.INVALID_PATH) {
            return;
        }

        final RecordAccessor<K, V> recordAccessor = source.getRoot().getRecords();

        // A collection of threads iterate over the map. Each thread writes into its own output queue.
        final List<Thread> threads = new ArrayList<>(threadCount);
        final AtomicReference<Throwable> throwable = new AtomicReference<>();

        for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {

            final long firstPath = firstLeafPath + threadIndex;

            threads.add(new ThreadConfiguration(threadManager)
                    .setComponent(COMPONENT_NAME)
                    .setThreadName("reader-" + threadCount)
                    .setInterruptableRunnable(() -> {
                        try {
                            for (long path = firstPath; path <= lastLeafPath; path += threadCount) {
                                final VirtualLeafRecord<K, V> leafRecord = recordAccessor.findLeafRecord(path, false);
                                handler.accept(Pair.of(leafRecord.getKey(), leafRecord.getValue()));
                            }
                        } catch (final Throwable t) {
                            if (throwable.compareAndSet(null, t)) {
                                threads.forEach(Thread::interrupt);
                            }
                        }
                    })
                    .build(true));
        }

        for (Thread thread : threads) {
            thread.join();
        }

        if (throwable.get() != null) {
            throw new InterruptedException(throwable.get().toString());
        }
    }
}

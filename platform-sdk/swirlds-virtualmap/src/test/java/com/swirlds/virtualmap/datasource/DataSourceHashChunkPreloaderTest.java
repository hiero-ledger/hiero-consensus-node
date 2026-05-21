// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.datasource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DataSourceHashChunkPreloaderTest {

    private static final int CHUNK_HEIGHT = 2;

    private VirtualDataSource dataSource;
    private DataSourceHashChunkPreloader preloader;

    @BeforeEach
    void setUp() {
        dataSource = mock(VirtualDataSource.class);
        when(dataSource.getHashChunkHeight()).thenReturn(CHUNK_HEIGHT);
        preloader = new DataSourceHashChunkPreloader(dataSource);
    }

    @Test
    @DisplayName("Constructor should throw NullPointerException when data source is null")
    void testNulLDatasourceThrows() {
        assertThrows(
                NullPointerException.class,
                () -> new DataSourceHashChunkPreloader(null),
                "A null data source should produce an NPE");
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 3L, 4L, 5L})
    @DisplayName("apply() returns the same cached instance on repeated calls for the same path {0}")
    void applyReturnsSameInstanceForSamePath(long chunkPath) throws IOException {
        final VirtualHashChunk first = preloader.apply(chunkPath);
        final VirtualHashChunk second = preloader.apply(chunkPath);

        assertNotNull(first);
        assertSame(first, second, "Expected the same cached instance to be returned on repeated apply() calls");
        // loadHashChunk must be called only once — the second apply() is a cache hit
        final long chunkId = VirtualHashChunk.chunkPathToChunkId(chunkPath, CHUNK_HEIGHT);
        verify(dataSource, times(1)).loadHashChunk(chunkId);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 3L, 4L, 5L})
    @DisplayName("apply() returns a new instance after clearCache() is called")
    void applyReturnsNewInstanceAfterClearCache(long chunkPath) throws IOException {
        final long chunkId = VirtualHashChunk.chunkPathToChunkId(chunkPath, CHUNK_HEIGHT);
        final VirtualHashChunk first = preloader.apply(chunkPath);
        assertNotNull(first);

        preloader.clearCache(chunkId);

        final VirtualHashChunk second = preloader.apply(chunkPath);
        assertNotNull(second);
        assertNotSame(first, second, "Expected a new instance to be created after clearCache()");
        // loadHashChunk must be called twice: once for each cache miss
        verify(dataSource, times(2)).loadHashChunk(chunkId);
    }

    @Test
    @DisplayName(
            "Concurrent apply() calls for the same path return the same instance and load from datasource only once")
    void concurrentApplyReturnsSameInstance() throws Exception {
        final int threadCount = 50;
        final long chunkPath = 0L;
        final long chunkId = 0L;

        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(threadCount);
        final VirtualHashChunk[] results = new VirtualHashChunk[threadCount];

        final List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            threads.add(new Thread(() -> {
                try {
                    startLatch.await(); // hold all threads until released simultaneously
                    results[idx] = preloader.apply(chunkPath);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        threads.forEach(Thread::start);
        startLatch.countDown(); // release all threads simultaneously
        assertTrue(doneLatch.await(1, TimeUnit.SECONDS), "Not all threads completed in time");

        final VirtualHashChunk expected = results[0];
        assertNotNull(expected);
        for (final VirtualHashChunk result : results) {
            assertSame(expected, result, "All concurrent apply() calls should return the same cached instance");
        }
        // ConcurrentHashMap.computeIfAbsent is atomic per key, so loadHashChunk must be called exactly once
        verify(dataSource, times(1)).loadHashChunk(chunkId);
    }
}

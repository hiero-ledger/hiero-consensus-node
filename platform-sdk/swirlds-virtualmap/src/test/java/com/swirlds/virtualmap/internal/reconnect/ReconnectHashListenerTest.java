// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.CONFIGURATION;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.VIRTUAL_MAP_CONFIG;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.DataSourceHashChunkPreloader;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.hash.VirtualHasher;
import com.swirlds.virtualmap.internal.merkle.VirtualMapStatistics;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.datasource.InMemoryBuilder;
import java.io.IOException;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReconnectHashListenerTest {

    @Test
    @DisplayName("Null flusher throws")
    void nullFlusherThrows() {
        assertThrows(
                NullPointerException.class,
                () -> new ReconnectHashListener(null, mock(DataSourceHashChunkPreloader.class)),
                "A null flusher should produce an NPE");
    }

    @Test
    @DisplayName("Null hash chunk preloader throws")
    void nullHashChunkPreloaderThrows() {
        assertThrows(
                NullPointerException.class,
                () -> new ReconnectHashListener(mock(ReconnectHashLeafFlusher.class), null),
                "A null hash chunk preloader should produce an NPE");
    }

    @Test
    @DisplayName("Valid configurations create an instance")
    void ableToCreateInstance() {
        final ReconnectHashLeafFlusher flusher = mock(ReconnectHashLeafFlusher.class);
        final VirtualDataSource dataSource = mock(VirtualDataSource.class);
        when(dataSource.getHashChunkHeight()).thenReturn(6);
        final DataSourceHashChunkPreloader preloader = new DataSourceHashChunkPreloader(dataSource);

        // should not throw any exception
        new ReconnectHashListener(flusher, preloader);
    }

    @Test
    @DisplayName("onHashChunkHashed() calls mocks")
    void onHashChunkHashedWithMocks() {
        final long chunkId = 123L;
        final VirtualHashChunk chunk = mock(VirtualHashChunk.class);
        when(chunk.getChunkId()).thenReturn(chunkId);

        final ReconnectHashLeafFlusher flusher = mock(ReconnectHashLeafFlusher.class);
        final DataSourceHashChunkPreloader chunkPreloader = mock(DataSourceHashChunkPreloader.class);

        final ReconnectHashListener hashListener = new ReconnectHashListener(flusher, chunkPreloader);
        hashListener.onHashChunkHashed(chunk);

        verify(chunk).getChunkId();
        verify(flusher).updateHashChunk(chunk);
        verify(chunkPreloader).clearCache(chunkId);

        verifyNoMoreInteractions(flusher, chunkPreloader, chunk);
    }

    /**
     * This class is for testing with real objects and without any mocks
     */
    @Nested
    class NoMocks {

        @ParameterizedTest
        @ValueSource(ints = {2, 10, 100, 1000, 10_000, 100_000, 1_000_000})
        @DisplayName("Chunks are flushed during hashing")
        void chunksAreFlushedDuringHashing(int size) throws IOException {
            final VirtualDataSource ds = new InMemoryBuilder().build("test", null, true, false);

            final VirtualMapStatistics statistics = new VirtualMapStatistics("test");
            final int hashChunkHeight = ds.getHashChunkHeight();
            final ReconnectHashLeafFlusher flusher =
                    new ReconnectHashLeafFlusher(ds, VIRTUAL_MAP_CONFIG.reconnectFlushInterval(), statistics);

            // 100 leaves would have firstLeafPath = 99, lastLeafPath = 198
            final int first = size - 1;
            final int last = 2 * size - 2;

            flusher.init(first, last);

            DataSourceHashChunkPreloader hashChunkPreloader = new DataSourceHashChunkPreloader(ds);
            final ReconnectHashListener listener = new ReconnectHashListener(flusher, hashChunkPreloader);
            final VirtualHasher hasher = new VirtualHasher(CONFIGURATION.getConfigData(VirtualMapConfig.class));

            try {
                hasher.hash(
                        hashChunkHeight,
                        hashChunkPreloader,
                        LongStream.range(first, last + 1).mapToObj(this::leaf).iterator(),
                        first,
                        last,
                        listener);

                flusher.finish();

                long lastChunkId = VirtualHashChunk.lastChunkIdForPaths(last, hashChunkHeight);
                for (long chunkId = 0; chunkId <= lastChunkId; chunkId++) {
                    final VirtualHashChunk chunk = ds.loadHashChunk(chunkId);
                    assertNotNull(chunk);
                }
                assertNull(
                        ds.loadHashChunk(lastChunkId + 1),
                        "There should not be any chunks with chunk id greater than the last chunk id");
            } finally {
                hasher.shutdown();
            }
        }

        @SuppressWarnings("rawtypes")
        private VirtualLeafBytes leaf(long path) {
            return new VirtualLeafBytes(path, TestKey.longToKey(path), new TestValue(path).toBytes());
        }
    }
}

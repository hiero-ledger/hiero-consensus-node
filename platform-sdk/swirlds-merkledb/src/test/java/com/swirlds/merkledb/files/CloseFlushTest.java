// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.test.fixtures.ExampleFixedValue;
import com.swirlds.merkledb.test.fixtures.ExampleLongKey;
import com.swirlds.merkledb.test.fixtures.TestType;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * This is a regression test for swirlds/swirlds-platform/issues/6151, but
 * it can be used to find many different issues with VirtualMap.
 *
 * <p>The test creates a virtual map and makes its copies in a loop, until it gets flushed to
 * disk. Right after a flush is started, the last map is released, which triggers virtual
 * pipeline shutdown. The test then makes sure the flush completes without exceptions.
 */
public class CloseFlushTest {

    @BeforeAll
    public static void setup() throws IOException {
        Configurator.setRootLevel(Level.WARN);
    }

    @AfterAll
    public static void cleanUp() {
        Configurator.reconfigure();
    }

    @Test
    public void closeFlushTest() throws Exception {
        final int count = 10000;
        final ExecutorService exec = Executors.newSingleThreadExecutor();
        final AtomicReference<Exception> exception = new AtomicReference<>();
        final Path tmpFileDir = LegacyTemporaryFileBuilder.buildTemporaryFile(CONFIGURATION);
        for (int j = 0; j < 100; j++) {
            final Path storeDir = tmpFileDir.resolve("closeFlushTest-" + j);
            final VirtualDataSource dataSource =
                    TestType.long_fixed.dataType().createDataSource(storeDir, "closeFlushTest", count, 0, false, true);
            // Create a custom data source builder, which creates a custom data source to capture
            // all exceptions happened in saveRecords()
            final VirtualDataSourceBuilder builder = new CustomDataSourceBuilder(dataSource, exception, CONFIGURATION);
            VirtualMap map = new VirtualMap("closeFlushTest", builder, CONFIGURATION);
            for (int i = 0; i < count; i++) {
                final Bytes key = ExampleLongKey.longToKey(i);
                final ExampleFixedValue value = new ExampleFixedValue(i);
                map.put(key, value, ExampleFixedValue.CODEC);
            }
            VirtualMap copy;
            final CountDownLatch shutdownLatch = new CountDownLatch(1);
            for (int i = 0; i < 100; i++) {
                copy = map.copy();
                map.release();
                map = copy;
            }
            copy = map.copy();
            map.enableFlush();
            final VirtualMap lastMap = map;
            final Future<?> job = exec.submit(() -> {
                try {
                    Thread.sleep(new Random().nextInt(100));
                    lastMap.release();
                } catch (final Exception z) {
                    throw new RuntimeException(z);
                } finally {
                    shutdownLatch.countDown();
                }
            });
            copy.release();
            shutdownLatch.await();
            if (exception.get() != null) {
                exception.get().printStackTrace(System.err);
                break;
            }
            job.get();
        }
        Assertions.assertNull(exception.get(), "No exceptions expected, but caught " + exception.get());
    }

    public static class CustomDataSourceBuilder extends MerkleDbDataSourceBuilder {

        private VirtualDataSource delegate = null;
        private AtomicReference<Exception> exceptionSink = null;

        // Provided for deserialization
        public CustomDataSourceBuilder() {
            super(CONFIGURATION);
        }

        public CustomDataSourceBuilder(
                final VirtualDataSource delegate,
                AtomicReference<Exception> sink,
                final @NonNull Configuration configuration) {
            super(configuration);
            this.delegate = delegate;
            this.exceptionSink = sink;
        }

        @Override
        public long getClassId() {
            return super.getClassId() + 1;
        }

        @NonNull
        @Override
        public VirtualDataSource build(
                final String label,
                @Nullable final Path sourceDir,
                final boolean compactionEnabled,
                final boolean offlineUse) {
            return new VirtualDataSource() {
                @Override
                public void close(boolean keepData) throws IOException {
                    delegate.close(keepData);
                }

                @Override
                public void saveRecords(
                        final long firstLeafPath,
                        final long lastLeafPath,
                        @NonNull final Stream<VirtualHashRecord> pathHashRecordsToUpdate,
                        @NonNull final Stream<VirtualLeafBytes> leafRecordsToAddOrUpdate,
                        @NonNull final Stream<VirtualLeafBytes> leafRecordsToDelete,
                        final boolean isReconnectContext) {
                    try {
                        delegate.saveRecords(
                                firstLeafPath,
                                lastLeafPath,
                                pathHashRecordsToUpdate,
                                leafRecordsToAddOrUpdate,
                                leafRecordsToDelete,
                                isReconnectContext);
                    } catch (final Exception e) {
                        exceptionSink.set(e);
                    }
                }

                @Override
                public VirtualLeafBytes loadLeafRecord(final Bytes key) throws IOException {
                    return delegate.loadLeafRecord(key);
                }

                @Override
                public VirtualLeafBytes loadLeafRecord(final long path) throws IOException {
                    return delegate.loadLeafRecord(path);
                }

                @Override
                public long findKey(final Bytes key) throws IOException {
                    return delegate.findKey(key);
                }

                @Override
                public Hash loadHash(final long path) throws IOException {
                    return delegate.loadHash(path);
                }

                @Override
                public void snapshot(final Path snapshotDirectory) throws IOException {
                    delegate.snapshot(snapshotDirectory);
                }

                @Override
                public void copyStatisticsFrom(final VirtualDataSource that) {
                    delegate.copyStatisticsFrom(that);
                }

                @Override
                public void registerMetrics(final Metrics metrics) {
                    delegate.registerMetrics(metrics);
                }

                public long getFirstLeafPath() {
                    return delegate.getFirstLeafPath();
                }

                public long getLastLeafPath() {
                    return delegate.getLastLeafPath();
                }

                @Override
                public void enableBackgroundCompaction() {
                    delegate.enableBackgroundCompaction();
                }

                @Override
                public void stopAndDisableBackgroundCompaction() {
                    delegate.stopAndDisableBackgroundCompaction();
                }
            };
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.common.test.fixtures.io.ResourceLoader.loadLog4jContext;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.merkle.synchronization.task.InternalDataLesson;
import com.swirlds.common.merkle.synchronization.task.LeafDataLesson;
import com.swirlds.common.merkle.synchronization.task.Lesson;
import com.swirlds.common.merkle.synchronization.task.QueryResponse;
import com.swirlds.common.merkle.utility.SerializableLong;
import com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.SerializablePublicKey;
import org.hiero.consensus.model.event.CesEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.reconnect.config.ReconnectConfig;
import org.hiero.consensus.reconnect.config.ReconnectConfig_;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

public abstract class VirtualMapReconnectTestBase {

    protected static final Bytes A_KEY = TestKey.charToKey('a');
    protected static final Bytes B_KEY = TestKey.charToKey('b');
    protected static final Bytes C_KEY = TestKey.charToKey('c');
    protected static final Bytes D_KEY = TestKey.charToKey('d');
    protected static final Bytes E_KEY = TestKey.charToKey('e');
    protected static final Bytes F_KEY = TestKey.charToKey('f');
    protected static final Bytes G_KEY = TestKey.charToKey('g');

    protected static final TestValue APPLE = new TestValue("APPLE");
    protected static final TestValue BANANA = new TestValue("BANANA");
    protected static final TestValue CHERRY = new TestValue("CHERRY");
    protected static final TestValue DATE = new TestValue("DATE");
    protected static final TestValue EGGPLANT = new TestValue("EGGPLANT");
    protected static final TestValue FIG = new TestValue("FIG");
    protected static final TestValue GRAPE = new TestValue("GRAPE");

    protected static final TestValue AARDVARK = new TestValue("AARDVARK");
    protected static final TestValue BEAR = new TestValue("BEAR");
    protected static final TestValue CUTTLEFISH = new TestValue("CUTTLEFISH");
    protected static final TestValue DOG = new TestValue("DOG");
    protected static final TestValue EMU = new TestValue("EMU");
    protected static final TestValue FOX = new TestValue("FOX");
    protected static final TestValue GOOSE = new TestValue("GOOSE");

    protected VirtualMap teacherMap;
    protected VirtualMap learnerMap;
    protected BrokenBuilder teacherBuilder;
    protected BrokenBuilder learnerBuilder;

    protected final ReconnectConfig reconnectConfig = new TestConfigBuilder()
            // This is lower than the default, helps test that is supposed to fail to finish faster.
            .withValue(ReconnectConfig_.ASYNC_STREAM_TIMEOUT, "5s")
            .withValue(ReconnectConfig_.MAX_ACK_DELAY, "1000ms")
            .getOrCreateConfig()
            .getConfigData(ReconnectConfig.class);

    protected abstract VirtualDataSourceBuilder createBuilder();

    @BeforeEach
    void setupEach() {
        final VirtualDataSourceBuilder dataSourceBuilder = createBuilder();
        teacherBuilder = new BrokenBuilder(dataSourceBuilder);
        learnerBuilder = new BrokenBuilder(dataSourceBuilder);
        teacherMap = new VirtualMap(teacherBuilder, CONFIGURATION);
        learnerMap = new VirtualMap(learnerBuilder, CONFIGURATION);
    }

    @BeforeAll
    public static void startup() throws ConstructableRegistryException, FileNotFoundException {
        loadLog4jContext();
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructable(new ClassConstructorPair(Hash.class, Hash::new));
        registry.registerConstructable(
                new ClassConstructorPair(SerializablePublicKey.class, SerializablePublicKey::new));
        registry.registerConstructable(new ClassConstructorPair(CesEvent.class, CesEvent::new));
        registry.registerConstructable(new ClassConstructorPair(NodeId.class, NodeId::new));
        registry.registerConstructable(new ClassConstructorPair(Lesson.class, Lesson::new));
        registry.registerConstructable(new ClassConstructorPair(InternalDataLesson.class, InternalDataLesson::new));
        registry.registerConstructable(new ClassConstructorPair(QueryResponse.class, QueryResponse::new));
        registry.registerConstructable(new ClassConstructorPair(LeafDataLesson.class, LeafDataLesson::new));
        registry.registerConstructable(new ClassConstructorPair(SerializableLong.class, SerializableLong::new));
    }

    protected void reconnect() throws Exception {
        reconnectMultipleTimes(1);
    }

    protected void reconnectMultipleTimes(int attempts) {
        final VirtualMap copy = teacherMap.copy();
        teacherMap.reserve();
        learnerMap.reserve();

        withSuppressedErr(() -> {
            try {
                for (int i = 0; i < attempts; i++) {
                    try {
                        final var node =
                                MerkleTestUtils.hashAndTestSynchronization(learnerMap, teacherMap, reconnectConfig);
                        node.release();
                        assertEquals(attempts - 1, i, "We should only succeed on the last try");
                        assertTrue(learnerMap.isHashed(), "Learner map must be hashed");

                    } catch (Exception e) {
                        if (i == attempts - 1) {
                            fail("We did not expect an exception on this reconnect attempt!", e);
                        }
                        teacherBuilder.nextAttempt();
                        learnerBuilder.nextAttempt();
                    }
                }
            } finally {
                teacherMap.release();
                learnerMap.release();
                copy.release();
            }
        });
    }

    protected static final class BrokenBuilder implements VirtualDataSourceBuilder {

        private VirtualDataSourceBuilder delegate;
        private int numCallsBeforeThrow = Integer.MAX_VALUE;
        private int numCalls = 0;
        private int numTimesToBreak = 0;
        private int numTimesBroken = 0;

        public BrokenBuilder() {}

        public BrokenBuilder(VirtualDataSourceBuilder delegate) {
            this.delegate = delegate;
        }

        @NonNull
        @Override
        public BreakableDataSource build(
                final String label,
                @Nullable final Path sourceDir,
                final boolean compactionEnabled,
                final boolean offlineUse) {
            return new BreakableDataSource(this, delegate.build(label, sourceDir, compactionEnabled, offlineUse));
        }

        @NonNull
        @Override
        public Path snapshot(@Nullable final Path destination, @NonNull final VirtualDataSource snapshotMe) {
            final var breakableSnapshot = (BreakableDataSource) snapshotMe;
            return delegate.snapshot(destination, breakableSnapshot.delegate);
        }

        public void setNumCallsBeforeThrow(int num) {
            this.numCallsBeforeThrow = num;
        }

        public void setNumTimesToBreak(int num) {
            this.numTimesToBreak = num;
        }

        public void nextAttempt() {
            this.numCalls = 0;
        }
    }

    protected static final class BreakableDataSource implements VirtualDataSource {

        private final VirtualDataSource delegate;
        private final BrokenBuilder builder;

        public BreakableDataSource(final BrokenBuilder builder, final VirtualDataSource delegate) {
            this.delegate = Objects.requireNonNull(delegate);
            this.builder = Objects.requireNonNull(builder);
        }

        @Override
        public void saveRecords(
                long firstLeafPath,
                long lastLeafPath,
                @NonNull Stream<VirtualHashRecord> pathHashRecordsToUpdate,
                @NonNull Stream<VirtualLeafBytes> leafRecordsToAddOrUpdate,
                @NonNull Stream<VirtualLeafBytes> leafRecordsToDelete,
                boolean isReconnectContext)
                throws IOException {
            final List<VirtualLeafBytes> leaves = leafRecordsToAddOrUpdate.collect(Collectors.toList());

            if (builder.numTimesBroken < builder.numTimesToBreak) {
                if (builder.numCalls <= builder.numCallsBeforeThrow) {
                    builder.numCalls += leaves.size();
                    if (builder.numCalls > builder.numCallsBeforeThrow) {
                        builder.numTimesBroken++;
                        delegate.close();
                        throw new IOException("Something bad on the DB!");
                    }
                }
            }

            delegate.saveRecords(
                    firstLeafPath,
                    lastLeafPath,
                    pathHashRecordsToUpdate,
                    leaves.stream(),
                    leafRecordsToDelete,
                    isReconnectContext);
        }

        @Override
        public void close(final boolean keepData) throws IOException {
            delegate.close(keepData);
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

        @Override
        public long getFirstLeafPath() {
            return delegate.getFirstLeafPath();
        }

        @Override
        public long getLastLeafPath() {
            return delegate.getLastLeafPath();
        }

        @Override
        public void enableBackgroundCompaction() {
            // no op
        }

        @Override
        public void stopAndDisableBackgroundCompaction() {
            // no op
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        if (teacherMap.getReservationCount() > 0) {
            teacherMap.release();
        }

        if (learnerMap.getReservationCount() > 0) {
            learnerMap.release();
        }
    }

    /**
     * Temporarily suppresses System.err output while executing a runnable.
     * Used to reduce expected error output.
     *
     * @param runnable the operation to execute with suppressed error output
     */
    private static void withSuppressedErr(Runnable runnable) {
        PrintStream originalErr = System.err;
        PrintStream nullStream = new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                // Discard output
            }
        });
        try {
            System.setErr(nullStream);
            runnable.run();
        } finally {
            System.setErr(originalErr);
        }
    }
}

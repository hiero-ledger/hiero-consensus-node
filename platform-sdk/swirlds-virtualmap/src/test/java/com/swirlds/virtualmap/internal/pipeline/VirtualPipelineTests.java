// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.pipeline;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyTrue;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.metrics.platform.MetricKeyRegistry;
import com.swirlds.common.metrics.platform.PlatformMetricsFactoryImpl;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metric.ValueType;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.config.VirtualMapConfig_;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.hiero.base.concurrent.interrupt.InterruptableRunnable;
import org.hiero.base.crypto.Hash;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("VirtualPipeline Tests")
class VirtualPipelineTests {

    private Metrics metrics;
    private VirtualMapConfig config =
            new TestConfigBuilder().getOrCreateConfig().getConfigData(VirtualMapConfig.class);

    /**
     * Run an operation on a thread, interrupt and throw an exception if the thread does not complete before timeout.
     *
     * @param milliseconds
     * 		the timeout, in milliseconds
     * @param runnable
     * 		the operation to run
     */
    static void interruptOnTimeout(
            @SuppressWarnings("SameParameterValue") final int milliseconds,
            final InterruptableRunnable runnable,
            final String message)
            throws InterruptedException {

        final CountDownLatch latch = new CountDownLatch(1);

        final Thread thread = new ThreadConfiguration(getStaticThreadManager())
                .setComponent("test")
                .setThreadName("interrupt-on-timeout")
                .setRunnable(() -> {
                    try {
                        runnable.run();
                        latch.countDown();
                    } catch (InterruptedException e) {
                        // intentional no-op
                    }
                })
                .build();

        thread.start();
        final boolean finished = latch.await(milliseconds, MILLISECONDS);
        if (!finished) {
            throw new RuntimeException(message);
        }
    }

    /**
     * Make sure that every copy in the list is in the correct state.
     */
    private void assertValidity(final List<DummyVirtualRoot> copies) throws InterruptedException {

        boolean oldestUndestroyedFound = false;
        boolean allAreDestroyed = true;

        // Index 0 is the oldest copy, so we will iterate from the oldest to the newest copy
        for (int index = 0; index < copies.size(); index++) {
            final DummyVirtualRoot copy = copies.get(index);
            allAreDestroyed &= copy.isDestroyed();

            // Flushing and merging are mutually exclusive, and only those marked
            // "shouldBeFlushed" should ever be flushed
            if (copy.shouldBeFlushed()) {
                assertFalse(copy.isMerged(), "copy should be flushed, not merged. Copy #" + copy.getCopyIndex());
            } else {
                assertFalse(copy.isFlushed(), "copy is not marked for flushing. Copy #" + copy.getCopyIndex());
            }

            if (copy.isFlushed() || copy.isMerged()) {
                assertTrue(
                        copy.isHashed(),
                        "copy must be hashed before it is flushed or merged. Copy #" + copy.getCopyIndex());
            }

            if (copy.isMerged()) {
                assertTrue(
                        copy.isDestroyed() || copy.isDetached(),
                        "only destroyed or detached copies should be merged. Copy #" + copy.getCopyIndex());
                assertTrue(copy.isImmutable(), "mutable copy should not be merged. Copy #" + copy.getCopyIndex());
            }

            if (oldestUndestroyedFound) {
                // The oldest undestroyed copy has been found, and it's older than this copy
                if (copy.shouldBeFlushed()) {
                    assertFalse(copy.isFlushed(), "only the oldest copy can be flushed. Copy #" + copy.getCopyIndex());
                } else {
                    if ((copy.isDestroyed() || copy.isDetached()) && copy.isImmutable()) {
                        final DummyVirtualRoot next = index + 1 < copies.size() ? copies.get(index + 1) : null;
                        if (next != null && next.isImmutable()) {
                            interruptOnTimeout(
                                    2_000,
                                    copy::waitUntilMerged,
                                    "copy should quickly become merged. Copy #" + copy.getCopyIndex());
                        }
                    }
                }
            } else {
                // The oldest undestroyed copy has not yet been encountered
                if (copy.isDestroyed() || copy.isDetached()) {
                    if (copy.isImmutable()) {
                        if (copy.shouldBeFlushed()) {
                            interruptOnTimeout(
                                    2_000,
                                    copy::waitUntilFlushed,
                                    "copy should quickly become flushed. Copy #" + copy.getCopyIndex());
                        } else {
                            final DummyVirtualRoot next = index + 1 < copies.size() ? copies.get(index + 1) : null;
                            if (next != null && next.isImmutable()) {
                                interruptOnTimeout(
                                        2_000,
                                        copy::waitUntilMerged,
                                        "copy should quickly become merged. Copy #" + copy.getCopyIndex());
                            }
                        }
                    }
                } else {
                    // A copy, which is not merged or flushed, must prevent further copies from flushing
                    oldestUndestroyedFound = true;
                }
            }
        }

        if (allAreDestroyed && !copies.isEmpty()) {
            final VirtualPipeline pipeline = copies.getFirst().getPipeline();
            assertTrue(pipeline.awaitTermination(2, TimeUnit.SECONDS), "thread should stop");
        }
    }

    /**
     * Helper method to create a chain of copies.
     *
     * @param copyCount
     * 		The number of copies to have in the chain. The oldest at index 0 and the newest at the end.
     * 		The newest copy is mutable.
     * @param shouldBeFlushed
     * 		A predicate to determine whether a given copy in the chain should be flushable.
     * @return A non-null array of all copies in the chain. The oldest copy at index 0, the newest at the end.
     * @throws InterruptedException
     * 		As part of the tests this might happen.
     */
    private List<DummyVirtualRoot> setupCopies(
            @SuppressWarnings("SameParameterValue") int copyCount, Predicate<Integer> shouldBeFlushed)
            throws InterruptedException {
        final List<DummyVirtualRoot> copies = new ArrayList<>(copyCount);

        DummyVirtualRoot mutableCopy = null;
        for (int index = 0; index < copyCount; index++) {
            if (mutableCopy == null) {
                mutableCopy = new DummyVirtualRoot("VirtualPipelineTests", config);
                mutableCopy.setShouldFlushPredicate(shouldBeFlushed);
                mutableCopy.registerMetrics(metrics);
            } else {
                mutableCopy = mutableCopy.copy();
            }

            if (shouldBeFlushed.test(index)) {
                mutableCopy.setShouldBeFlushed(true);
            }

            copies.add(mutableCopy);
        }

        assertValidity(copies);

        return copies;
    }

    private static Metrics createMetrics() {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        final MetricKeyRegistry registry = new MetricKeyRegistry();
        return new DefaultPlatformMetrics(
                null,
                registry,
                mock(ScheduledExecutorService.class),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
    }

    private Metric getMetric(final String name) {
        return metrics.getAll().stream()
                .filter(it -> it.getName().contains(name))
                .findAny()
                .orElse(null);
    }

    private int getIntMetricValue(final String name) {
        final Metric metric = getMetric(name);
        return Integer.parseInt(metric.get(ValueType.VALUE).toString());
    }

    private void assertIntMetricValue(final String name, final int value) {
        final int metricValue = getIntMetricValue(name);
        assertEquals(value, metricValue);
    }

    @BeforeEach
    void setupEach() {
        metrics = createMetrics();
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("registerCopy rejects nulls")
    void registerCopyRejectsNull() {
        final DummyVirtualRoot root = new DummyVirtualRoot("registerCopyRejectsNull", config);
        final VirtualPipeline pipeline = root.getPipeline();
        assertNotNull(pipeline, "Pipeline should never be null");
        assertThrows(NullPointerException.class, () -> pipeline.registerCopy(null), "Should have thrown NPE");
    }

    /**
     * This tests creates 100 copies and either releases and/or detaches them in order from the oldest to the newest.
     * It does this all from within the same thread.
     *
     * @throws InterruptedException
     * 		Side effect of test code
     */
    @ParameterizedTest
    @CsvSource({"true,false", "false,true", "true,true"})
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Ordered Release and/or Detach")
    void orderedReleaseAndOrDetach(boolean doDetach, boolean doRelease) throws IOException, InterruptedException {
        // Create 100 copies where every 10th is flush eligible
        final List<DummyVirtualRoot> copies = setupCopies(100, i -> i % 10 == 0);
        for (final DummyVirtualRoot copy : copies) {
            if (doDetach) {
                copy.getPipeline().pausePipelineAndRun("copy", copy::detach);
            }
            if (doRelease) {
                copy.release();
            }
            assertValidity(copies);
        }
    }

    /**
     * This test creates 100 copies and releases and/or detaches them in a completely (pseudo-)random order.
     * Semantically we should be able to release and/or detach any copy in any order and the pipeline will
     * make sure that the right thing is done.
     *
     * @throws InterruptedException
     * 		Side effect of test code
     */
    @ParameterizedTest
    @CsvSource({"true,false", "false,true", "true,true"})
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Random Release")
    void randomReleaseAndOrDetach(boolean doDetach, boolean doRelease) throws IOException, InterruptedException {
        // Create 100 copies where every 10th is flush eligible
        final int copyCount = 100;
        final List<DummyVirtualRoot> copies = setupCopies(copyCount, i -> i % 10 == 0);

        // Create a collection of indexes corresponding to the copies and pseudo-randomly shuffle them
        // This will give us the order in which we release items.
        final List<Integer> order = new ArrayList<>(copyCount);
        for (int index = 0; index < copyCount - 1; index++) {
            order.add(index);
        }

        final Random random = new Random();
        Collections.shuffle(order, random);
        order.add(copyCount - 1); // release the mutable copy last

        // Now release things in the order we determined above.
        for (final int index : order) {
            final DummyVirtualRoot copy = copies.get(index);
            if (doDetach) {
                copy.getPipeline().pausePipelineAndRun("copy", copy::detach);
            }
            if (doRelease) {
                copy.release();
            }
            assertValidity(copies);
        }
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Reject Immutable Registration")
    void rejectImmutableRegistration() throws InterruptedException {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final VirtualPipeline pipeline =
                new VirtualPipeline(configuration.getConfigData(VirtualMapConfig.class), "rejectImmutableRegistration");
        final NoOpVirtualRoot root = new NoOpVirtualRoot();
        root.makeImmutable();

        assertThrows(
                IllegalStateException.class,
                () -> pipeline.registerCopy(root),
                "pipeline should reject immutable copy");

        pipeline.terminate();
        assertTrue(pipeline.awaitTermination(2, TimeUnit.SECONDS), "thread should stop");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Random Release Pre Hash")
    void randomReleasePreHash() throws InterruptedException {
        // Create 100 copies where every 10th is flush eligible
        final int copyCount = 100;
        final List<DummyVirtualRoot> copies = setupCopies(copyCount, i -> i % 10 == 0);

        // Hash the oldest half of the copies
        for (int index = 0; index < copyCount / 2; index++) {
            final DummyVirtualRoot copyToHash = copies.get(index);

            if (index > 0) {
                // first copy may have been hashed since it is flush eligible, no other will have been hashed
                assertFalse(copyToHash.isHashed(), "copy should not yet be hashed");
            }

            // Returns null, but causes dummy class to consider itself to be hashed
            copyToHash.getHash();

            assertTrue(copyToHash.isHashed(), "copy should be hashed");
            assertValidity(copies);
        }

        // Hash a random subset all copies. Some copies will be requested to hash themselves again
        // (this is intentional)
        final Random random = new Random();
        for (int index = 0; index < copyCount; index++) {
            if (random.nextFloat() > 0.5) {
                final DummyVirtualRoot copyToHash = copies.get(index);
                copyToHash.getHash();
                assertTrue(copyToHash.isHashed(), "copy should be hashed");
                assertValidity(copies);
            }
        }

        final List<Integer> releaseOrder = new ArrayList<>(copyCount);
        for (int index = 0; index < copyCount - 1; index++) {
            releaseOrder.add(index);
        }
        Collections.shuffle(releaseOrder, random);
        releaseOrder.add(copyCount - 1); // release the mutable copy last

        for (final int indexToRelease : releaseOrder) {
            final DummyVirtualRoot copyToRelease = copies.get(indexToRelease);
            copyToRelease.release();
            assertValidity(copies);
        }
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Terminate waits for jobs to complete")
    void terminateWaitsForJobs() {
        final SlowVirtualRoot root = new SlowVirtualRoot("terminateWaitsForJobs", config);
        final SlowVirtualRoot copy1 = root.copy();
        final SlowVirtualRoot copy2 = copy1.copy();
        final SlowVirtualRoot copy3 = copy2.copy();

        // Copies root, copy1, copy2 are all immutable.
        // root should be flushable. copy1 and copy2 are all merge-able,
        // but copy2 won't merge because copy3 isn't immutable.

        // Root shouldn't be flushed because the flushFinishedLatch will prevent it from completing.
        // Then count down the latch, so it can move forward.
        root.setShouldBeFlushed(true);
        root.release();
        assertFalse(root.isFlushed(), "Should not have finished flushing yet");
        root.flushFinishedLatch.countDown();

        // None of these should be flushable
        assertFalse(copy1.shouldBeFlushed(), "Should not be flushable");
        assertFalse(copy2.shouldBeFlushed(), "Should not be flushable");
        assertFalse(copy3.shouldBeFlushed(), "Should not be flushable");

        // I'll take the latch out of the way, so that IF copy1 were to be processed,
        // we wouldn't block it. But it won't be processed because we haven't destroyed
        // copy1 or detached it.
        copy1.mergeFinishedLatch.countDown();

        // By the time this returns, I know for certain previous tasks are done.
        root.getPipeline().terminate();

        // Root will have finished, but the others will not have done anything.
        assertTrue(root.isFlushed(), "Should have flushed before terminate finished");
        assertFalse(copy1.isMerged(), "Should never merge now!");
        assertFalse(copy2.isMerged(), "Should never merge now!");
        assertFalse(copy3.isMerged(), "Should never merge now!");

        // Count these down, just in case any background threads have been running.
        // They shouldn't have been.
        copy2.mergeFinishedLatch.countDown();
        copy3.mergeFinishedLatch.countDown();
        copy1.release();
        copy2.release();
        copy3.release();

        // This isn't quite right, it is possible if a background thread was merging, we might see this
        // pass even though it shouldn't.
        assertFalse(copy1.isMerged(), "Should never merge now!");
        assertFalse(copy2.isMerged(), "Should never merge now!");
        assertFalse(copy3.isMerged(), "Should never merge now!");
    }

    // Regression test for #4223
    @Test
    void testPipelineList() {
        PipelineList<Long> copies = new PipelineList<>();
        copies.add(1L);
        copies.add(2L);
        copies.add(3L);
        copies.add(100L);

        final PipelineListNode<Long> goodNextNode = new PipelineListNode<>(101L);
        final PipelineListNode<Long> badNextNode1 = new PipelineListNode<>(42L);
        PipelineList<Long> secondPipeline = new PipelineList<>();
        secondPipeline.add(200L);
        secondPipeline.add(220L);
        final PipelineListNode<Long> badNextNode2 = secondPipeline.getFirst().getNext();

        for (PipelineListNode<Long> node = copies.getFirst(); node != null; node = node.getNext()) {
            if (node.getValue() == 2) {
                final PipelineListNode<Long> finalNode = node;
                assertThrows(
                        IllegalStateException.class,
                        () -> finalNode.addNext(badNextNode1),
                        "can't add a nextNode from the middle of a PipelineList.");
                assertEquals("(2)", node.toString(), "unexpected value returned from toString().");
            }
            if (node.getValue() == 100) {
                node.addNext(goodNextNode); // this is legal, since node is the last node of the list.
                copies.remove(node);
            }
            if (node.getValue() == 101) {
                final PipelineListNode<Long> finalNode = node;
                assertThrows(
                        IllegalStateException.class,
                        () -> finalNode.addNext(badNextNode2),
                        "can't add a nextNode with a previous value already set.");
                copies.remove(node);
            }
        }
        copies.add(4L);

        AtomicLong sum = new AtomicLong(0);
        copies.testAll(node -> {
            sum.addAndGet(node);
            return true;
        });
        assertEquals(10, sum.get(), "List integrity violated");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Datasource is closed after the last copy is destroyed")
    void dataSourceClosedAfterLastCopyDestroyed() throws InterruptedException {
        // Create 10 copies. Copy 3, 6, and 9 are flush eligible.
        final int copyCount = 10;
        final List<DummyVirtualRoot> copies = setupCopies(copyCount, i -> i != 0 && i % 3 == 0);

        final Random rand = new Random(837);
        final List<Integer> shuffledIndexes =
                IntStream.range(0, copyCount - 1).boxed().collect(Collectors.toList());
        Collections.shuffle(shuffledIndexes, rand);
        for (final int i : shuffledIndexes) {
            final var copy = copies.get(i);
            assertFalse(copy.isShutdownHandlerCalled(), "Should not be invoked yet");
            copy.release();
            assertFalse(copy.isShutdownHandlerCalled(), "Should not be invoked yet");
        }

        final var lastCopy = copies.get(copyCount - 1);
        lastCopy.release();
        assertTrue(lastCopy.getPipeline().awaitTermination(5, SECONDS), "Timed out");
        assertTrue(lastCopy.isShutdownHandlerCalled(), "Callback should now be invoked");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Datasource is closed when pipeline is terminated")
    void dataSourceClosedWhenPipelineTerminates() throws InterruptedException {
        // Create 10 copies. Copy 3, 6, and 9 are flush eligible.
        final int copyCount = 10;
        final List<DummyVirtualRoot> copies = setupCopies(copyCount, i -> i != 0 && i % 3 == 0);

        // I'll release half of them and then terminate the pipeline.
        for (int i = 0; i < copyCount / 2; i++) {
            final var copy = copies.get(i);
            assertFalse(copy.isShutdownHandlerCalled(), "Should not be invoked yet");
            copy.release();
            assertFalse(copy.isShutdownHandlerCalled(), "Should not be invoked yet");
        }

        copies.get(0).getPipeline().terminate();
        final var lastCopy = copies.get(copyCount - 1);
        assertTrue(lastCopy.getPipeline().awaitTermination(5, SECONDS), "Timed out");
        assertTrue(lastCopy.isShutdownHandlerCalled(), "Callback should now be invoked");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Datasource is closed when pipeline terminates due to error")
    void dataSourceClosedWhenPipelineTerminatesDueToError() throws InterruptedException {
        // Create 10 copies. Let's them all be flush eligible for simplicity in the test
        final int copyCount = 10;
        final List<DummyVirtualRoot> copies = setupCopies(copyCount, i -> true);

        // I'll release half of them and then blow something up.
        final int half = copyCount / 2;
        for (int i = 0; i < half; i++) {
            final var copy = copies.get(i);
            assertFalse(copy.isShutdownHandlerCalled(), "Should not be invoked yet");
            copy.release();
            assertFalse(copy.isShutdownHandlerCalled(), "Should not be invoked yet");
        }

        final var halfCopy = copies.get(half);
        halfCopy.setCrashOnFlush(true);
        halfCopy.release(); // Should cause it to crash!

        final var lastCopy = copies.get(copyCount - 1);
        assertTrue(lastCopy.getPipeline().awaitTermination(5, SECONDS), "Timed out");
        assertTrue(lastCopy.isShutdownHandlerCalled(), "Callback should now be invoked");
    }

    private static final class SlowVirtualRoot extends DummyVirtualRoot {

        private final CountDownLatch flushFinishedLatch = new CountDownLatch(1);
        private final CountDownLatch mergeFinishedLatch = new CountDownLatch(1);

        private SlowVirtualRoot(final String label, final VirtualMapConfig config) {
            super(label, config);
        }

        private SlowVirtualRoot(SlowVirtualRoot other) {
            super(other);
        }

        @Override
        public SlowVirtualRoot copy() {
            setImmutable(true);
            final SlowVirtualRoot copy = new SlowVirtualRoot(this);
            getPipeline().registerCopy(copy);
            return copy;
        }

        @Override
        public void flush() {
            try {
                if (!flushFinishedLatch.await(30, TimeUnit.SECONDS)) {
                    throw new RuntimeException("Wait exceeded");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ex);
            }
            super.flush();
        }

        @Override
        public void merge() {
            try {
                if (!mergeFinishedLatch.await(30, TimeUnit.SECONDS)) {
                    throw new RuntimeException("Wait exceeded");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ex);
            }
            super.merge();
        }
    }

    @ParameterizedTest
    @Tag(TestComponentTags.VMAP)
    @ValueSource(ints = {11, 50, 99, 100, 500, 1000, 1111})
    @DisplayName("Size based flushes")
    public void sizeBasedFlushes(int copyCount) throws InterruptedException {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final VirtualMapConfig config = configuration.getConfigData(VirtualMapConfig.class);

        final List<DummyVirtualRoot> copies = setupCopies(copyCount, i -> false);
        DummyVirtualRoot last = copies.get(copies.size() - 1);
        DummyVirtualRoot afterCopy = last.copy();
        afterCopy.setShouldBeFlushed(true);
        afterCopy.copy(); // make it immutable and eligible to flush
        for (int i = 0; i < copyCount; i++) {
            DummyVirtualRoot copy = copies.get(i);
            // Every 11th copy should be flushed
            copy.setEstimatedSize(config.copyFlushCandidateThreshold() / 10 - 1);
        }
        // Release all copies to make them mergeable / flushable. Note that when the first copy is
        // released, a thread race between this thread and the pipeline thread starts. It may
        // happen that the pipeline will check if a copy should be flushed before it's released,
        // which would result in less than expected number of flushed copies. To avoid that,
        // pause the pipeline until all copies are released
        afterCopy.getPipeline().pausePipelineAndExecute("releaseAll", () -> {
            for (DummyVirtualRoot copy : copies) {
                copy.release();
            }
            return null;
        });
        afterCopy.release();
        afterCopy.waitUntilFlushed();
        int flushedCount = 0;
        for (DummyVirtualRoot copy : copies) {
            if (copy.isFlushed()) {
                flushedCount++;
            }
        }
        assertEquals(copyCount / 11, flushedCount, "There should be " + copyCount / 11 + " flushed copies");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Small copies are never flushed")
    void smallCopiesAreNeverFlushed() throws InterruptedException {
        final int copyCount = 1000;
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final VirtualMapConfig config = configuration.getConfigData(VirtualMapConfig.class);
        final List<DummyVirtualRoot> copies = setupCopies(copyCount, i -> false);
        for (int i = 0; i < copyCount; i++) {
            DummyVirtualRoot copy = copies.get(i);
            // Set all copies small enough, so none of them should be flushed even after merge
            copy.setEstimatedSize(config.copyFlushCandidateThreshold() / (copyCount + 1));
        }
        DummyVirtualRoot last = copies.get(copies.size() - 1);
        DummyVirtualRoot afterCopy = last.copy();
        afterCopy.setShouldBeFlushed(true);
        afterCopy.copy(); // make afterCopy immutable / eligible to flush
        for (DummyVirtualRoot copy : copies) {
            copy.release();
        }
        last.waitUntilMerged();
        for (DummyVirtualRoot copy : copies) {
            assertFalse(copy.isFlushed(), "Small copy should not be flushed");
        }
        afterCopy.release();
        afterCopy.waitUntilFlushed();
        assertTrue(afterCopy.isFlushed());
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Undestroyed Copy Blocks Flushes")
    void undestroyedCopyBlocksFlushes() throws InterruptedException {
        final int copyCount = 10;

        // Copies 0 and 5 need to be flushed
        final List<DummyVirtualRoot> copies = setupCopies(copyCount, i -> i == 0 || i == 5);

        // release copies 1 through 5. 5 should be prevented from flushing due to copy 0 not being destroyed.
        for (int i = 1; i <= 5; i++) {
            copies.get(i).release();
        }

        copies.get(4).waitUntilMerged();
        for (int i = 0; i < copyCount; i++) {
            DummyVirtualRoot copy = copies.get(i);
            assertFalse(copy.isFlushed(), "Copy should not yet be flushed");
            if ((i != 0) && (i < 5)) {
                assertTrue(copy.isMerged(), "Copy should be merged by now " + copy.getCopyIndex());
            }
        }

        copies.get(0).release();
        copies.get(5).waitUntilFlushed();
        assertTrue(copies.get(0).isFlushed(), "copy should be flushed by now");
        assertTrue(copies.get(5).isFlushed(), "copy should be flushed by now");

        // release remaining copies
        for (int i = 6; i < copyCount; i++) {
            copies.get(i).release();
        }
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Undestroyed Detached Copy Does Not Block")
    void undestroyedDetachedCopyDoesNotBlock() throws IOException, InterruptedException {
        final int copyCount = 10;

        // Copies 5 needs to be flushed
        final List<DummyVirtualRoot> copies = setupCopies(copyCount, i -> i == 5);

        final DummyVirtualRoot copy0 = copies.get(0);
        copy0.getPipeline().pausePipelineAndRun("copy", copy0::detach);

        // Once detached, copy 0 should be merge eligible
        copy0.waitUntilMerged();

        assertTrue(copy0.isMerged(), "copy should be merged");

        // release copies 1 through 5
        for (int i = 1; i < 6; i++) {
            copies.get(i).release();
        }

        copies.get(5).waitUntilFlushed();
        assertTrue(copies.get(5).isFlushed(), "copy should be flushed by now");

        // release remaining copies
        for (int i = 6; i < copyCount; i++) {
            copies.get(i).release();
        }
        copies.get(0).release();
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Merge Release Race")
    void mergeReleaseRace() throws InterruptedException {
        final int copyCount = 10;

        // Copies 5 needs to be flushed
        final List<DummyVirtualRoot> copies = setupCopies(copyCount, i -> i == 5);

        // Release 0-3
        for (int i = 0; i < 4; i++) {
            copies.get(i).release();
        }

        // Wait for a moment and let the pipeline catch up with all work
        MILLISECONDS.sleep(20);

        assertTrue(copies.get(3).isMerged(), "copy should be merged by now");
        assertFalse(copies.get(4).isMerged(), "copy should not be merged");

        // The next time isMerged() is called on copy 4, it will release itself.
        // Simulates a race condition that is possible in the real world.
        // This could cause a copy to be flushed before an older copy was merged.
        copies.get(4).setReleaseInIsDetached(true);

        // force the pipeline to iterate through the list
        copies.get(5).release();

        // release remaining, copy 4 destroyed itself (via setReleaseInIsDetached)
        for (int i = 6; i < 9; i++) {
            copies.get(i).release();
        }

        assertEventuallyTrue(() -> copies.get(5).isFlushed(), Duration.ofSeconds(1), "copy should have been flushed");

        copies.get(9).release();
    }

    /**
     * Measure the time that it takes to make another copy, assert that it is within 10ms of the expected time.
     */
    private static void copyAndAssertFamilySizeBackpressurePause(
            final Deque<DummyVirtualRoot> copies, final int expectedTimeMs) {
        final DummyVirtualRoot copy = copies.getLast().copy();
        copies.add(copy);
        final long duration = copy.getPipeline().calculateFamilySizeBackpressurePause();
        if (duration <= 0) {
            // no backpressure applied
            return;
        }

        assertEquals(expectedTimeMs, duration);
    }

    @Test
    @DisplayName("Test Family Size Backpressure")
    void testFamilySizeBackpressure() throws InterruptedException {

        final int familyThrottleThreshold = 10000;
        final int estimatedSize = 100;

        final Configuration config = new TestConfigBuilder()
                .withSource(new SimpleConfigSource()
                        .withValue(VirtualMapConfig_.FAMILY_THROTTLE_THRESHOLD, familyThrottleThreshold + ""))
                .withConfigDataType(VirtualMapConfig.class)
                .getOrCreateConfig();

        final Deque<DummyVirtualRoot> copies = new LinkedList<>();

        final DummyVirtualRoot originalCopy =
                new DummyVirtualRoot("flushThrottle", config.getConfigData(VirtualMapConfig.class));
        originalCopy.setEstimatedSize(100);
        originalCopy.setShouldFlushPredicate(i -> i % 2 == 1); // flush odd copies
        copies.add(originalCopy);

        // Create some copies, but not so many that the flush throttle becomes engaged.
        for (int i = 0; i < familyThrottleThreshold / estimatedSize; i++) {
            copyAndAssertFamilySizeBackpressurePause(copies, 0);
        }

        // Creation of additional copies should become increasingly slower and slower
        int copiesOverThreshold = 10;

        for (int i = 0; i < copiesOverThreshold; i++) {
            final int expectedDelayMs = (i + 1) * (i + 1);
            copyAndAssertFamilySizeBackpressurePause(copies, expectedDelayMs);
        }

        for (int i = 0; i < copiesOverThreshold + 2; i++) {
            copies.removeFirst().release();
        }

        // Give some time for the background thread to catch up.
        MILLISECONDS.sleep(100);

        copyAndAssertFamilySizeBackpressurePause(copies, 0);
        // Release remaining copies so that the background thread dies.
        while (!copies.isEmpty()) {
            copies.removeFirst().release();
        }
    }

    @Test
    @DisplayName("Get same copy hash in multiple threads")
    void concurrentHashing() throws InterruptedException {
        final int NUM_COPIES = 100;
        final int NUM_THREADS = 50;
        final List<DummyVirtualRoot> copies = setupCopies(NUM_COPIES, i -> false);

        final DummyVirtualRoot penultimate = copies.get(copies.size() - 2);
        final DummyVirtualRoot last = copies.get(copies.size() - 1);
        final Hash[] hashes = new Hash[NUM_COPIES];
        IntStream.range(0, NUM_THREADS).parallel().forEach(i -> {
            hashes[i] = penultimate.getHash();
        });
        for (final Hash hash : hashes) {
            assertSame(hash, hashes[0]);
        }
        copies.stream().filter(copy -> copy != last).forEach(copy -> assertTrue(copy.isHashed(), "Copy not hashed"));
        assertFalse(last.isHashed(), "Copy hashed");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    void pipelineSizeStatTest() throws Exception {
        final int copiesCount = 100;
        final List<DummyVirtualRoot> copies = setupCopies(copiesCount, i -> false);
        assertIntMetricValue("vmap_lifecycle_pipelineSize_VirtualPipelineTests", copiesCount);
        final DummyVirtualRoot newCopy = copies.get(copiesCount - 1).copy();
        assertIntMetricValue("vmap_lifecycle_pipelineSize_VirtualPipelineTests", copiesCount + 1);
        for (int i = 0; i < copiesCount / 2; i++) {
            copies.get(i).release();
        }
        assertEventuallyTrue(
                () -> getIntMetricValue("vmap_lifecycle_pipelineSize_VirtualPipelineTests") == copiesCount / 2 + 1,
                Duration.ofSeconds(10),
                "Copy is not merged or flushed");
        for (int i = copiesCount / 2; i < copiesCount; i++) {
            copies.get(i).release();
        }
        // Create one more copy, so lastCopy can be merged into newCopy, which must be immutable
        final DummyVirtualRoot newNewCopy = newCopy.copy();
        assertEventuallyTrue(
                () -> getIntMetricValue("vmap_lifecycle_pipelineSize_VirtualPipelineTests") == 2,
                Duration.ofSeconds(10),
                "Copy is not merged or flushed");
        newCopy.release();
        newNewCopy.release();
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    void flushCountStatTest() throws Exception {
        final List<DummyVirtualRoot> copies = setupCopies(81, i -> (i > 0) && (i % 20 == 0));
        assertIntMetricValue("vmap_lifecycle_flushCount_VirtualPipelineTests", 0);
        for (int i = 0; i < 39; i++) {
            copies.get(i).release();
        }
        assertEventuallyTrue(
                () -> getIntMetricValue("vmap_lifecycle_flushCount_VirtualPipelineTests") == 1,
                Duration.ofSeconds(10),
                "Copy is not flushed");
        copies.get(39).release(); // unreleased 39 prevents 40 from flushing; just unreleased 40 does not
        copies.get(40).release();
        assertEventuallyTrue(
                () -> getIntMetricValue("vmap_lifecycle_flushCount_VirtualPipelineTests") == 2,
                Duration.ofSeconds(10),
                "Copy is not flushed");
        final DummyVirtualRoot newCopy = copies.get(80).copy();
        for (int i = 41; i < 81; i++) {
            copies.get(i).release();
        }
        assertEventuallyTrue(
                () -> getIntMetricValue("vmap_lifecycle_flushCount_VirtualPipelineTests") == 4,
                Duration.ofSeconds(10),
                "Copy is not flushed");
        newCopy.release();
    }
}

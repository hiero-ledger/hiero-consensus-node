// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockStreamingObsTest {

    // gatherAndLogObsData() and refreshEnabledFlag() are private; tests drive the gather cycle via these handles.
    private static final MethodHandle gatherHandle;
    private static final MethodHandle refreshEnabledFlagHandle;

    static {
        try {
            final Lookup lookup = MethodHandles.lookup();

            final Method gather = BlockStreamingObs.class.getDeclaredMethod("gatherAndLogObsData");
            gather.setAccessible(true);
            gatherHandle = lookup.unreflect(gather);

            final Method refreshEnabledFlag = BlockStreamingObs.class.getDeclaredMethod("refreshEnabledFlag");
            refreshEnabledFlag.setAccessible(true);
            refreshEnabledFlagHandle = lookup.unreflect(refreshEnabledFlag);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final long MILLI = 1_000_000L;

    @Mock
    private ConfigProvider configProvider;

    private final TestClock clock = new TestClock();
    private final List<BlockStreamingObs> created = new ArrayList<>();
    private LogCaptor logCaptor;

    @BeforeEach
    void setUp() {
        logCaptor = new LogCaptor(LogManager.getLogger(BlockStreamingObs.class));
    }

    @AfterEach
    void tearDown() {
        created.forEach(BlockStreamingObs::close);
        logCaptor.stopCapture();
    }

    // ── full lifecycle aggregation values ───────────────────────────────────────

    @Test
    void testFullLifecycle_reportContainsExactAggregates() {
        final BlockStreamingObs obs = makeObs(true);
        final long block1 = 10L;
        final long block2 = 11L;

        clock.set(secs(1));
        obs.onBlockInit(block1);
        clock.set(secs(1) + MILLI);
        obs.onBlockOpen(block1);
        clock.set(secs(1) + 2 * MILLI);
        obs.onBlockItemAdd(block1, 0, 100, false);
        clock.set(secs(1) + 3 * MILLI);
        obs.onBlockItemAdd(block1, 1, 200, false);
        clock.set(secs(1) + 4 * MILLI);
        obs.onBlockItemAdd(block1, 2, 300, false);
        obs.onBlockHeaderSend(block1, secs(1) + 10 * MILLI, secs(1) + 11 * MILLI);
        obs.onBlockItemsSend(block1, 0, 2, secs(1) + 10 * MILLI, secs(1) + 11 * MILLI);
        obs.onBlockEndSend(block1, secs(1) + 12 * MILLI, secs(1) + 13 * MILLI);
        clock.set(secs(1) + 14 * MILLI);
        obs.onBlockClose(block1);
        clock.set(secs(1) + 20 * MILLI);
        obs.onBlockAcknowledge(block1);

        // create another block to test the open delay is calculated
        clock.set(secs(2));
        obs.onBlockInit(block2);
        clock.set(secs(2) + MILLI);
        obs.onBlockOpen(block2);
        clock.set(secs(2) + 2 * MILLI);
        obs.onBlockItemAdd(block2, 0, 100, false);
        clock.set(secs(2) + 3 * MILLI);
        obs.onBlockItemAdd(block2, 1, 200, false);
        clock.set(secs(2) + 4 * MILLI);
        obs.onBlockItemAdd(block2, 2, 300, false);
        obs.onBlockHeaderSend(block2, secs(2) + 10 * MILLI, secs(2) + 11 * MILLI);
        obs.onBlockItemsSend(block2, 0, 2, secs(2) + 10 * MILLI, secs(2) + 11 * MILLI);
        obs.onBlockEndSend(block2, secs(2) + 12 * MILLI, secs(2) + 13 * MILLI);
        clock.set(secs(2) + 14 * MILLI);
        obs.onBlockClose(block2);
        clock.set(secs(2) + 20 * MILLI);
        obs.onBlockAcknowledge(block2);

        clock.set(secs(10)); // move past the 2-second gather grace period
        final String report = gather(obs);
        System.out.println(report);

        assertThat(report).isNotNull();
        // block lifecycle spans (exact line for the simplest probe; prefixes elsewhere)
        assertThat(report)
                .contains(
                        "InitToOpen { (Unit:NANOS|Samples:2|Sum:2000000|Min:1000000|Max:1000000|Avg:1000000.0000|StdDev:0.0000) }");
        assertThat(report).contains("OpenToClose { (Unit:NANOS|Samples:2|Sum:26000000|");
        assertThat(report).contains("OpenToAck { (Unit:NANOS|Samples:2|Sum:38000000|");
        assertThat(report).contains("ClosedToAck { (Unit:NANOS|Samples:2|Sum:12000000|");
        assertThat(report).contains("HeaderSentToAck { (Unit:NANOS|Samples:2|Sum:18000000|");
        assertThat(report).contains("EndSentToAck { (Unit:NANOS|Samples:2|Sum:14000000|");
        assertThat(report).contains("ItemsPerBlock { (Unit:COUNT|Samples:2|Sum:6|");
        assertThat(report).contains("BlockSize { (Unit:BYTES|Samples:2|Sum:1200|");
        // verify the open delay - there is only 1 sample even though there are 2 blocks. The open delay can only be
        // determined for the second block since there was no block before the first to compare against
        assertThat(report).contains("OpenDelay { (Unit:NANOS|Samples:1|Sum:987000000|");
        // per-item details: idle = send start - buffered (8/7/6 ms), latency = send window (1 ms each)
        assertThat(report).contains("ItemSize { (Unit:BYTES|Samples:6|Sum:1200|Min:100|Max:300|");
        assertThat(report).contains("ItemIdle { (Unit:NANOS|Samples:6|Sum:42000000|Min:6000000|Max:8000000|");
        assertThat(report).contains("ItemSendLatency { (Unit:NANOS|Samples:6|Sum:6000000|Min:1000000|Max:1000000|");
        assertThat(report).contains("ItemsNeverSent { (Unit:COUNT|Sum:0) }");
        // summary counters
        assertThat(report).contains("Opened { (Unit:COUNT|Sum:2) }");
        assertThat(report).contains("Closed { (Unit:COUNT|Sum:2) }");
        assertThat(report).contains("Acknowledged { (Unit:COUNT|Sum:2) }");
        assertThat(report).contains("Abandoned { (Unit:COUNT|Sum:0) }");
        assertThat(report).contains("Created-Total { (Unit:COUNT|Sum:6)(Unit:BYTES|Sum:1200) }");
        assertThat(report).contains("Sent-Total { (Unit:COUNT|Sum:6)(Unit:BYTES|Sum:1200) }");
    }

    @Test
    void testProofAndFooterSpans_reported() {
        final BlockStreamingObs obs = makeObs(true);

        clock.set(secs(1));
        obs.onBlockInit(1L);
        clock.set(secs(1) + MILLI);
        obs.onBlockOpen(1L);
        clock.set(secs(1) + 2 * MILLI);
        obs.onBlockFooterCreate(1L);
        clock.set(secs(1) + 5 * MILLI);
        obs.onBlockProofCreate(1L);
        clock.set(secs(1) + 8 * MILLI);
        obs.onBlockItemAdd(1L, 0, 50, true); // the proof item
        clock.set(secs(1) + 9 * MILLI);
        obs.onBlockAcknowledge(1L);

        clock.set(secs(10));
        final String report = gather(obs);

        assertThat(report).isNotNull();
        assertThat(report).contains("OpenToProofCreated { (Unit:NANOS|Samples:1|Sum:4000000|");
        assertThat(report).contains("OpenToProofAdded { (Unit:NANOS|Samples:1|Sum:7000000|");
        assertThat(report).contains("FooterCreatedToProofCreated { (Unit:NANOS|Samples:1|Sum:3000000|");
    }

    /** Regression guard for sentinel pollution: a block acked while only partially tracked must
     * contribute zero samples to the spans it has no ticks for, not ~-10^14 garbage values. */
    @Test
    void testPartiallyTrackedBlock_doesNotPolluteProbesWithSentinelGarbage() {
        final BlockStreamingObs obs = makeObs(true);

        clock.set(secs(1));
        obs.onBlockInit(1L); // never opened or closed (e.g. obs was enabled mid-block)
        obs.onBlockAcknowledge(1L);

        clock.set(secs(10));
        final String report = gather(obs);

        assertThat(report).isNotNull();
        assertThat(report).contains("InitToOpen { (Unit:NANOS|Samples:0|");
        assertThat(report).contains("OpenToAck { (Unit:NANOS|Samples:0|");
        assertThat(report).contains("ClosedToAck { (Unit:NANOS|Samples:0|");
        assertThat(report).contains("OpenToProofAdded { (Unit:NANOS|Samples:0|");
    }

    // ── never-sent items ────────────────────────────────────────────────────────

    @Test
    void testItemsNeverSent_countTowardSizeButNotIdleOrLatency() {
        final BlockStreamingObs obs = makeObs(true);

        // block 5: two items buffered but never sent (e.g. the block node already had the block)
        clock.set(secs(1));
        obs.onBlockInit(5L);
        obs.onBlockOpen(5L);
        obs.onBlockItemAdd(5L, 0, 100, false);
        obs.onBlockItemAdd(5L, 1, 200, false);
        obs.onBlockAcknowledge(5L);

        // block 6: one item, sent normally
        obs.onBlockInit(6L);
        obs.onBlockOpen(6L);
        obs.onBlockItemAdd(6L, 0, 400, false);
        obs.onBlockItemsSend(6L, 0, 0, secs(1) + 5 * MILLI, secs(1) + 6 * MILLI);
        obs.onBlockAcknowledge(6L);

        clock.set(secs(10));
        final String report = gather(obs);

        assertThat(report).isNotNull();
        // all three items count toward size...
        assertThat(report).contains("ItemSize { (Unit:BYTES|Samples:3|Sum:700|Min:100|Max:400|");
        // ...but only block 6's item has idle/latency samples
        assertThat(report).contains("ItemIdle { (Unit:NANOS|Samples:1|Sum:5000000|");
        assertThat(report).contains("ItemSendLatency { (Unit:NANOS|Samples:1|Sum:1000000|");
        assertThat(report).contains("ItemsNeverSent { (Unit:COUNT|Sum:2) }");
    }

    // ── throughput buckets / fencepost ──────────────────────────────────────────

    @Test
    void testThroughput_sameSecondSharesBucket_andDivisorSpansFullWindow() {
        final BlockStreamingObs obs = makeObs(true);

        clock.set(secs(1));
        obs.onBlockInit(1L);
        obs.onBlockOpen(1L);
        obs.onBlockItemAdd(1L, 0, 100, false);
        obs.onBlockItemAdd(1L, 1, 200, false); // same second -> same bucket
        clock.set(secs(2));
        obs.onBlockItemAdd(1L, 2, 300, false); // next second -> second bucket

        clock.set(secs(10));
        final String report = gather(obs);

        assertThat(report).isNotNull();
        // buckets at seconds 1 and 2 -> the window spans 2 seconds, not 1 (fencepost regression guard)
        assertThat(report).contains("Seconds { (Unit:COUNT|Sum:2) }");
        assertThat(report).contains("Created-Total { (Unit:COUNT|Sum:3)(Unit:BYTES|Sum:600) }");
        assertThat(report).contains("Created-PerSecond { (Unit:COUNT|Avg:1.5000)(Unit:BYTES|Avg:300.0000) }");
    }

    // ── dynamic enable/disable ──────────────────────────────────────────────────

    @Test
    void testDynamicDisable_clearsAccumulatedData_andLateAckIsNoOp() {
        final BlockStreamingObs obs = makeObs(true);

        clock.set(secs(1));
        obs.onBlockInit(1L);
        obs.onBlockOpen(1L);
        obs.onBlockItemAdd(1L, 0, 100, false);

        // flag flips to disabled before the next gather cycle: everything is cleared
        stubConfig(false);
        invokeRefreshEnabledFlag(obs);
        clock.set(secs(10));
        assertThat(gather(obs)).isNull();

        // flag flips back; the late ack refers to a cleared block and must be a no-op
        stubConfig(true);
        invokeRefreshEnabledFlag(obs);
        obs.onBlockAcknowledge(1L);
        clock.set(secs(20));
        assertThat(gather(obs)).isNull();
    }

    @Test
    void testAllHooks_whenDisabled_storeNothing() {
        final BlockStreamingObs obs = makeObs(false);

        clock.set(secs(1));
        obs.onBlockInit(1L);
        obs.onBlockOpen(1L);
        obs.onBlockItemAdd(1L, 0, 100, false);
        obs.onBlockItemsSend(1L, 0, 0, secs(1), secs(1) + MILLI);
        obs.onBlockClose(1L);
        obs.onBlockAcknowledge(1L);

        // even when re-enabled, nothing was tracked
        stubConfig(true);
        invokeRefreshEnabledFlag(obs);
        clock.set(secs(10));
        assertThat(gather(obs)).isNull();
    }

    // ── leak guard / abandoned blocks ───────────────────────────────────────────

    @Test
    void testNeverAckedBlock_isEvictedAsAbandoned() {
        final BlockStreamingObs obs = makeObs(true);

        clock.set(secs(1));
        obs.onBlockInit(1L); // never acked (e.g. block node down)

        clock.set(secs((5 * 60) + 1)); // past the 5-minute abandon threshold
        final String report = gather(obs);
        assertThat(report).isNotNull();
        assertThat(report).contains("Abandoned { (Unit:COUNT|Sum:1) }");

        // the entry is gone: a later ack is a no-op and a later gather has nothing to report
        obs.onBlockAcknowledge(1L);
        clock.set(secs(400));
        assertThat(gather(obs)).isNull();
    }

    @Test
    void testUnackedBlockYoungerThanThreshold_isRetained() {
        final BlockStreamingObs obs = makeObs(true);

        clock.set(secs(1));
        obs.onBlockInit(1L);

        clock.set(secs(100)); // older than the gather grace, far younger than the abandon threshold
        assertThat(gather(obs)).isNull(); // not abandoned, no buckets -> nothing to report

        // the block can still complete its lifecycle later
        obs.onBlockAcknowledge(1L);
        clock.set(secs(110));
        final String report = gather(obs);
        assertThat(report).isNotNull();
        assertThat(report).contains("Acknowledged { (Unit:COUNT|Sum:1) }");
    }

    // ── concurrency ─────────────────────────────────────────────────────────────

    @Test
    void testConcurrentItemAdds_distinctIndices_allCounted() throws InterruptedException {
        final BlockStreamingObs obs = makeObs(true);
        final int threads = 8;
        final int itemsPerThread = 100;

        clock.set(secs(1));
        obs.onBlockInit(1L);
        obs.onBlockOpen(1L);

        runConcurrently(threads, t -> {
            for (int i = 0; i < itemsPerThread; i++) {
                obs.onBlockItemAdd(1L, t * itemsPerThread + i, 10, false);
            }
        });

        obs.onBlockAcknowledge(1L);
        clock.set(secs(10));
        final String report = gather(obs);

        assertThat(report).isNotNull();
        assertThat(report).contains("ItemsPerBlock { (Unit:COUNT|Samples:1|Sum:800|");
        assertThat(report).contains("ItemSize { (Unit:BYTES|Samples:800|Sum:8000|");
        assertThat(report).contains("Created-Total { (Unit:COUNT|Sum:800)(Unit:BYTES|Sum:8000) }");
    }

    @Test
    void testConcurrentItemAdds_collidingIndices_exactlyOneWinsPerIndex() throws InterruptedException {
        final BlockStreamingObs obs = makeObs(true);
        final int threads = 8;
        final int items = 100;

        clock.set(secs(1));
        obs.onBlockInit(1L);
        obs.onBlockOpen(1L);

        runConcurrently(threads, t -> {
            for (int i = 0; i < items; i++) {
                obs.onBlockItemAdd(1L, i, 10, false); // every thread adds the same 100 indices
            }
        });

        obs.onBlockAcknowledge(1L);
        clock.set(secs(10));
        final String report = gather(obs);

        assertThat(report).isNotNull();
        assertThat(report).contains("ItemsPerBlock { (Unit:COUNT|Samples:1|Sum:100|");
        assertThat(report).contains("ItemSize { (Unit:BYTES|Samples:100|Sum:1000|");
        assertThat(report).contains("Created-Total { (Unit:COUNT|Sum:100)(Unit:BYTES|Sum:1000) }");
    }

    /** Regression guard for the ack-vs-gather race: racing the two must never throw (the old code
     * could throw IllegalStateException into the ack path) and every block is drained exactly once. */
    @Test
    void testAckAndGatherRacing_neverThrows_andEveryBlockIsDrainedExactlyOnce() throws InterruptedException {
        final BlockStreamingObs obs = makeObs(true);

        for (long block = 0; block < 50; block++) {
            clock.advanceSeconds(10);
            obs.onBlockInit(block);
            obs.onBlockOpen(block);
            obs.onBlockItemAdd(block, 0, 10, false);
            clock.advanceSeconds(5);

            final long blockNum = block;
            runConcurrently(2, t -> {
                if (t == 0) {
                    obs.onBlockAcknowledge(blockNum);
                } else {
                    invokeGather(obs);
                }
            });
        }

        // a final gather drains whatever remains; after that there must be nothing left
        clock.advanceSeconds(10);
        assertThat(gather(obs)).isNotNull();
        clock.advanceSeconds(10);
        assertThat(gather(obs)).isNull();
    }

    // ── idempotency / unknown-block smoke tests ─────────────────────────────────

    @Test
    void testOnBlockOpen_onlyFirstCallCounts() {
        final BlockStreamingObs obs = makeObs(true);

        clock.set(secs(1));
        obs.onBlockInit(1L);
        clock.set(secs(1) + MILLI);
        obs.onBlockOpen(1L);
        clock.set(secs(1) + 5 * MILLI);
        obs.onBlockOpen(1L); // must not overwrite the first open tick
        obs.onBlockAcknowledge(1L);

        clock.set(secs(10));
        final String report = gather(obs);

        assertThat(report).isNotNull();
        assertThat(report).contains("InitToOpen { (Unit:NANOS|Samples:1|Sum:1000000|");
        assertThat(report).contains("Opened { (Unit:COUNT|Sum:1) }");
    }

    @Test
    void testOnBlockItemAdd_duplicateIndex_firstWins() {
        final BlockStreamingObs obs = makeObs(true);

        clock.set(secs(1));
        obs.onBlockInit(1L);
        obs.onBlockOpen(1L);
        obs.onBlockItemAdd(1L, 0, 100, false);
        obs.onBlockItemAdd(1L, 0, 200, false); // duplicate index: ignored, not double-counted
        obs.onBlockAcknowledge(1L);

        clock.set(secs(10));
        final String report = gather(obs);

        assertThat(report).isNotNull();
        assertThat(report).contains("ItemsPerBlock { (Unit:COUNT|Samples:1|Sum:1|");
        assertThat(report).contains("ItemSize { (Unit:BYTES|Samples:1|Sum:100|");
        assertThat(report).contains("Created-Total { (Unit:COUNT|Sum:1)(Unit:BYTES|Sum:100) }");
    }

    @Test
    void testUnknownBlock_allHooksAreNoOps() {
        final BlockStreamingObs obs = makeObs(true);

        clock.set(secs(1));
        obs.onBlockOpen(999L);
        obs.onBlockItemAdd(999L, 0, 64, false);
        obs.onBlockItemAdd(999L, 0, 64, true);
        obs.onBlockItemsSend(999L, 0, 5, secs(1), secs(1) + MILLI);
        obs.onBlockHeaderSend(999L, secs(1), secs(1) + MILLI);
        obs.onBlockEndSend(999L, secs(1), secs(1) + MILLI);
        obs.onBlockClose(999L);
        obs.onBlockAcknowledge(999L);
        obs.onBlockProofCreate(999L);
        obs.onBlockFooterCreate(999L);

        clock.set(secs(10));
        assertThat(gather(obs)).isNull();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private BlockStreamingObs makeObs(final boolean enabled) {
        stubConfig(enabled);
        final BlockStreamingObs obs = new BlockStreamingObs(configProvider, clock);
        created.add(obs);
        return obs;
    }

    /**
     * Drives one gather cycle and returns the report it logged on this call, or {@code null} if it
     * logged nothing. Reads the captured INFO output, so the test verifies what is actually logged
     * rather than a returned value.
     */
    private String gather(final BlockStreamingObs obs) {
        final int before = logCaptor.infoLogs().size();
        invokeGather(obs);
        final List<String> logs = logCaptor.infoLogs();
        assertThat(logs.size()).isLessThanOrEqualTo(before + 1); // a gather logs at most one report
        return logs.size() > before ? logs.get(logs.size() - 1) : null;
    }

    /** Invokes the private {@code gatherAndLogObsData()}; thread-safe (MethodHandles are immutable). */
    private static void invokeGather(final BlockStreamingObs obs) {
        try {
            gatherHandle.invoke(obs);
        } catch (final Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** Invokes the private {@code refreshEnabledFlag()} so tests can simulate a config toggle. */
    private static void invokeRefreshEnabledFlag(final BlockStreamingObs obs) {
        try {
            refreshEnabledFlagHandle.invoke(obs);
        } catch (final Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private void stubConfig(final boolean enabled) {
        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.enhancedObservabilityEnabled", String.valueOf(enabled))
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
    }

    /** Runs {@code task} on the given number of threads, released simultaneously by a start latch. */
    private void runConcurrently(final int threads, final IntConsumer task) throws InterruptedException {
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            final int threadIndex = t;
            new Thread(() -> {
                        try {
                            start.await();
                            task.accept(threadIndex);
                        } catch (final Throwable e) {
                            failures.add(e);
                        } finally {
                            done.countDown();
                        }
                    })
                    .start();
        }

        start.countDown();
        done.await();
        assertThat(failures).isEmpty();
    }

    private static long secs(final long seconds) {
        return seconds * 1_000_000_000L;
    }

    /** Mutable nano-clock injected into the obs so tests fully control every timestamp. */
    private static final class TestClock implements LongSupplier {
        private volatile long nanos = 0;

        @Override
        public long getAsLong() {
            return nanos;
        }

        void set(final long newNanos) {
            nanos = newNanos;
        }

        void advanceSeconds(final long seconds) {
            nanos += secs(seconds);
        }
    }
}

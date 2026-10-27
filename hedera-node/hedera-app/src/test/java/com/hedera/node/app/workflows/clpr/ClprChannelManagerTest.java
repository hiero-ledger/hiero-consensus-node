// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprPeerEndpoints;
import com.hedera.hapi.node.state.clpr.ClprPeerEndpointsEntry;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.clpr.ReadableChannelStore;
import com.hedera.node.app.service.clpr.ReadableLedgerConfigurationStore;
import com.hedera.node.app.store.ReadableStoreFactoryImpl;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.testfixtures.ClprConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.state.State;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprChannelManagerTest {
    private static final Bytes CHANNEL_ID_1 = Bytes.wrap("test_channel_1");
    private static final Bytes CHANNEL_ID_2 = Bytes.wrap("test_channel_2");

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private VersionedConfiguration versionedConfig;

    @Mock
    private Supplier<AutoCloseableWrapper<State>> stateAccessor;

    @Mock
    private ClprSynchronizer synchronizer;

    @Mock
    private ClprEndpointClientCache clientCache;

    @TempDir
    private Path tempDir;

    private ClprChannelManager subject;

    @BeforeEach
    void setUp() {
        given(configProvider.getConfiguration()).willReturn(versionedConfig);
        given(versionedConfig.getConfigData(ClprConfig.class))
                .willReturn(defaultClprConfig().build());
        Mockito.reset(synchronizer);
        subject = new ClprChannelManager(configProvider, stateAccessor, synchronizer, clientCache);
    }

    @AfterEach
    void tearDown() {
        // Per-channel sync timers are scheduled on the real scheduler once a channel is
        // activated on a started manager; shut it down so leaked ticks don't fire across tests.
        subject.stop();
    }

    @Nested
    @DisplayName("start()")
    class StartTests {

        @Test
        @DisplayName("does not start when CLPR is disabled")
        void startWhenCliprIsDisabledShouldNotStart() {
            assertFalse(subject.started());
            final var config = defaultClprConfig().enabled(false).build();
            given(versionedConfig.getConfigData(ClprConfig.class)).willReturn(config);

            subject.start();

            assertFalse(subject.started(), "Expected channel manager to not be started");
        }

        @Test
        @DisplayName("starts when CLPR is enabled and schedules no sync timer until a channel activates")
        void startWhenCliprIsEnabledShouldStart() {
            assertFalse(subject.started());
            subject.start();
            assertTrue(subject.started(), "Expected channel manager to be started");
            // No channel is active yet, so no per-channel sync timer exists.
            assertNull(subject.syncTickFuture(CHANNEL_ID_1));
        }

        @Test
        @DisplayName("is idempotent — a second call schedules no additional tasks")
        void startIsIdempotent() {
            // The only periodic task is the peer-endpoints flush; with no active channel the
            // scheduler queue holds exactly that one task after start().
            subject.start();
            assertTrue(subject.started());
            final var executor = (ScheduledThreadPoolExecutor) subject.scheduler();
            final int scheduledAfterFirstStart = executor.getQueue().size();

            // The platform can re-reach ACTIVE with no intervening stop() (e.g. after a reconnect);
            // the guard must make the re-entry a no-op rather than leak a duplicate flush task.
            subject.start();

            assertTrue(subject.started());
            assertEquals(
                    scheduledAfterFirstStart,
                    executor.getQueue().size(),
                    "Second start() must not schedule duplicate tasks");
        }
    }

    @Nested
    @DisplayName("stop()")
    class StopTests {

        @Test
        @DisplayName("is a no-op when not started yet")
        void stopWhenNotStartedYet() {
            subject.stop();

            assertThat(subject.started()).isFalse();
            assertThat(subject.scheduler().isTerminated()).isTrue();
        }

        @Test
        @DisplayName("shuts down the scheduler when started")
        void stopWhenStarted() {
            subject.start();
            assertThat(subject.scheduler().isTerminated()).isFalse();

            subject.stop();

            assertThat(subject.started()).isFalse();
            assertThat(subject.scheduler().isShutdown()).isTrue();
        }

        @Test
        @DisplayName("is idempotent")
        void stopIsIdempotent() {
            subject.stop();
            assertThatCode(subject::stop).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("onChannelActivated() scheduling")
    class ActivationSchedulingTests {

        @Test
        @DisplayName("schedules a per-channel tick at the default interval on a started manager")
        void activationSchedulesDefaultIntervalTick() {
            subject.start();

            subject.onChannelActivated(CHANNEL_ID_1);

            assertNotNull(subject.syncTickFuture(CHANNEL_ID_1), "Expected a per-channel tick to be scheduled");
        }

        @Test
        @DisplayName("does not schedule a tick when the manager has not started")
        void activationBeforeStartSchedulesNoTick() {
            subject.onChannelActivated(CHANNEL_ID_1);

            assertThat(subject.knownChannelsIds()).contains(CHANNEL_ID_1);
            assertNull(subject.syncTickFuture(CHANNEL_ID_1));
        }

        @Test
        @DisplayName("start() schedules ticks for channels that activated before it started")
        void startSchedulesTicksForPreStartChannels() {
            // Activation happens first, while the orchestrator is not yet started.
            subject.onChannelActivated(CHANNEL_ID_1);
            assertNull(subject.syncTickFuture(CHANNEL_ID_1));

            subject.start();

            assertNotNull(
                    subject.syncTickFuture(CHANNEL_ID_1),
                    "Expected start() to schedule a tick for the pre-start channel");
        }
    }

    @Nested
    @DisplayName("syncChannel(), initiateSync()")
    class SyncTickTests {

        @Test
        @DisplayName("Clpr got disabled, so nothing gets done on sync tick")
        void clprDisabledNoActionOnSyncTick() {
            given(versionedConfig.getConfigData(ClprConfig.class))
                    .willReturn(defaultClprConfig().enabled(false).build());

            try (var mocked = mockConstruction(ReadableStoreFactoryImpl.class)) {
                subject.syncChannel(CHANNEL_ID_1);
                assertThat(mocked.constructed())
                        .as("Disabled syncTick must not touch state")
                        .isEmpty();
            }
            verify(synchronizer, never()).synchronize(any(), any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("null state, nothing synced")
        void nullStateNoActionOnSyncTick() {
            given(stateAccessor.get()).willReturn(new AutoCloseableWrapper<>(null, () -> {}));

            try (var mocked = mockConstruction(ReadableStoreFactoryImpl.class)) {
                subject.syncChannel(CHANNEL_ID_1);
                assertThat(mocked.constructed())
                        .as("syncTick with null state must not build a store factory")
                        .isEmpty();
            }
            verify(synchronizer, never()).synchronize(any(), any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("known channel is null in state")
        void nullChannelNoActionOnSyncTick() {
            subject.onChannelActivated(CHANNEL_ID_1);
            final var channelStore = mock(ReadableChannelStore.class);
            lenient().when(channelStore.getChannel(CHANNEL_ID_1)).thenReturn(null);

            try (var _ = givenMockedStateForSync(channelStore, ledgerConfigWithMaxPeerEndpoints(0))) {
                subject.syncChannel(CHANNEL_ID_1);

                // Null channel is not auto-removed; cleanup is driven by onChannelClosed only.
                assertThat(subject.knownChannelsIds()).contains(CHANNEL_ID_1);
                assertThat(subject.getKnownEndpoints(CHANNEL_ID_1)).isEmpty();
            }
            verify(synchronizer, never()).synchronize(any(), any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("channel is closed, should be removed from known channels")
        void closedChannelNoActionOnSyncTick() {
            subject.onChannelActivated(CHANNEL_ID_1);
            final var closedConn = makeChannel(CHANNEL_ID_1, ClprChannelStatus.CLOSED, 0L, 0L);
            final var channelStore = mock(ReadableChannelStore.class);
            lenient().when(channelStore.getChannel(CHANNEL_ID_1)).thenReturn(closedConn);

            try (var _ = givenMockedStateForSync(channelStore, ledgerConfigWithMaxPeerEndpoints(0))) {
                subject.syncChannel(CHANNEL_ID_1);

                assertThat(subject.knownChannelsIds()).doesNotContain(CHANNEL_ID_1);
            }
            verify(synchronizer, never()).synchronize(any(), any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("channel is pending, no sync happen")
        void pendingChannelNoActionOnSyncTick() {
            final var pendingConn = makeChannel(CHANNEL_ID_1, ClprChannelStatus.PENDING, 0L, 0L);

            subject.initiateSync(pendingConn);

            // initiateSync short-circuits synchronously for PENDING, so synchronize never runs.
            verify(synchronizer, never()).synchronize(any(), any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("peer ledger acked messages is ahead, no sync happen")
        void peerLedgerMessagesAheadNoActionOnSyncTick() {
            // nextMessageId == ackedMessageId + 1 means no pending outbound messages.
            final var caughtUpConn = makeChannel(CHANNEL_ID_1, ClprChannelStatus.ACTIVE, 5L, 6L);

            subject.initiateSync(caughtUpConn);

            verify(synchronizer, never()).synchronize(any(), any(), anyLong(), anyLong());
        }

        /**
         * This test simulates the scenario when a channel has an ongoing sync but
         * a new sync is triggered for it. In this case, the second sync is skipped.
         */
        @Test
        @DisplayName("channel has an ongoing sync")
        void channelHasOngoingSyncNoActionOnSyncTick() {
            final var conn = makeActiveChannelWithPendingMessages(CHANNEL_ID_1);
            final var callsCount = new AtomicInteger(0);
            final var longSyncEmulator = new CountDownLatch(1);

            // Block the first sync to keep CHANNEL_ID_1 in ongoingChannelSyncs.
            willAnswer(invocation -> {
                        callsCount.incrementAndGet();
                        longSyncEmulator.await();
                        return null;
                    })
                    .given(synchronizer)
                    .synchronize(eq(conn), any(), anyLong(), anyLong());

            subject.initiateSync(conn);
            await().atMost(Duration.ofMillis(200)).until(() -> callsCount.get() == 1);
            try {
                subject.initiateSync(conn);
                // ongoingChannelSyncs guard is synchronous — the second call must not be scheduled.
                verify(synchronizer, after(200).times(1)).synchronize(eq(conn), any(), anyLong(), anyLong());
            } finally {
                longSyncEmulator.countDown();
                assertThat(callsCount.get()).isEqualTo(1);
            }
        }

        /**
         * With every sync slot busy, a Channel that becomes ready waits for a slot rather than being skipped, and runs
         * as soon as one frees.
         */
        @Test
        @DisplayName("max concurrent sync operations reached, the next sync waits for a free slot")
        void maxConcurrentSyncOperationsReachedNextSyncWaits() {
            final int maxConcurrentSyncs = 2;
            given(versionedConfig.getConfigData(ClprConfig.class))
                    .willReturn(defaultClprConfig()
                            .maxConcurrentSyncs(maxConcurrentSyncs)
                            .build());
            subject = new ClprChannelManager(configProvider, stateAccessor, synchronizer, clientCache);

            final List<ClprChannel> channels = IntStream.range(0, maxConcurrentSyncs + 1)
                    .mapToObj((id) -> makeActiveChannelWithPendingMessages(Bytes.wrap("channel_" + id)))
                    .toList();

            final var syncCallsCount = new AtomicInteger(0);
            final var longSyncEmulator = new CountDownLatch(1);
            willAnswer(invocation -> {
                        syncCallsCount.incrementAndGet();
                        longSyncEmulator.await();
                        return SyncOutcome.IDLE;
                    })
                    .given(synchronizer)
                    .synchronize(any(), any(), anyLong(), anyLong());

            channels.forEach(subject::initiateSync);
            await().atMost(Duration.ofMillis(200)).until(() -> syncCallsCount.get() == maxConcurrentSyncs);
            verify(synchronizer, after(200).never()).synchronize(eq(channels.getLast()), any(), anyLong(), anyLong());

            longSyncEmulator.countDown();

            verify(synchronizer, timeout(500)).synchronize(eq(channels.getLast()), any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("sync slots go to waiting channels in the order they became ready")
        void syncSlotsGoToWaitingChannelsInOrder() {
            given(versionedConfig.getConfigData(ClprConfig.class))
                    .willReturn(defaultClprConfig().maxConcurrentSyncs(1).build());
            subject = new ClprChannelManager(configProvider, stateAccessor, synchronizer, clientCache);
            final var first = makeActiveChannelWithPendingMessages(CHANNEL_ID_1);
            final var second = makeActiveChannelWithPendingMessages(CHANNEL_ID_2);
            final var third = makeActiveChannelWithPendingMessages(Bytes.wrap("test_channel_3"));
            final var release = new CountDownLatch(1);
            final var order = new java.util.concurrent.CopyOnWriteArrayList<Bytes>();
            willAnswer(invocation -> {
                        final ClprChannel channel = invocation.getArgument(0);
                        order.add(channel.channelId());
                        if (channel.channelId().equals(CHANNEL_ID_1)) {
                            release.await();
                        }
                        return SyncOutcome.IDLE;
                    })
                    .given(synchronizer)
                    .synchronize(any(), any(), anyLong(), anyLong());

            subject.initiateSync(first);
            await().atMost(Duration.ofMillis(200)).until(() -> order.size() == 1);
            subject.initiateSync(second);
            subject.initiateSync(third);
            release.countDown();

            await().atMost(Duration.ofMillis(500)).until(() -> order.size() == 3);
            assertThat(order).containsExactly(CHANNEL_ID_1, CHANNEL_ID_2, third.channelId());
        }

        @Test
        @DisplayName("a channel whose sync failed backs off until its delay passes")
        void failedChannelBacksOff() {
            final var clock = new AtomicLong(0L);
            given(versionedConfig.getConfigData(ClprConfig.class))
                    .willReturn(defaultClprConfig()
                            .retryInitialDelayMs(1_000L)
                            .retryMaxDelayMs(30_000L)
                            .build());
            subject = new ClprChannelManager(
                    configProvider, stateAccessor, synchronizer, clientCache, ClprSyncMetrics.noop(), clock::get);
            given(synchronizer.synchronize(any(), any(), anyLong(), anyLong())).willReturn(SyncOutcome.FAILURE);
            final var conn = makeActiveChannelWithPendingMessages(CHANNEL_ID_1);

            subject.initiateSync(conn);
            verify(synchronizer, timeout(200)).synchronize(eq(conn), any(), anyLong(), anyLong());

            // Within the 1s backoff: skipped
            clock.set(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(999));
            await().atMost(Duration.ofMillis(200)).untilAsserted(() -> {
                subject.initiateSync(conn);
                verify(synchronizer, times(1)).synchronize(eq(conn), any(), anyLong(), anyLong());
            });

            // After it: synced again
            clock.set(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1_001));
            await().atMost(Duration.ofMillis(500)).untilAsserted(() -> {
                subject.initiateSync(conn);
                verify(synchronizer, times(2)).synchronize(eq(conn), any(), anyLong(), anyLong());
            });
        }

        @Test
        @DisplayName("a channel the operator denies is never synced")
        void deniedChannelIsNotSynced() {
            final var conn = makeActiveChannelWithPendingMessages(CHANNEL_ID_1);
            given(versionedConfig.getConfigData(ClprConfig.class))
                    .willReturn(defaultClprConfig()
                            .deniedChannelIds(
                                    java.util.Set.of("0x" + CHANNEL_ID_1.toHex().toUpperCase()))
                            .build());

            subject.initiateSync(conn);

            verify(synchronizer, after(200).never()).synchronize(any(), any(), anyLong(), anyLong());
            assertThat(subject.isChannelDenied(CHANNEL_ID_1)).isTrue();
            assertThat(subject.isChannelDenied(CHANNEL_ID_2)).isFalse();
        }

        @Test
        @DisplayName("a peer endpoint the operator denies is never dialed")
        void deniedEndpointIsNotDialed() {
            final var allowed = makeEndpoint("203.0.113.2", 50211);
            final var conn = makeChannel(CHANNEL_ID_1, ClprChannelStatus.ACTIVE, 0L, 2L)
                    .copyBuilder()
                    .endpointManifest(ClprEndpointManifest.newBuilder()
                            .version(1L)
                            .endpoints(List.of(makeEndpoint("203.0.113.1", 50211), allowed))
                            .build())
                    .build();
            given(versionedConfig.getConfigData(ClprConfig.class))
                    .willReturn(defaultClprConfig()
                            .deniedPeerEndpoints(java.util.Set.of("203.0.113.1"))
                            .build());

            subject.initiateSync(conn);

            verify(synchronizer, timeout(200)).synchronize(eq(conn), eq(List.of(allowed)), anyLong(), anyLong());
        }

        @Test
        @DisplayName("channel whose cached peer manifest has no endpoints is skipped without dispatching a sync")
        void channelWithoutDialTargetsIsSkipped() {
            // Pending messages, but no endpoint manifest to dial from.
            final var conn = makeChannel(CHANNEL_ID_1, ClprChannelStatus.ACTIVE, 0L, 2L);

            subject.initiateSync(conn);

            verify(synchronizer, after(200).never()).synchronize(any(), any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("sync is performed, channel is removed from ongoing syncs and semaphore is released")
        void syncPerformed() {
            final var conn = makeActiveChannelWithPendingMessages(CHANNEL_ID_1);

            subject.initiateSync(conn);

            verify(synchronizer, timeout(200)).synchronize(eq(conn), any(), anyLong(), anyLong());

            // After completion, the slot must be released so a follow-up sync for the same
            // channel can run — confirms semaphore release and ongoingChannelSyncs cleanup.
            subject.initiateSync(conn);
            verify(synchronizer, timeout(200).times(2)).synchronize(eq(conn), any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("disallowed peer endpoints are never dialed")
        void disallowedEndpointsAreNotDialed() {
            final var publicEndpoint = makeEndpoint("203.0.113.1", 50211);
            final var conn = makeChannel(CHANNEL_ID_1, ClprChannelStatus.ACTIVE, 0L, 2L)
                    .copyBuilder()
                    .endpointManifest(ClprEndpointManifest.newBuilder()
                            .version(1L)
                            .endpoints(List.of(
                                    makeEndpoint("127.0.0.1", 50211), publicEndpoint, makeEndpoint("10.0.0.1", 50211)))
                            .build())
                    .build();

            subject.initiateSync(conn);

            verify(synchronizer, timeout(200)).synchronize(eq(conn), eq(List.of(publicEndpoint)), anyLong(), anyLong());
        }

        @Test
        @DisplayName("a channel whose peer endpoints are all disallowed is not synced")
        void channelWithOnlyDisallowedEndpointsIsNotSynced() {
            final var conn = makeChannel(CHANNEL_ID_1, ClprChannelStatus.ACTIVE, 0L, 2L)
                    .copyBuilder()
                    .endpointManifest(ClprEndpointManifest.newBuilder()
                            .version(1L)
                            .endpoints(List.of(makeEndpoint("169.254.169.254", 80)))
                            .build())
                    .build();

            subject.initiateSync(conn);

            verify(synchronizer, after(200).never()).synchronize(any(), any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("sync is performed concurrently for two different channels")
        void syncPerformedConcurrently() {
            final var conn1 = makeActiveChannelWithPendingMessages(CHANNEL_ID_1);
            final var conn2 = makeActiveChannelWithPendingMessages(CHANNEL_ID_2);

            // configure the synchronizer with a small delay to simulate some blocking behavior.
            doAnswer(_ -> {
                        Thread.sleep(100);
                        return null;
                    })
                    .when(synchronizer)
                    .synchronize(any(), any(), anyLong(), anyLong());

            subject.initiateSync(conn1);
            subject.initiateSync(conn2);

            verify(synchronizer, timeout(200).times(1)).synchronize(eq(conn1), any(), anyLong(), anyLong());
            verify(synchronizer, timeout(200).times(1)).synchronize(eq(conn2), any(), anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("channel management")
    class ChannelManagementTests {

        @Test
        @DisplayName("onChannelActivated tracks new channels")
        void onChannelActivated() {
            subject.onChannelActivated(CHANNEL_ID_1);
            subject.onChannelActivated(CHANNEL_ID_2);

            assertThat(subject.knownChannelsIds()).containsExactlyInAnyOrder(CHANNEL_ID_1, CHANNEL_ID_2);
        }

        @Test
        @DisplayName("onChannelClosed removes the closed channel")
        void onChannelClosed() {
            subject.onChannelActivated(CHANNEL_ID_1);
            subject.onChannelActivated(CHANNEL_ID_2);
            subject.onChannelClosed(CHANNEL_ID_1);

            assertThat(subject.knownChannelsIds()).containsExactlyInAnyOrder(CHANNEL_ID_2);
        }

        @Test
        @DisplayName("seedPeerEndpoints with an empty list clears a previously cached endpoint")
        void seedPeerEndpointsWithEmptyListClearsPreviousCache() {
            subject.seedPeerEndpoints(CHANNEL_ID_1, List.of(makeEndpoint("203.0.113.7", 50211)));
            assertThat(subject.getKnownEndpoints(CHANNEL_ID_1)).isNotEmpty();

            subject.seedPeerEndpoints(CHANNEL_ID_1, Collections.emptyList());

            assertThat(subject.getKnownEndpoints(CHANNEL_ID_1)).isEmpty();
        }
    }

    private ClprConfigBuilder defaultClprConfig() {
        // Isolate the node-local channel cache to a per-test temp file so the orchestrator's
        // disk persistence/rehydration doesn't leak across tests (or runs) via the default path.
        return ClprConfigBuilder.newBuilder()
                .enabled(true)
                .peerEndpointsFile(tempDir.resolve("clpr-peer-endpoints.json").toString());
    }

    /** Builds a {@link ReadableNodeStore} mock whose {@code sizeOfState()} returns {@code size}. */
    private static ReadableNodeStore nodeStoreWithSize(final long size) {
        final var nodeStore = mock(ReadableNodeStore.class);
        lenient().when(nodeStore.sizeOfState()).thenReturn(size);
        return nodeStore;
    }

    private static ClprLedgerConfiguration ledgerConfigWithMaxPeerEndpoints(final int maxPeerEndpoints) {
        return ClprLedgerConfiguration.newBuilder()
                .throttles(ClprThrottles.newBuilder()
                        .maxPeerEndpoints(maxPeerEndpoints)
                        .build())
                .build();
    }

    /**
     * Wires the readable factory to additionally return the supplied {@link ReadableChannelStore}
     * so {@code syncTick()} can look up channels by id.
     */
    private MockedConstruction<ReadableStoreFactoryImpl> givenMockedStateForSync(
            final ReadableChannelStore channelStore, final ClprLedgerConfiguration ledgerConfig) {
        final var state = mock(State.class);
        lenient().when(stateAccessor.get()).thenReturn(new AutoCloseableWrapper<>(state, () -> {}));

        final var configStore = mock(ReadableLedgerConfigurationStore.class);
        lenient().when(configStore.getConfiguration()).thenReturn(ledgerConfig);

        return mockConstruction(ReadableStoreFactoryImpl.class, (factory, ctx) -> {
            lenient()
                    .when(factory.readableStore(ReadableLedgerConfigurationStore.class))
                    .thenReturn(configStore);
            lenient().when(factory.readableStore(ReadableChannelStore.class)).thenReturn(channelStore);
        });
    }

    private static ClprChannel makeActiveChannelWithPendingMessages(final Bytes channelId) {
        // A dialable channel: its cached peer manifest carries an endpoint for the sync to target.
        return makeChannel(channelId, ClprChannelStatus.ACTIVE, 0L, 2L)
                .copyBuilder()
                .endpointManifest(ClprEndpointManifest.newBuilder()
                        .version(1L)
                        .endpoints(List.of(makeEndpoint("203.0.113.1", 50211)))
                        .build())
                .build();
    }

    private static ClprChannel makeChannel(
            final Bytes channelId, final ClprChannelStatus status, final long acked, final long next) {
        return ClprChannel.newBuilder()
                .channelId(channelId)
                .status(status)
                .ackedMessageId(acked)
                .nextMessageId(next)
                .build();
    }

    private static ClprEndpoint makeEndpoint(final String ip, final int port) {
        return ClprEndpoint.newBuilder()
                .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                        .ipAddress(ip)
                        .port(port)
                        .build())
                .build();
    }

    /**
     * Variant of {@link #givenMockedStateForSync} for rehydration tests: wires the readable factory
     * to return the supplied channel + node stores so {@code start()}'s disk rehydration can
     * check channel status and recover the interval from {@code peer_throttles}.
     */
    private MockedConstruction<ReadableStoreFactoryImpl> givenMockedStateForRehydration(
            final ReadableChannelStore channelStore, final ReadableNodeStore nodeStore) {
        final var state = mock(State.class);
        lenient().when(stateAccessor.get()).thenReturn(new AutoCloseableWrapper<>(state, () -> {}));
        return mockConstruction(ReadableStoreFactoryImpl.class, (factory, ctx) -> {
            lenient().when(factory.readableStore(ReadableChannelStore.class)).thenReturn(channelStore);
            lenient().when(factory.readableStore(ReadableNodeStore.class)).thenReturn(nodeStore);
        });
    }

    private static boolean cacheHas(final Path cacheFile, final Bytes channelId) {
        return cacheEntry(cacheFile, channelId) != null;
    }

    private static boolean cacheHasEndpoint(final Path cacheFile, final Bytes channelId) {
        final var entry = cacheEntry(cacheFile, channelId);
        return entry != null && !entry.endpoints().isEmpty();
    }

    private static ClprPeerEndpointsEntry cacheEntry(final Path cacheFile, final Bytes channelId) {
        try {
            if (!Files.exists(cacheFile)) {
                return null;
            }
            try (final var in = Files.newInputStream(cacheFile)) {
                final var cache = ClprPeerEndpoints.JSON.parse(new ReadableStreamingData(in));
                return cache.entries().stream()
                        .filter(e -> e.channelId().equals(channelId))
                        .findFirst()
                        .orElse(null);
            }
        } catch (final Exception e) {
            return null;
        }
    }

    @Nested
    @DisplayName("disk persistence + rehydration")
    class DiskRehydrationTests {

        @Test
        void ignoresCacheWithUnknownFields() throws Exception {
            final var cacheFile = tempDir.resolve("clpr-peer-endpoints.json");
            final var cached = ClprPeerEndpoints.newBuilder()
                    .entries(ClprPeerEndpointsEntry.newBuilder()
                            .channelId(CHANNEL_ID_1)
                            .endpoints(makeEndpoint("203.0.113.7", 50211))
                            .build())
                    .build();
            final var json = ClprPeerEndpoints.JSON.toJSON(cached);
            Files.writeString(cacheFile, json.replaceFirst("\\{", "{\"unknownField\":1,"));

            subject.start();

            assertThat(subject.knownChannelsIds()).isEmpty();
            assertThat(subject.getKnownEndpoints(CHANNEL_ID_1)).isEmpty();
            verifyNoInteractions(stateAccessor);
        }

        @Test
        @DisplayName("persists channels + endpoints to disk and rehydrates them on a fresh manager")
        void persistsAndRehydratesAcrossRestart() {
            final var cacheFile = tempDir.resolve("clpr-peer-endpoints.json");
            final var endpoint = makeEndpoint("203.0.113.7", 50211);

            // Manager A: activate a channel and seed peer endpoints — both persist to disk.
            subject.start();
            subject.onChannelActivated(CHANNEL_ID_1);
            subject.seedPeerEndpoints(CHANNEL_ID_1, List.of(endpoint));
            // Disk writes are offloaded to the scheduler; wait until the file reflects the endpoint.
            await().atMost(Duration.ofSeconds(5)).until(() -> cacheHasEndpoint(cacheFile, CHANNEL_ID_1));
            subject.stop();

            // Manager B: fresh instance, same (temp) cache file. It must rebuild the registry, the
            // peer endpoint cache, the interval, and the timer purely from disk + committed state.
            final var managerB = new ClprChannelManager(configProvider, stateAccessor, synchronizer, clientCache);
            final var channelStore = mock(ReadableChannelStore.class);
            given(channelStore.getChannel(CHANNEL_ID_1))
                    .willReturn(ClprChannel.newBuilder()
                            .channelId(CHANNEL_ID_1)
                            .status(ClprChannelStatus.ACTIVE)
                            .peerThrottles(ClprThrottles.newBuilder().build())
                            .build());
            try (var _ = givenMockedStateForRehydration(channelStore, nodeStoreWithSize(10))) {
                managerB.start();

                assertThat(managerB.knownChannelsIds()).contains(CHANNEL_ID_1);
                assertThat(managerB.getKnownEndpoints(CHANNEL_ID_1)).containsExactly(endpoint);
                assertNotNull(managerB.syncTickFuture(CHANNEL_ID_1));
            } finally {
                managerB.stop();
            }
        }

        @Test
        @DisplayName("replacing cached endpoints with an empty manifest clears them on disk and after restart")
        void seedPeerEndpointsEmptyListClearsDiskCacheAndSurvivesRestart() {
            final var cacheFile = tempDir.resolve("clpr-peer-endpoints.json");
            final var endpoint = makeEndpoint("203.0.113.7", 50211);

            subject.start();
            subject.onChannelActivated(CHANNEL_ID_1);
            subject.seedPeerEndpoints(CHANNEL_ID_1, List.of(endpoint));
            await().atMost(Duration.ofSeconds(5)).until(() -> cacheHasEndpoint(cacheFile, CHANNEL_ID_1));

            // A non-empty-to-empty manifest update (e.g. manifest-only recovery, spec §8.1.4) must
            // flush the cleared cache to disk too, not leave the stale endpoint behind.
            subject.seedPeerEndpoints(CHANNEL_ID_1, List.of());
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> cacheHas(cacheFile, CHANNEL_ID_1) && !cacheHasEndpoint(cacheFile, CHANNEL_ID_1));
            subject.stop();

            final var managerB = new ClprChannelManager(configProvider, stateAccessor, synchronizer, clientCache);
            final var channelStore = mock(ReadableChannelStore.class);
            given(channelStore.getChannel(CHANNEL_ID_1))
                    .willReturn(ClprChannel.newBuilder()
                            .channelId(CHANNEL_ID_1)
                            .status(ClprChannelStatus.ACTIVE)
                            .peerThrottles(ClprThrottles.newBuilder().build())
                            .build());
            try (var _ = givenMockedStateForRehydration(channelStore, nodeStoreWithSize(10))) {
                managerB.start();

                assertThat(managerB.knownChannelsIds()).contains(CHANNEL_ID_1);
                assertThat(managerB.getKnownEndpoints(CHANNEL_ID_1)).isEmpty();
            } finally {
                managerB.stop();
            }
        }

        @Test
        @DisplayName("rehydration skips a channel that is CLOSED in committed state")
        void rehydrationSkipsClosed() {
            final var cacheFile = tempDir.resolve("clpr-peer-endpoints.json");
            subject.start();
            subject.onChannelActivated(CHANNEL_ID_1);
            await().atMost(Duration.ofSeconds(5)).until(() -> cacheHas(cacheFile, CHANNEL_ID_1));
            subject.stop();

            final var managerB = new ClprChannelManager(configProvider, stateAccessor, synchronizer, clientCache);
            final var channelStore = mock(ReadableChannelStore.class);
            given(channelStore.getChannel(CHANNEL_ID_1))
                    .willReturn(makeChannel(CHANNEL_ID_1, ClprChannelStatus.CLOSED, 0L, 0L));
            try (var _ = givenMockedStateForRehydration(channelStore, nodeStoreWithSize(10))) {
                managerB.start();

                assertThat(managerB.knownChannelsIds()).doesNotContain(CHANNEL_ID_1);
                assertNull(managerB.syncTickFuture(CHANNEL_ID_1));
            } finally {
                managerB.stop();
            }
        }
    }

    @Test
    void disablingClprBlocksOutboundWorkForAnExistingChannel() {
        // Populate an eligible channel and peer cache before disabling the master flag.
        final var channel = makeChannel(CHANNEL_ID_1, ClprChannelStatus.ACTIVE, 0L, 2L);
        subject.onChannelActivated(CHANNEL_ID_1);
        subject.seedPeerEndpoints(CHANNEL_ID_1, List.of(makeEndpoint("203.0.113.7", 50211)));
        Mockito.clearInvocations(stateAccessor, synchronizer, clientCache);
        given(versionedConfig.getConfigData(ClprConfig.class))
                .willReturn(defaultClprConfig().enabled(false).build());

        subject.start();
        subject.syncChannel(CHANNEL_ID_1);
        subject.initiateSync(channel);

        assertFalse(subject.started());
        assertNull(subject.syncTickFuture(CHANNEL_ID_1));
        assertThat(((ScheduledThreadPoolExecutor) subject.scheduler()).getQueue())
                .isEmpty();
        verifyNoInteractions(stateAccessor, synchronizer, clientCache);
    }

    @Test
    void disabledRestartIgnoresPersistedPeerCacheAndLeavesItUnchanged() throws Exception {
        final var cacheFile = tempDir.resolve("clpr-peer-endpoints.json");
        final var cached = ClprPeerEndpoints.newBuilder()
                .entries(List.of(ClprPeerEndpointsEntry.newBuilder()
                        .channelId(CHANNEL_ID_1)
                        .endpoints(List.of(makeEndpoint("203.0.113.7", 50211)))
                        .build()))
                .build();
        try (final var output = Files.newOutputStream(cacheFile)) {
            ClprPeerEndpoints.JSON.write(cached, new WritableStreamingData(output));
        }
        final var before = Files.readAllBytes(cacheFile);
        given(versionedConfig.getConfigData(ClprConfig.class))
                .willReturn(defaultClprConfig().enabled(false).build());

        subject.start();
        subject.syncChannel(CHANNEL_ID_1);

        assertFalse(subject.started());
        assertThat(subject.knownChannelsIds()).isEmpty();
        assertThat(subject.getKnownEndpoints(CHANNEL_ID_1)).isEmpty();
        assertThat(((ScheduledThreadPoolExecutor) subject.scheduler()).getQueue())
                .isEmpty();
        verifyNoInteractions(stateAccessor, synchronizer, clientCache);
        subject.stop();
        assertThat(Files.readAllBytes(cacheFile)).isEqualTo(before);
    }

    @Test
    @DisplayName("backoff doubles per consecutive failure up to the maximum")
    void backoffDoublesUpToMaximum() {
        assertThat(ClprChannelManager.backoffDelayMs(1, 1_000L, 30_000L)).isEqualTo(1_000L);
        assertThat(ClprChannelManager.backoffDelayMs(2, 1_000L, 30_000L)).isEqualTo(2_000L);
        assertThat(ClprChannelManager.backoffDelayMs(5, 1_000L, 30_000L)).isEqualTo(16_000L);
        assertThat(ClprChannelManager.backoffDelayMs(6, 1_000L, 30_000L)).isEqualTo(30_000L);
        assertThat(ClprChannelManager.backoffDelayMs(1_000, 1_000L, 30_000L)).isEqualTo(30_000L);
    }
}

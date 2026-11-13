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
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.ReadableChannelStore;
import com.hedera.node.app.service.clpr.ReadableLedgerConfigurationStore;
import com.hedera.node.app.store.ReadableStoreFactoryImpl;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.testfixtures.ClprConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.state.State;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprDeepDiveSyncAuditTest {
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
            // A channel tracked before start() gets its sync tick scheduled by start()
            subject.onChannelActivated(CHANNEL_ID_1);
            subject.start();
            assertTrue(subject.started());
            final var executor = (ScheduledThreadPoolExecutor) subject.scheduler();
            final int scheduledAfterFirstStart = executor.getQueue().size();
            assertEquals(1, scheduledAfterFirstStart);

            // The platform can re-reach ACTIVE with no intervening stop() (e.g. after a reconnect);
            // the guard must make the re-entry a no-op rather than leak a duplicate tick.
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
         * This test simulates a scenario in which we have a max concurrent sync of 2 but
         * 3 channels are active and being synced.
         * We use a CountDownLatch to block the sync for channels 1 and 2.
         * When the sync for the third channel is initiated, it should be skipped to prevent
         * throttling.
         */
        @Test
        @DisplayName("max concurrent sync operations reached, sync is skipped")
        void maxConcurrentSyncOperationsReachedNoActionOnSyncTick() {
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
            final var longSyncEmulator = new CountDownLatch(maxConcurrentSyncs);
            willAnswer(invocation -> {
                        syncCallsCount.incrementAndGet();
                        longSyncEmulator.await();
                        return null;
                    })
                    .given(synchronizer)
                    .synchronize(any(), any(), anyLong(), anyLong());

            channels.forEach(subject::initiateSync);
            await().atMost(Duration.ofMillis(200)).until(() -> syncCallsCount.get() == maxConcurrentSyncs);
            try {
                subject.initiateSync(channels.getLast());
                verify(synchronizer, after(200).never())
                        .synchronize(eq(channels.getLast()), any(), anyLong(), anyLong());
            } finally {
                IntStream.of(0, maxConcurrentSyncs).forEach((_) -> longSyncEmulator.countDown());
                assertThat(syncCallsCount.get()).isEqualTo(maxConcurrentSyncs);
                verify(synchronizer, times(maxConcurrentSyncs)).synchronize(any(), any(), anyLong(), anyLong());
            }
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
    }

    private ClprConfigBuilder defaultClprConfig() {
        return ClprConfigBuilder.newBuilder().enabled(true);
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
        return makeChannel(channelId, ClprChannelStatus.ACTIVE, 0L, 2L)
                .copyBuilder()
                .endpointManifest(ClprEndpointManifest.newBuilder()
                        .version(1L)
                        .endpoints(List.of(makeEndpoint("10.0.0.1", 50211)))
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

    @Test
    void disablingClprBlocksOutboundWorkForAnExistingChannel() {
        // Track an eligible channel before disabling the master flag.
        final var channel = makeChannel(CHANNEL_ID_1, ClprChannelStatus.ACTIVE, 0L, 2L);
        subject.onChannelActivated(CHANNEL_ID_1);
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
    void auditClosingEmptyChannelStillPropagatesClose() {
        final var closing = makeActiveChannelWithPendingMessages(CHANNEL_ID_1)
                .copyBuilder()
                .status(ClprChannelStatus.CLOSING)
                .nextMessageId(1)
                .ackedMessageId(0)
                .build();
        subject.initiateSync(closing);
        verify(synchronizer, timeout(500)).synchronize(eq(closing), any(), anyLong(), anyLong());
    }

    @Test
    void auditDrainedEmptyChannelStillPropagatesClose() {
        final var drained = makeActiveChannelWithPendingMessages(CHANNEL_ID_1)
                .copyBuilder()
                .status(ClprChannelStatus.DRAINED)
                .nextMessageId(1)
                .ackedMessageId(0)
                .build();
        subject.initiateSync(drained);
        verify(synchronizer, timeout(500)).synchronize(eq(drained), any(), anyLong(), anyLong());
    }
}

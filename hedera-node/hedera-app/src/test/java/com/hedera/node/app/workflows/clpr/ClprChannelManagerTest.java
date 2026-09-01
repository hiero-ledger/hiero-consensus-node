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
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprPeerEndpoints;
import com.hedera.hapi.node.state.clpr.ClprPeerEndpointsEntry;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.clpr.ReadableChannelStore;
import com.hedera.node.app.service.clpr.ReadableLedgerConfigurationStore;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.store.ReadableStoreFactoryImpl;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.testfixtures.ClprConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.state.State;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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
    private NetworkInfo networkInfo;

    @Mock
    private ClprLeafCertManager leafCertManager;

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
        subject = new ClprChannelManager(
                configProvider, networkInfo, stateAccessor, synchronizer, leafCertManager, clientCache);
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
        @DisplayName("discovery is disabled")
        void startWithDiscoveryDisabled() {
            final var config = defaultClprConfig().discoveryIntervalSeconds(-1).build();
            given(versionedConfig.getConfigData(ClprConfig.class)).willReturn(config);

            subject.start();

            assertFalse(subject.isDiscoveryEnabled(), "Expected discovery to be disabled");
        }

        @Test
        @DisplayName("discovery is enabled")
        void startWithDiscoveryEnabled() {
            final var config = defaultClprConfig().discoveryIntervalSeconds(10).build();
            given(versionedConfig.getConfigData(ClprConfig.class)).willReturn(config);

            subject.start();

            assertTrue(subject.isDiscoveryEnabled(), "Expected discovery to be enabled");
        }

        @Test
        @DisplayName("is idempotent — a second call schedules no additional tasks")
        void startIsIdempotent() {
            // Discovery off so the only periodic task is the peer-endpoints flush; with no active
            // channel the scheduler queue holds exactly that one task after start().
            given(versionedConfig.getConfigData(ClprConfig.class))
                    .willReturn(defaultClprConfig().discoveryIntervalSeconds(-1).build());

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

            try (var _ = givenMockedStateForSync(channelStore, ledgerConfigWithEndpoints(Collections.emptyList(), 0))) {
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

            try (var _ = givenMockedStateForSync(channelStore, ledgerConfigWithEndpoints(Collections.emptyList(), 0))) {
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
        @DisplayName("channel without peer endpoints, use capped ledger configuration")
        void channelWithoutEndpointsUseLedgerConfig() {
            subject.onChannelActivated(CHANNEL_ID_1);
            // No pending messages, so initiateSync exits before any network call, but seeding still runs.
            final var idleConn = makeChannel(CHANNEL_ID_1, ClprChannelStatus.ACTIVE, 0L, 1L);
            final var channelStore = mock(ReadableChannelStore.class);
            lenient().when(channelStore.getChannel(CHANNEL_ID_1)).thenReturn(idleConn);

            final var seedEndpoints = new ArrayList<ClprEndpoint>();
            for (int i = 0; i < 10 + 5; i++) {
                seedEndpoints.add(makeEndpoint("10.0.0." + i, 50211));
            }
            final var ledgerConfig = ClprLedgerConfiguration.newBuilder()
                    .throttles(ClprThrottles.newBuilder().maxPeerEndpoints(10).build())
                    .endpoints(seedEndpoints)
                    .build();

            try (var _ = givenMockedStateForSync(channelStore, ledgerConfig)) {
                subject.syncChannel(CHANNEL_ID_1);

                assertThat(subject.getKnownEndpoints(CHANNEL_ID_1))
                        .as("Endpoints should be seeded from ledger config and capped at PEER_ENDPOINT_CAP")
                        .hasSize(10);
            }
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
            subject = new ClprChannelManager(
                    configProvider, networkInfo, stateAccessor, synchronizer, leafCertManager, clientCache);

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

        @Test
        @DisplayName("seedPeerEndpoints with empty list is a no-op")
        void seedPeerEndpointsWithEmptyList() {
            subject.seedPeerEndpoints(CHANNEL_ID_1, Collections.emptyList());
            assertThat(subject.getKnownEndpoints(CHANNEL_ID_1)).isEmpty();
        }
    }

    @Nested
    @DisplayName("endpoint seeding from config")
    class EndpointSeedingFromConfigTest {

        @Test
        @DisplayName("keeps all configured endpoints when max_peer_endpoints is zero")
        void seedEndpointsKeepsAllWhenMaxPeerEndpointsIsZero() {
            final var channelId = Bytes.wrap(new byte[32]);
            final var endpoints = new ArrayList<ClprEndpoint>();
            for (int i = 0; i < 15; i++) {
                endpoints.add(makeEndpoint("10.0.0." + i, 50211));
            }
            subject.seedEndpointsFromConfig(
                    channelId, storeFactoryWithLedgerConfig(ledgerConfigWithEndpoints(endpoints, 0)));

            assertThat(subject.getKnownEndpoints(channelId)).hasSize(15);
        }

        @Test
        @DisplayName("truncates configured endpoints when max_peer_endpoints is non-zero")
        void seedEndpointsTruncatesWhenMaxPeerEndpointsIsNonZero() {
            final var channelId = Bytes.wrap(new byte[32]);
            final var endpoints = new ArrayList<ClprEndpoint>();
            for (int i = 0; i < 8; i++) {
                endpoints.add(makeEndpoint("10.0.0." + i, 50211));
            }
            subject.seedEndpointsFromConfig(
                    channelId, storeFactoryWithLedgerConfig(ledgerConfigWithEndpoints(endpoints, 3)));

            assertThat(subject.getKnownEndpoints(channelId)).hasSize(3);
        }
    }

    @Nested
    @DisplayName("endpoint cache (mergeDiscoveredEndpoints)")
    class EndpointCacheTest {

        private static final Bytes CHANNEL_ID = Bytes.wrap(new byte[32]);

        @Test
        @DisplayName("adds new endpoints to the cache")
        void mergeDiscoveredEndpointsAddsNewEndpoints() {
            final var ep1 = makeEndpoint("10.0.0.1", 50211);
            subject.mergeDiscoveredEndpoints(CHANNEL_ID, List.of(ep1));
            assertThat(subject.getKnownEndpoints(CHANNEL_ID)).hasSize(1);
        }

        @Test
        @DisplayName("deduplicates endpoints by address")
        void mergeDiscoveredEndpointsDeduplicatesByAddress() {
            final var ep1 = makeEndpoint("10.0.0.1", 50211);
            final var ep2 = makeEndpoint("10.0.0.1", 50211);
            final var ep3 = makeEndpoint("10.0.0.2", 50211);

            subject.mergeDiscoveredEndpoints(CHANNEL_ID, List.of(ep1));
            subject.mergeDiscoveredEndpoints(CHANNEL_ID, List.of(ep2, ep3));

            assertThat(subject.getKnownEndpoints(CHANNEL_ID)).hasSize(2);
        }

        @Test
        @DisplayName("ignores an empty endpoint list")
        void mergeDiscoveredEndpointsIgnoresEmpty() {
            subject.mergeDiscoveredEndpoints(CHANNEL_ID, List.of());
            assertThat(subject.getKnownEndpoints(CHANNEL_ID)).isEmpty();
        }

        @Test
        @DisplayName("re-merging only already-known endpoints leaves the cache unchanged")
        void mergeDiscoveredEndpointsAllDuplicatesAreNoOp() {
            subject.mergeDiscoveredEndpoints(CHANNEL_ID, List.of(makeEndpoint("10.0.0.1", 50211)));
            assertThat(subject.getKnownEndpoints(CHANNEL_ID)).hasSize(1);

            // A merge whose endpoints are all already known adds nothing — the cache neither grows
            // nor corrupts (and the size-gate skips flagging the file dirty for this no-op).
            subject.mergeDiscoveredEndpoints(CHANNEL_ID, List.of(makeEndpoint("10.0.0.1", 50211)));
            assertThat(subject.getKnownEndpoints(CHANNEL_ID)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("mTLS: known peer CA certs + discovery suppression")
    class MtlsTests {
        private static final Bytes CONN = Bytes.wrap(new byte[32]);

        @BeforeEach
        void enableMtls() {
            // buildPeerCaCertificates() is a no-op unless mTLS is enabled — the trust index it
            // builds is consumed only by the mTLS sync listener. lenient(): not every test in this
            // class triggers a rebuild (e.g. the no-endpoints-cached case).
            lenient().when(leafCertManager.isMtlsEnabled()).thenReturn(true);
        }

        @Test
        @DisplayName("knownPeerCaCertificatesByIssuer parses cached endpoint certs, skipping empty/unparseable")
        void knownPeerCaCertificatesParsesCerts() throws Exception {
            final var caCert = selfSignedCert();
            final var withCert = ClprEndpoint.newBuilder()
                    .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                            .ipAddress("10.0.0.1")
                            .port(50214)
                            .build())
                    .tlsCertificate(Bytes.wrap(caCert.getEncoded()))
                    .build();
            final var withoutCert = makeEndpoint("10.0.0.2", 50214); // empty tls_certificate — skipped
            final var withGarbage = ClprEndpoint.newBuilder()
                    .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                            .ipAddress("10.0.0.3")
                            .port(50214)
                            .build())
                    .tlsCertificate(Bytes.wrap(new byte[] {1, 2, 3})) // unparseable — skipped
                    .build();

            subject.seedPeerEndpoints(CONN, List.of(withCert, withoutCert, withGarbage));

            assertThat(allTrustedCerts(subject)).containsExactly(caCert);
        }

        @Test
        @DisplayName("knownPeerCaCertificatesByIssuer is empty when no endpoints are cached")
        void knownPeerCaCertificatesEmpty() {
            assertThat(subject.knownPeerCaCertificatesByIssuer()).isEmpty();
        }

        @Test
        @DisplayName("knownPeerCaCertificatesByIssuer skips parsing entirely when mTLS is disabled")
        void knownPeerCaCertificatesSkippedWhenMtlsDisabled() {
            given(leafCertManager.isMtlsEnabled()).willReturn(false);
            final var withGarbage = ClprEndpoint.newBuilder()
                    .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                            .ipAddress("10.0.0.3")
                            .port(50214)
                            .build())
                    .tlsCertificate(Bytes.wrap(new byte[] {1, 2, 3})) // unparseable, but never reached
                    .build();

            subject.seedPeerEndpoints(CONN, List.of(withGarbage));

            assertThat(subject.knownPeerCaCertificatesByIssuer()).isEmpty();
        }

        @Test
        @DisplayName("discoveryTick requests no client when mTLS is enabled")
        void discoverySuppressedUnderMtls() {
            subject.seedPeerEndpoints(CONN, List.of(makeEndpoint("10.0.0.9", 50214)));
            subject.discoveryTick();
            verifyNoInteractions(clientCache);
        }

        @Test
        @DisplayName("knownPeerCaCertificatesByIssuer keys CAs with distinct subject DNs under separate keys")
        void knownPeerCaCertificatesInvalidatedOnAdd() throws Exception {
            final var ca1 = caWithCn("ca-one");
            subject.seedPeerEndpoints(CONN, List.of(endpointWithCert("10.0.0.1", ca1)));
            var certsByIssuer = subject.knownPeerCaCertificatesByIssuer();
            assertThat(certsByIssuer).containsOnlyKeys(ca1.getSubjectX500Principal());
            assertThat(certsByIssuer.get(ca1.getSubjectX500Principal())).containsExactly(ca1);

            final var ca2 = caWithCn("ca-two");
            final var conn2 = Bytes.wrap(distinctConnId());
            subject.seedPeerEndpoints(conn2, List.of(endpointWithCert("10.0.0.2", ca2)));
            certsByIssuer = subject.knownPeerCaCertificatesByIssuer();
            assertThat(certsByIssuer).containsOnlyKeys(ca1.getSubjectX500Principal(), ca2.getSubjectX500Principal());
            assertThat(certsByIssuer.get(ca1.getSubjectX500Principal())).containsExactly(ca1);
            assertThat(certsByIssuer.get(ca2.getSubjectX500Principal())).containsExactly(ca2);
        }

        @Test
        @DisplayName("knownPeerCaCertificatesByIssuer groups distinct CAs that share a subject DN under one key")
        void knownPeerCaCertificatesGroupsCasSharingSubjectDn() throws Exception {
            // Two distinct CAs (different key pairs) that happen to share the same subject DN — they must
            // collapse into one bucket under that DN, not overwrite each other.
            final var ca1 = caWithCn("shared-ca");
            final var ca2 = caWithCn("shared-ca");
            final var sharedDn = ca1.getSubjectX500Principal();
            assertThat(ca2.getSubjectX500Principal()).isEqualTo(sharedDn); // precondition: DNs collide

            subject.seedPeerEndpoints(
                    CONN, List.of(endpointWithCert("10.0.0.1", ca1), endpointWithCert("10.0.0.2", ca2)));

            final var index = subject.knownPeerCaCertificatesByIssuer();
            assertThat(index).containsOnlyKeys(sharedDn);
            assertThat(index.get(sharedDn)).containsExactlyInAnyOrder(ca1, ca2);
        }

        @Test
        @DisplayName(
                "knownPeerCaCertificatesByIssuer drops a peer's CA after its channel closes (invalidated on remove)")
        void knownPeerCaCertificatesInvalidatedOnRemove() throws Exception {
            final var ca = selfSignedCert();
            subject.registerChannel(CONN);
            subject.seedPeerEndpoints(CONN, List.of(endpointWithCert("10.0.0.1", ca)));
            assertThat(allTrustedCerts(subject)).containsExactly(ca);

            subject.onChannelClosed(CONN);
            assertThat(subject.knownPeerCaCertificatesByIssuer()).isEmpty();
        }

        @Test
        @DisplayName("knownPeerCaCertificatesByIssuer returns the cached instance when endpoints are unchanged")
        void knownPeerCaCertificatesCachedBetweenCalls() throws Exception {
            subject.seedPeerEndpoints(CONN, List.of(endpointWithCert("10.0.0.1", selfSignedCert())));
            final var first = subject.knownPeerCaCertificatesByIssuer();
            final var second = subject.knownPeerCaCertificatesByIssuer();
            assertThat(second).isSameAs(first);
        }
    }

    /** Flattens the issuer-keyed trust index to the set of trusted CA certs, for assertion convenience. */
    private static List<X509Certificate> allTrustedCerts(final ClprChannelManager mgr) {
        return mgr.knownPeerCaCertificatesByIssuer().values().stream()
                .flatMap(List::stream)
                .toList();
    }

    private static X509Certificate selfSignedCert() throws Exception {
        return new ClprTestCa("test-ca").caCert();
    }

    /** A self-signed CA cert with subject/issuer {@code CN=<cn>}, for asserting how the index keys CAs. */
    private static X509Certificate caWithCn(final String cn) throws Exception {
        return new ClprTestCa(cn).caCert();
    }

    /** An endpoint advertising {@code cert} as its {@code tls_certificate}. */
    private static ClprEndpoint endpointWithCert(final String ip, final X509Certificate cert) throws Exception {
        return ClprEndpoint.newBuilder()
                .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                        .ipAddress(ip)
                        .port(50214)
                        .build())
                .tlsCertificate(Bytes.wrap(cert.getEncoded()))
                .build();
    }

    /** A 32-byte channel id distinct from the all-zero {@code CONN} used by the mTLS tests. */
    private static byte[] distinctConnId() {
        final var id = new byte[32];
        id[0] = 1;
        return id;
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

    private static ClprLedgerConfiguration ledgerConfigWithEndpoints(
            final List<ClprEndpoint> endpoints, final int maxPeerEndpoints) {
        return ClprLedgerConfiguration.newBuilder()
                .endpoints(endpoints)
                .throttles(ClprThrottles.newBuilder()
                        .maxPeerEndpoints(maxPeerEndpoints)
                        .build())
                .build();
    }

    private static ReadableStoreFactoryImpl storeFactoryWithLedgerConfig(final ClprLedgerConfiguration ledgerConfig) {
        final var storeFactory = mock(ReadableStoreFactoryImpl.class);
        final var configStore = mock(ReadableLedgerConfigurationStore.class);
        lenient().when(configStore.getConfiguration()).thenReturn(ledgerConfig);
        lenient()
                .when(storeFactory.readableStore(ReadableLedgerConfigurationStore.class))
                .thenReturn(configStore);
        return storeFactory;
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
        return makeChannel(channelId, ClprChannelStatus.ACTIVE, 0L, 2L);
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
        @DisplayName("persists channels + endpoints to disk and rehydrates them on a fresh manager")
        void persistsAndRehydratesAcrossRestart() {
            final var cacheFile = tempDir.resolve("clpr-peer-endpoints.json");
            final var endpoint = makeEndpoint("10.0.0.7", 50211);

            // Manager A: activate a channel and seed peer endpoints — both persist to disk.
            subject.start();
            subject.onChannelActivated(CHANNEL_ID_1);
            subject.seedPeerEndpoints(CHANNEL_ID_1, List.of(endpoint));
            // Disk writes are offloaded to the scheduler; wait until the file reflects the endpoint.
            await().atMost(Duration.ofSeconds(5)).until(() -> cacheHasEndpoint(cacheFile, CHANNEL_ID_1));
            subject.stop();

            // Manager B: fresh instance, same (temp) cache file. It must rebuild the registry, the
            // peer endpoint cache, the interval, and the timer purely from disk + committed state.
            final var managerB = new ClprChannelManager(
                    configProvider, networkInfo, stateAccessor, synchronizer, leafCertManager, clientCache);
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
        @DisplayName("rehydration skips a channel that is CLOSED in committed state")
        void rehydrationSkipsClosed() {
            final var cacheFile = tempDir.resolve("clpr-peer-endpoints.json");
            subject.start();
            subject.onChannelActivated(CHANNEL_ID_1);
            await().atMost(Duration.ofSeconds(5)).until(() -> cacheHas(cacheFile, CHANNEL_ID_1));
            subject.stop();

            final var managerB = new ClprChannelManager(
                    configProvider, networkInfo, stateAccessor, synchronizer, leafCertManager, clientCache);
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
}

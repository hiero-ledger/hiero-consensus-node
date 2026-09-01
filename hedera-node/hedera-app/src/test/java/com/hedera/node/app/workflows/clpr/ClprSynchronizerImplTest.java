// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.GrpcConfig;
import com.hedera.node.config.testfixtures.ClprConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprSynchronizerImplTest {

    /**
     * 32-byte channel ID per spec §1.4. The leading byte makes it readable in logs while
     * still satisfying the wire-format length requirement.
     */
    private static final Bytes TEST_CHANNEL_ID =
            Bytes.fromHex("01000000000000000000000000000000000000000000000000000000000000ab");

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private VersionedConfiguration versionedConfig;

    @Mock
    private ClprBundleSubmitter bundleSubmitter;

    @Mock
    private NetworkInfo networkInfo;

    @Mock
    private ClprStateProofManager stateProofManager;

    @Mock
    private ClprLeafCertManager leafCertManager;

    @Mock
    private ClprEndpointClientCache clientCache;

    private ClprSynchronizerImpl subject;

    @BeforeEach
    void setUp() {
        lenient().when(configProvider.getConfiguration()).thenReturn(versionedConfig);
        lenient()
                .when(versionedConfig.getConfigData(ClprConfig.class))
                .thenReturn(ClprConfigBuilder.newBuilder()
                        .enabled(true)
                        .syncPeerExclusionEnabled(true)
                        .build());
        subject = new ClprSynchronizerImpl(
                configProvider, bundleSubmitter, networkInfo, stateProofManager, leafCertManager, clientCache);
    }

    @Nested
    @DisplayName("synchronize")
    class SynchronizeTests {

        private static final int GRPC_PORT = 50211;
        private static final int TLS_PORT = 50212;
        private static final String PEER_HOST = "10.0.0.1";
        private static final int PEER_PORT = 50211;
        private static final String PEER_ID = PEER_HOST + ":" + PEER_PORT;

        @BeforeEach
        void setUpSynchronize() {
            // NodeIdentity is built lazily from GrpcConfig + selfNodeInfo whenever the endpoint
            // list is non-empty, so we need a real GrpcConfig in place.
            lenient()
                    .when(versionedConfig.getConfigData(GrpcConfig.class))
                    .thenReturn(
                            new GrpcConfig(GRPC_PORT, TLS_PORT, true, 50213, 60211, 60212, 4194304, 4194304, 4194304));
        }

        @Test
        @DisplayName("CLPR disabled skips sync")
        void disabledClprSkipsSync() {
            given(versionedConfig.getConfigData(ClprConfig.class))
                    .willReturn(ClprConfigBuilder.newBuilder().enabled(false).build());

            subject.synchronize(testChannel(List.of()), List.of(endpoint(PEER_HOST, PEER_PORT)), 0L, 0L);

            verifyNoInteractions(stateProofManager, bundleSubmitter, networkInfo, clientCache);
        }

        @Test
        @DisplayName("channel with no endpoint_manifest skips sync")
        void noManifestSkipsSync() {
            subject.synchronize(testChannel(List.of()), List.of(), 0L, 0L);

            verifyNoInteractions(stateProofManager, bundleSubmitter, networkInfo);
        }

        @Test
        @DisplayName("empty endpoint_manifest skips sync")
        void emptyManifestEndpointsSkipsSync() {
            // Channel has an empty ClprEndpointManifest (no endpoints) — nothing to dial.
            subject.synchronize(testChannel(List.of()), List.of(), 0L, 0L);

            verifyNoInteractions(stateProofManager, bundleSubmitter, networkInfo);
        }

        @Test
        @DisplayName("skipped self endpoint")
        void skipSelfEndpoint() {
            // Loopback host on the configured gRPC port short-circuits NodeIdentity.isSelf,
            // so the only candidate is filtered out and selectPeer returns null.
            subject.synchronize(testChannel(List.of()), List.of(endpoint("127.0.0.1", GRPC_PORT)), 0L, 0L);

            verifyNoInteractions(stateProofManager, bundleSubmitter);
        }

        @Test
        @DisplayName("bundle state proof returns null")
        void bundleStateProofReturnsNull() {
            given(stateProofManager.buildSerializedBundleProof(any(), anyLong(), any(), eq(false), anyBoolean()))
                    .willReturn(null);

            subject.synchronize(testChannel(List.of()), List.of(endpoint(PEER_HOST, PEER_PORT)), 0L, 0L);

            // No gRPC client should be requested when there is no bundle to send.
            verifyNoInteractions(clientCache, bundleSubmitter);
        }

        @Test
        @DisplayName("clpr sync remote call fails")
        void removeSyncCallFails() throws Exception {
            given(stateProofManager.buildSerializedBundleProof(any(), anyLong(), any(), eq(false), anyBoolean()))
                    .willReturn(Bytes.wrap("bundle"));

            final var client = mock(ClprEndpointClient.class);
            given(client.sync(any(), any()))
                    .willThrow(new ClprEndpointClient.ClprSyncException("simulated sync failure"));
            given(clientCache.clientFor(any(), anyInt(), any(), any())).willReturn(client);

            subject.synchronize(testChannel(List.of()), List.of(endpoint(PEER_HOST, PEER_PORT)), 0L, 0L);

            verify(clientCache).clientFor(any(), anyInt(), any(), any());
            // Sync exception is treated as a failure: reputation drops by FAILURE_PENALTY (0.3) from MAX (1.0).
            assertThat(subject.getReputation(PEER_ID).rawScore()).isCloseTo(0.7, offset(0.0001));
            verifyNoInteractions(bundleSubmitter);
        }

        @Test
        @DisplayName("bundle submission to application fails")
        void bundleSubmissionFails() throws Exception {
            given(stateProofManager.buildSerializedBundleProof(any(), anyLong(), any(), eq(false), anyBoolean()))
                    .willReturn(Bytes.wrap("bundle"));
            given(bundleSubmitter.submitBundle(any())).willReturn(false);

            final var peerResponse = peerResponse(Bytes.wrap("inbound_bundle"));
            stubEndpointClient(peerResponse);

            subject.synchronize(testChannel(List.of()), List.of(endpoint(PEER_HOST, PEER_PORT)), 0L, 0L);

            verify(bundleSubmitter).submitBundle(peerResponse);
            // Submission failure is treated as a sync failure: reputation drops from 1.0 to 0.7.
            assertThat(subject.getReputation(PEER_ID).rawScore()).isCloseTo(0.7, offset(0.0001));
        }

        @Test
        @DisplayName("sync succeeds with no returned messages")
        void syncSucceedsWithNoReturnedMessages() throws Exception {
            given(stateProofManager.buildSerializedBundleProof(any(), anyLong(), any(), eq(false), anyBoolean()))
                    .willReturn(Bytes.wrap("bundle"));

            // Pre-decrement reputation so the success bump (+0.1) is observable. The raw score
            // starts at MAX (1.0) and saturates there, so two failures drop it to 0.4.
            final var rep = subject.getReputation(PEER_ID);
            rep.recordFailure();
            rep.recordFailure();
            assertThat(rep.rawScore()).isCloseTo(0.4, offset(0.0001));

            stubEndpointClient(peerResponse(Bytes.EMPTY));

            subject.synchronize(testChannel(List.of()), List.of(endpoint(PEER_HOST, PEER_PORT)), 0L, 0L);

            // Empty response is a successful sync: reputation bumps to 0.5, no submission.
            assertThat(rep.rawScore()).isCloseTo(0.5, offset(0.0001));
            verifyNoInteractions(bundleSubmitter);
        }

        @Test
        @DisplayName("sync succeeds with returned messages")
        void syncSucceedsWithReturnedMessages() throws Exception {
            given(stateProofManager.buildSerializedBundleProof(any(), anyLong(), any(), eq(false), anyBoolean()))
                    .willReturn(Bytes.wrap("bundle"));
            given(bundleSubmitter.submitBundle(any())).willReturn(true);

            // Pre-decrement reputation so the success bump (+0.1) is observable above MAX saturation.
            final var rep = subject.getReputation(PEER_ID);
            rep.recordFailure();
            rep.recordFailure();
            final ClprSyncPayload expectedPeerResp = peerResponse(Bytes.wrap("inbound_bundle"));
            stubEndpointClient(expectedPeerResp);

            subject.synchronize(testChannel(List.of()), List.of(endpoint(PEER_HOST, PEER_PORT)), 0L, 0L);

            verify(bundleSubmitter).submitBundle(expectedPeerResp);
            assertThat(rep.rawScore()).isCloseTo(0.5, offset(0.0001));
        }

        @Test
        @DisplayName("#335: peer's observed view of our manifest is stale ⇒ includeEndpointManifest=true")
        void peerStaleTriggersManifestInclusion() throws Exception {
            // Local manifest version = 5; peer last reported holding version 3 of OUR manifest →
            // peer is behind → include our manifest proof. The gate keys on peerObservedManifestVersion,
            // NOT channel.endpointManifestVersion() (our cache of the PEER's manifest, set here to
            // 9 to prove it does not drive this decision — under the old logic 9<5 would have wrongly
            // suppressed inclusion).
            given(stateProofManager.buildSerializedBundleProof(any(), anyLong(), any(), eq(false), eq(true)))
                    .willReturn(Bytes.wrap("bundle"));

            final var channel = ClprChannel.newBuilder()
                    .channelId(TEST_CHANNEL_ID)
                    .status(ClprChannelStatus.ACTIVE)
                    .ackedMessageId(0L)
                    .peerThrottles(
                            ClprThrottles.newBuilder().maxMessagesPerBundle(10).build())
                    .endpointManifestVersion(9L)
                    .build();
            stubEndpointClient(peerResponse(Bytes.EMPTY));
            subject.synchronize(
                    channel,
                    List.of(endpoint(PEER_HOST, PEER_PORT)),
                    /*localManifestVersion*/ 5L,
                    /*peerObservedManifestVersion*/ 3L);
            verify(clientCache).clientFor(any(), anyInt(), any(), any());

            // Verified via the eq(true) matcher on the given(...) stub.
            verify(stateProofManager).buildSerializedBundleProof(any(), anyLong(), any(), eq(false), eq(true));
        }

        @Test
        @DisplayName("#335: peer's observed view of our manifest is current ⇒ includeEndpointManifest=false")
        void peerCurrentSuppressesManifestInclusion() throws Exception {
            given(stateProofManager.buildSerializedBundleProof(any(), anyLong(), any(), eq(false), eq(false)))
                    .willReturn(Bytes.wrap("bundle"));

            // Peer last reported holding version 5 of OUR manifest and our local version is 5 → peer
            // is current → suppress. channel.endpointManifestVersion() (our cache of the PEER) is
            // set to 1 to prove it does not drive this gate — under the old logic 1<5 would have
            // wrongly included the manifest.
            final var channel = ClprChannel.newBuilder()
                    .channelId(TEST_CHANNEL_ID)
                    .status(ClprChannelStatus.ACTIVE)
                    .ackedMessageId(0L)
                    .peerThrottles(
                            ClprThrottles.newBuilder().maxMessagesPerBundle(10).build())
                    .endpointManifestVersion(1L)
                    .build();
            stubEndpointClient(peerResponse(Bytes.EMPTY));
            subject.synchronize(
                    channel,
                    List.of(endpoint(PEER_HOST, PEER_PORT)),
                    /*localManifestVersion*/ 5L,
                    /*peerObservedManifestVersion*/ 5L);
            verify(clientCache).clientFor(any(), anyInt(), any(), any());

            verify(stateProofManager).buildSerializedBundleProof(any(), anyLong(), any(), eq(false), eq(false));
        }

        @Test
        @DisplayName("#346: dial targets capped by peerThrottles.maxPeerEndpoints")
        void dialTargetsCappedByThrottle() {
            given(stateProofManager.buildSerializedBundleProof(any(), anyLong(), any(), eq(false), anyBoolean()))
                    .willReturn(null); // Skip network path — we only care that the cap is applied.
            final var channel = ClprChannel.newBuilder()
                    .channelId(TEST_CHANNEL_ID)
                    .status(ClprChannelStatus.ACTIVE)
                    .ackedMessageId(0L)
                    .peerThrottles(ClprThrottles.newBuilder()
                            .maxMessagesPerBundle(10)
                            .maxPeerEndpoints(1)
                            .build())
                    .endpointManifestVersion(1L)
                    .build();
            subject.synchronize(
                    channel,
                    List.of(endpoint("10.0.0.1", 50211), endpoint("10.0.0.2", 50211), endpoint("10.0.0.3", 50211)),
                    1L,
                    0L);
            // Cap = 1 ⇒ only one endpoint considered ⇒ no gRPC client requested since the bundle
            // was null (but the cap has already been applied — this test asserts the sync
            // proceeded past the empty-endpoints guard, meaning at least one dial target
            // survived).
            verifyNoInteractions(clientCache);
            verify(stateProofManager).buildSerializedBundleProof(any(), anyLong(), any(), eq(false), anyBoolean());
        }

        private ClprSyncPayload peerResponse(Bytes bundlePayload) {
            return ClprSyncPayload.newBuilder().bundlePayload(bundlePayload).build();
        }

        /**
         * Stubs {@link ClprEndpointClientCache#clientFor} to vend a client whose {@code sync} returns
         * {@code peerResponse}, mirroring the reused-channel path the synchronizer now takes.
         */
        private void stubEndpointClient(final ClprSyncPayload peerResponse)
                throws ClprEndpointClient.ClprSyncException {
            final var client = mock(ClprEndpointClient.class);
            given(client.sync(any(), any())).willReturn(peerResponse);
            given(clientCache.clientFor(any(), anyInt(), any(), any())).willReturn(client);
        }

        private static ClprChannel testChannel(final List<ClprEndpoint> endpoints) {
            return ClprChannel.newBuilder()
                    .channelId(TEST_CHANNEL_ID)
                    .status(ClprChannelStatus.ACTIVE)
                    .ackedMessageId(0L)
                    .peerThrottles(
                            ClprThrottles.newBuilder().maxMessagesPerBundle(10).build())
                    .endpointManifest(com.hedera.hapi.node.state.clpr.ClprEndpointManifest.newBuilder()
                            .version(0L)
                            .endpoints(endpoints)
                            .build())
                    .endpointManifestVersion(0L)
                    .build();
        }

        private static ClprEndpoint endpoint(final String ip, final int port) {
            return ClprEndpoint.newBuilder()
                    .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                            .ipAddress(ip)
                            .port(port)
                            .build())
                    // Non-empty dummy cert bytes so the tls_certificate skip check does not fire.
                    // The endpoint client is stubbed via the ClprEndpointClientCache so the bytes are never parsed.
                    .tlsCertificate(Bytes.wrap(new byte[] {1, 2, 3}))
                    .build();
        }
    }

    @Nested
    @DisplayName("internal getter caching")
    class GetterCachingTests {

        @Test
        @DisplayName("getCircuitBreaker returns the cached instance per peer")
        void circuitBreakerInstancesAreCached() {
            final var cb1 = subject.getCircuitBreaker("peer1");
            final var cb2 = subject.getCircuitBreaker("peer1");
            assertThat(cb1).isSameAs(cb2);
        }

        @Test
        @DisplayName("getCircuitBreaker returns distinct instances per peer")
        void circuitBreakerInstancesAreDistinctAcrossPeers() {
            final var cb1 = subject.getCircuitBreaker("peer1");
            final var cb2 = subject.getCircuitBreaker("peer2");
            assertThat(cb1).isNotSameAs(cb2);
        }

        @Test
        @DisplayName("getReputation returns the cached instance per peer")
        void reputationInstancesAreCached() {
            final var rep1 = subject.getReputation("peer1");
            final var rep2 = subject.getReputation("peer1");
            assertThat(rep1).isSameAs(rep2);
        }

        @Test
        @DisplayName("getReputation returns distinct instances per peer")
        void reputationInstancesAreDistinctAcrossPeers() {
            final var rep1 = subject.getReputation("peer1");
            final var rep2 = subject.getReputation("peer2");
            assertThat(rep1).isNotSameAs(rep2);
        }
    }

    @Nested
    @DisplayName("selectPeer()")
    class PeerSelectionTest {

        @Test
        @DisplayName("returns null for an empty peer list")
        void returnsNullForEmptyPeerList() {
            assertThat(subject.selectPeer(List.of())).isNull();
        }

        @Test
        @DisplayName("returns the only candidate when the list has one peer")
        void returnsSingleCandidate() {
            assertThat(subject.selectPeer(List.of("peer1"))).isEqualTo("peer1");
        }

        @Test
        @DisplayName("skips peers whose circuit breaker is open")
        void skipsPeersWithOpenCircuitBreaker() {
            // Open the circuit breaker for peer1
            final var cb = subject.getCircuitBreaker("peer1");
            for (int i = 0; i < 5; i++) {
                cb.recordFailure();
            }
            assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);

            // peer1 should be skipped, peer2 selected
            final var selected = subject.selectPeer(List.of("peer1", "peer2"));
            assertThat(selected).isEqualTo("peer2");
        }

        @Test
        @DisplayName("returns null when every candidate peer is blocked")
        void returnsNullWhenAllPeersBlocked() {
            final var cb1 = subject.getCircuitBreaker("peer1");
            final var cb2 = subject.getCircuitBreaker("peer2");
            for (int i = 0; i < 5; i++) {
                cb1.recordFailure();
                cb2.recordFailure();
            }

            assertThat(subject.selectPeer(List.of("peer1", "peer2"))).isNull();
        }

        @Test
        @DisplayName("returns open-breaker peers when peer exclusion is disabled")
        void returnsOpenBreakerPeersWhenPeerExclusionDisabled() {
            given(versionedConfig.getConfigData(ClprConfig.class))
                    .willReturn(ClprConfigBuilder.newBuilder()
                            .enabled(true)
                            .syncPeerExclusionEnabled(false)
                            .build());
            final var cb = subject.getCircuitBreaker("peer1");
            for (int i = 0; i < 5; i++) {
                cb.recordFailure();
            }
            assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);

            assertThat(subject.selectPeer(List.of("peer1"))).isEqualTo("peer1");
        }

        @Test
        @DisplayName("always returns a candidate from the list when peers are healthy")
        void selectsFromMultipleCandidates() {
            // With multiple healthy peers, should always return one of them
            final var peers = List.of("peer1", "peer2", "peer3");
            for (int i = 0; i < 20; i++) {
                final var selected = subject.selectPeer(peers);
                assertThat(selected).isIn("peer1", "peer2", "peer3");
            }
        }

        @Test
        @DisplayName("higher-reputation peers are selected more often than lower-reputation peers")
        void weightedSelectionFavorsHigherReputation() {
            // Reputation weights — kept below retryMaxAttempts (5) so no circuit breaker opens:
            //   high: 0 failures → raw score 1.0
            //   mid : 1 failure  → raw score 0.7
            //   low : 3 failures → raw score 0.1 (clamped at MIN_SCORE)
            // Expected proportions over the totalWeight of 1.8: high≈55.6%, mid≈38.9%, low≈5.6%.
            // Default reputationDecaySeconds=300, so decay during the test is negligible.
            final var midRep = subject.getReputation("mid");
            midRep.recordFailure();
            final var lowRep = subject.getReputation("low");
            lowRep.recordFailure();
            lowRep.recordFailure();
            lowRep.recordFailure();

            assertThat(subject.getReputation("high").rawScore()).isCloseTo(1.0, offset(0.0001));
            assertThat(midRep.rawScore()).isCloseTo(0.7, offset(0.0001));
            assertThat(lowRep.rawScore()).isCloseTo(0.1, offset(0.0001));

            final var peers = List.of("high", "mid", "low");
            final int trials = 1_000;
            int highCount = 0;
            int midCount = 0;
            int lowCount = 0;
            for (int i = 0; i < trials; i++) {
                final var selected = subject.selectPeer(peers);
                switch (selected) {
                    case "high" -> highCount++;
                    case "mid" -> midCount++;
                    case "low" -> lowCount++;
                    default -> throw new AssertionError("unexpected peer: " + selected);
                }
            }

            // Strict ordering must hold. Gaps are wide enough (~1700 and ~3300 expected) to
            // dwarf the ~1% standard deviation of a binomial with n=10000, so flake risk is minimal.
            assertThat(highCount)
                    .as("high-reputation peer should win more often than mid")
                    .isGreaterThan(midCount);
            assertThat(midCount)
                    .as("mid-reputation peer should win more often than low")
                    .isGreaterThan(lowCount);

            // Sanity-check the proportions land near the analytical expectations (±5%).
            assertThat((double) highCount / trials).isCloseTo(1.0 / 1.8, offset(0.05));
            assertThat((double) midCount / trials).isCloseTo(0.7 / 1.8, offset(0.05));
            assertThat((double) lowCount / trials).isCloseTo(0.1 / 1.8, offset(0.05));
        }
    }
}

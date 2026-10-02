// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.app.workflows.clpr.ClprPeerSelector.SelectedPeer;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.ClprConfig;
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
class ClprUnarySynchronizerTest {

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
    private ClprStateProofManager stateProofManager;

    @Mock
    private ClprLeafCertManager leafCertManager;

    @Mock
    private ClprEndpointClientCache clientCache;

    @Mock
    private ClprPeerSelector peerSelector;

    private ClprUnarySynchronizer subject;

    @BeforeEach
    void setUp() {
        lenient().when(configProvider.getConfiguration()).thenReturn(versionedConfig);
        lenient()
                .when(versionedConfig.getConfigData(ClprConfig.class))
                .thenReturn(ClprConfigBuilder.newBuilder()
                        .enabled(true)
                        .syncPeerExclusionEnabled(true)
                        .build());
        subject = new ClprUnarySynchronizer(
                configProvider, bundleSubmitter, stateProofManager, leafCertManager, clientCache, peerSelector);
    }

    @Nested
    @DisplayName("synchronize")
    class SynchronizeTests {

        private static final String PEER_HOST = "10.0.0.1";
        private static final int PEER_PORT = 50211;
        private static final String PEER_ID = PEER_HOST + ":" + PEER_PORT;

        @BeforeEach
        void setUpSynchronize() {
            // Peer selection is covered by ClprPeerSelectorTest; here it just hands back the one peer.
            lenient()
                    .when(peerSelector.selectEndpoint(any(), any()))
                    .thenReturn(new SelectedPeer(PEER_ID, endpoint(PEER_HOST, PEER_PORT)));
        }

        @Test
        @DisplayName("CLPR disabled skips proof generation, networking and bundle submission")
        void disabledClprSkipsSync() {
            given(versionedConfig.getConfigData(ClprConfig.class))
                    .willReturn(ClprConfigBuilder.newBuilder().enabled(false).build());

            subject.synchronize(testChannel(List.of()), List.of(endpoint(PEER_HOST, PEER_PORT)), 0L, 0L);

            verifyNoInteractions(stateProofManager, bundleSubmitter, peerSelector, clientCache, leafCertManager);
        }

        @Test
        @DisplayName("channel with no endpoint_manifest skips sync")
        void noManifestSkipsSync() {
            given(peerSelector.selectEndpoint(any(), any())).willReturn(null);

            subject.synchronize(testChannel(List.of()), List.of(endpoint(PEER_HOST, PEER_PORT)), 0L, 0L);

            verifyNoInteractions(stateProofManager, bundleSubmitter, clientCache);
            thenNoOutcomeIsRecorded();
        }

        @Test
        @DisplayName("skipped self endpoint")
        void skipSelfEndpoint() {
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
            thenFailureIsRecorded();
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
            thenFailureIsRecorded();
        }

        @Test
        @DisplayName("sync succeeds with no returned messages")
        void syncSucceedsWithNoReturnedMessages() throws Exception {
            given(stateProofManager.buildSerializedBundleProof(any(), anyLong(), any(), eq(false), anyBoolean()))
                    .willReturn(Bytes.wrap("bundle"));

            stubEndpointClient(peerResponse(Bytes.EMPTY));

            subject.synchronize(testChannel(List.of()), List.of(endpoint(PEER_HOST, PEER_PORT)), 0L, 0L);
            thenSuccessIsRecorded();
            verifyNoInteractions(bundleSubmitter);
        }

        @Test
        @DisplayName("sync succeeds with returned messages")
        void syncSucceedsWithReturnedMessages() throws Exception {
            given(stateProofManager.buildSerializedBundleProof(any(), anyLong(), any(), eq(false), anyBoolean()))
                    .willReturn(Bytes.wrap("bundle"));
            given(bundleSubmitter.submitBundle(any())).willReturn(true);
            final ClprSyncPayload expectedPeerResp = peerResponse(Bytes.wrap("inbound_bundle"));
            stubEndpointClient(expectedPeerResp);

            subject.synchronize(testChannel(List.of()), List.of(endpoint(PEER_HOST, PEER_PORT)), 0L, 0L);

            verify(bundleSubmitter).submitBundle(expectedPeerResp);
            thenSuccessIsRecorded();
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

        private void thenSuccessIsRecorded() {
            verify(peerSelector).recordSuccess(PEER_ID);
            verify(peerSelector, never()).recordFailure(any());
        }

        private void thenFailureIsRecorded() {
            verify(peerSelector).recordFailure(PEER_ID);
            verify(peerSelector, never()).recordSuccess(any());
        }

        private void thenNoOutcomeIsRecorded() {
            verify(peerSelector, never()).recordSuccess(any());
            verify(peerSelector, never()).recordFailure(any());
        }

        private ClprSyncPayload peerResponse(final Bytes bundlePayload) {
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
                    .endpointManifest(ClprEndpointManifest.newBuilder()
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
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.clpr.ClprBundleRequest;
import com.hedera.hapi.node.state.clpr.ClprBundleResponse;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.hapi.node.state.clpr.ClprStreamingSyncPayload;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.app.workflows.clpr.ClprEndpointClient.ClprSyncException;
import com.hedera.node.app.workflows.clpr.ClprEndpointClientImpl.ClientException;
import com.hedera.node.app.workflows.clpr.ClprPeerSelector.SelectedPeer;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.testfixtures.ClprConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Exercises the client side of the streaming sync protocol against a scripted peer: the message ordering of the ADR's
 * sequence, the range the peer's request produces, the loop over several bundles, and how each way an exchange can end
 * is scored against the peer.
 */
@ExtendWith(MockitoExtension.class)
class ClprStreamingSynchronizerTest {

    private static final Bytes CHANNEL_ID =
            Bytes.fromHex("01000000000000000000000000000000000000000000000000000000000000ab");
    private static final Bytes OUTBOUND_BUNDLE = Bytes.wrap("outbound-bundle");
    private static final Bytes INBOUND_BUNDLE = Bytes.wrap("inbound-bundle");
    private static final String PEER_HOST = "10.0.0.1";
    private static final int PEER_PORT = 50211;
    private static final String PEER_ID = PEER_HOST + ":" + PEER_PORT;

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
    private ClprEndpointClient client;

    @Mock
    private ClprStreamingSyncCall streamingCall;

    @Mock
    private ClprPeerSelector peerSelector;

    private ClprStreamingSynchronizer subject;

    @BeforeEach
    void setUp() {
        lenient().when(configProvider.getConfiguration()).thenReturn(versionedConfig);
        lenient()
                .when(versionedConfig.getConfigData(ClprConfig.class))
                .thenReturn(ClprConfigBuilder.newBuilder()
                        .enabled(true)
                        .syncPeerExclusionEnabled(true)
                        .build());
        // Peer selection is covered by ClprPeerSelectorTest; here it just hands back the one peer.
        lenient().when(peerSelector.selectEndpoint(any(), any())).thenReturn(new SelectedPeer(PEER_ID, endpoint()));
        lenient().when(clientCache.clientFor(any(), anyInt(), any(), any())).thenReturn(client);
        lenient().when(client.streamingSync(any())).thenReturn(streamingCall);
        lenient().when(bundleSubmitter.submitBundle(any())).thenReturn(true);
        subject = new ClprStreamingSynchronizer(
                configProvider, bundleSubmitter, stateProofManager, leafCertManager, clientCache, peerSelector);
    }

    @Test
    @DisplayName("given a 4 messages sequence, then the cycle completes with our bundle shaped by the peer's request")
    void givenFourMessaagesSequence_thenCompletesTheFourMessageCycle() throws Exception {
        givenBundleWithMessageCount(2);
        givenPeerMessages(payload(request(3L), INBOUND_BUNDLE), payload(null, null));

        synchronize(channel(6L, 0L, 2L));

        final var writes = captureWrites(2);
        // Message 1: our live request, and no bundle — ours can only be shaped once the peer's request is known.
        assertThat(writes.getFirst().bundleRequest()).isNotNull();
        assertThat(writes.getFirst().bundleRequest().currentReceivedMessageId()).isEqualTo(2L);
        assertThat(writes.getFirst().bundleResponse()).isNull();
        // Message 3: our bundle only; the request is one-shot.
        assertThat(writes.get(1).bundleRequest()).isNull();
        assertNotNull(writes.get(1).bundleResponse());
        assertThat(writes.get(1).bundleResponse().bundlePayload()).isEqualTo(OUTBOUND_BUNDLE);

        final var order = inOrder(streamingCall);
        order.verify(streamingCall).write(any());
        order.verify(streamingCall).read();
        order.verify(streamingCall).write(any());
        order.verify(streamingCall).read();
        order.verify(streamingCall).halfClose();
        order.verify(streamingCall).read();
        order.verify(streamingCall).close();

        // The peer holds up to 3, so the range starts at 4.
        verify(stateProofManager).buildBundleProof(eq(CHANNEL_ID), eq(4L), any(), eq(false), anyBoolean());
        verify(bundleSubmitter)
                .submitBundle(ClprSyncPayload.newBuilder()
                        .channelId(CHANNEL_ID)
                        .bundlePayload(INBOUND_BUNDLE)
                        .build());
        thenSuccessIsRecorded();
    }

    @Nested
    @DisplayName("peer request handling")
    class PeerRequestHandling {

        @Test
        @DisplayName("given a peer claiming a message we never sent, then our range falls back to acked_message_id + 1")
        void givenPeerOverClaims_thenRangeFallsBackToAcked() throws Exception {
            givenBundleWithMessageCount(2);
            givenPeerMessages(payload(request(6L), null), payload(null, null));

            synchronize(channel(6L, 2L, 0L));

            verify(stateProofManager).buildBundleProof(eq(CHANNEL_ID), eq(3L), any(), eq(false), anyBoolean());
        }

        @Test
        @DisplayName("given a peer that reports itself CLOSED, then no bundle is built for it")
        void givenClosedPeer_thenNoBundleIsBuilt() throws Exception {
            givenPeerMessages(payload(
                    ClprBundleRequest.newBuilder()
                            .currentReceivedMessageId(3L)
                            .currentStatus(ClprChannelStatus.CLOSED)
                            .build(),
                    null));

            synchronize(channel(6L, 0L, 0L));

            verify(stateProofManager, never()).buildBundleProof(any(), anyLong(), any(), anyBoolean(), anyBoolean());
            verify(streamingCall, times(1)).write(any());
            verify(streamingCall).halfClose();
        }
    }

    @Nested
    @DisplayName("endpoint manifest inclusion")
    class ManifestInclusion {

        @Test
        @DisplayName("given the peer reports a stale copy of our manifest, then our manifest is included")
        void givenPeerReportsStaleManifest_thenManifestIsIncluded() throws Exception {
            givenBundleWithMessageCount(2);
            givenPeerMessages(
                    payload(
                            ClprBundleRequest.newBuilder()
                                    .currentReceivedMessageId(0L)
                                    .currentEndpointManifestVersion(3L)
                                    .build(),
                            null),
                    payload(null, null));

            // The cached observation (5) says current; the peer's own report (3) says stale, and wins.
            subject.synchronize(channel(6L, 0L, 0L), List.of(endpoint()), 5L, 5L);

            verify(stateProofManager).buildBundleProof(eq(CHANNEL_ID), anyLong(), any(), eq(false), eq(true));
        }

        @Test
        @DisplayName("given no peer request, then our manifest is not included")
        void givenNoPeerRequest_thenUseCachedManifestVersion() throws Exception {
            givenBundleWithMessageCount(2);
            givenPeerMessages(payload(null, null), payload(null, null));

            subject.synchronize(channel(6L, 0L, 0L), List.of(endpoint()), 5L, 5L);

            verify(stateProofManager).buildBundleProof(eq(CHANNEL_ID), anyLong(), any(), eq(false), eq(false));
        }
    }

    @Test
    @DisplayName("given the bundle limit is lifted, then each bundle continues where the previous one stopped")
    void givenLiftedBundleLimit_thenEachBundleContinuesFromThePrevious() throws Exception {
        subject = newSubject(3);
        givenBundleWithMessageCount(2);
        givenPeerMessages(
                payload(request(0L), INBOUND_BUNDLE),
                payload(null, INBOUND_BUNDLE),
                payload(null, null),
                payload(null, null));

        synchronize(channel(101L, 0L, 0L));

        final var order = inOrder(stateProofManager);
        order.verify(stateProofManager).buildBundleProof(eq(CHANNEL_ID), eq(1L), any(), eq(false), anyBoolean());
        order.verify(stateProofManager).buildBundleProof(eq(CHANNEL_ID), eq(3L), any(), eq(false), anyBoolean());
        order.verify(stateProofManager).buildBundleProof(eq(CHANNEL_ID), eq(5L), any(), eq(false), anyBoolean());
        // One request plus three bundles; every inbound bundle is submitted.
        captureWrites(4);
        verify(bundleSubmitter, times(2)).submitBundle(any());
        verify(streamingCall).halfClose();
    }

    @Test
    @DisplayName(
            "given mTLS is enabled and the peer has a certificate, then the stream is opened with our leaf credentials")
    void givenMtlsWithPeerCertificate_thenStreamIsOpenedWithLeafCredentials() throws Exception {
        givenMessageQueueIsEmpty();
        final var leafCredentials = mock(ClprLeafCredentials.class);
        given(leafCertManager.isMtlsEnabled()).willReturn(true);
        given(leafCertManager.leafCredentials()).willReturn(leafCredentials);
        givenPeerMessages(payload(request(0L), null));

        synchronize(channel(1L, 0L, 0L));

        verify(clientCache).clientFor(PEER_HOST, PEER_PORT, endpoint().tlsCertificate(), leafCredentials);
        verify(streamingCall).halfClose();
    }

    @Nested
    @DisplayName("inbound bundles")
    class InboundBundles {

        @Test
        @DisplayName("given a peer message whose bundle payload is empty, then nothing is submitted")
        void givenEmptyBundlePayload_thenNothingIsSubmitted() throws Exception {
            givenMessageQueueIsEmpty();
            givenPeerMessages(payload(request(0L), Bytes.EMPTY));

            synchronize(channel(1L, 0L, 0L));

            verifyNoInteractions(bundleSubmitter);
            thenSuccessIsRecorded();
        }

        @Test
        @DisplayName("given the peer keeps writing after our half-close, then its bundles are still submitted")
        void givenPeerWritesAfterHalfClose_thenItsBundlesAreSubmitted() throws Exception {
            givenMessageQueueIsEmpty();
            // We half-close right after the opening; the peer still writes before closing.
            givenPeerMessages(payload(request(0L), null), payload(null, INBOUND_BUNDLE));

            synchronize(channel(1L, 0L, 0L));

            final var order = inOrder(streamingCall, bundleSubmitter);
            order.verify(streamingCall).halfClose();
            order.verify(bundleSubmitter).submitBundle(any());
            thenSuccessIsRecorded();
        }
    }

    @Nested
    @DisplayName("failures")
    class Failures {

        @Test
        @DisplayName("given a write fails, then the peer is penalized and the stream is released")
        void givenWriteFails_thenPeerIsPenalizedAndStreamReleased() throws Exception {
            willThrow(new ClprSyncException("simulated write failure"))
                    .given(streamingCall)
                    .write(any());

            synchronize(channel(6L, 0L, 0L));

            verify(streamingCall).close();
            thenFailureIsRecorded();
            verifyNoInteractions(bundleSubmitter);
        }

        @Test
        @DisplayName("given the peer closes before replying, then the peer is penalized")
        void givenPeerClosesBeforeReplying_thenPeerIsPenalized() throws Exception {
            given(streamingCall.read()).willReturn(null);

            synchronize(channel(6L, 0L, 0L));

            verify(streamingCall).close();
            thenFailureIsRecorded();
        }

        @Test
        @DisplayName("given the peer closes before answering our bundle, then the peer is penalized")
        void givenPeerClosesBeforeAnsweringBundle_thenPeerIsPenalized() throws Exception {
            givenBundleWithMessageCount(2);
            givenPeerMessages(payload(request(0L), null));

            synchronize(channel(6L, 0L, 0L));

            verify(streamingCall, never()).halfClose();
            verify(streamingCall).close();
            thenFailureIsRecorded();
        }

        @Test
        @DisplayName("given the submitter refuses the peer's bundle, then the peer is penalized")
        void givenSubmissionRefused_thenPeerIsPenalized() throws Exception {
            givenMessageQueueIsEmpty();
            given(bundleSubmitter.submitBundle(any())).willReturn(false);
            givenPeerMessages(payload(null, INBOUND_BUNDLE));

            synchronize(channel(1L, 0L, 0L));

            thenFailureIsRecorded();
        }

        @Test
        @DisplayName("given a peer that never goes terminal, then the stream is cut off at the inbound message limit")
        void givenPeerNeverGoesTerminal_thenStreamIsCutAtInboundLimit() throws Exception {
            subject = newSubject(Integer.MAX_VALUE);
            givenBundleWithMessageCount(1);
            given(streamingCall.read()).willReturn(payload(request(0L), INBOUND_BUNDLE), payload(null, INBOUND_BUNDLE));

            synchronize(channel(1_000L, 0L, 0L));

            verify(streamingCall, times(ClprStreamingSyncSession.MAX_INBOUND_MESSAGES + 1))
                    .read();
            verify(streamingCall, never()).halfClose();
            verify(streamingCall).close();
            thenFailureIsRecorded();
        }

        @Test
        @DisplayName("given a peer that never closes after our half-close, then draining stops at the inbound limit")
        void givenPeerNeverClosesAfterHalfClose_thenDrainStopsAtInboundLimit() throws Exception {
            givenMessageQueueIsEmpty();
            // Every message past the opening arrives after our half-close.
            given(streamingCall.read()).willReturn(payload(request(0L), null), payload(null, INBOUND_BUNDLE));

            synchronize(channel(1L, 0L, 0L));

            verify(streamingCall).halfClose();
            verify(streamingCall, times(ClprStreamingSyncSession.MAX_INBOUND_MESSAGES + 1))
                    .read();
            verify(streamingCall).close();
            thenFailureIsRecorded();
        }

        @Test
        @DisplayName("given the mTLS client cannot be built, then the peer is penalized")
        void givenClientCannotBeBuilt_thenPeerIsPenalized() {
            given(clientCache.clientFor(any(), anyInt(), any(), any()))
                    .willThrow(new ClientException("bad cert", new IllegalArgumentException()));

            synchronize(channel(6L, 0L, 0L));

            thenFailureIsRecorded();
        }
    }

    @Nested
    @DisplayName("skipped syncs")
    class Skipped {

        @Test
        @DisplayName("given CLPR is disabled, then nothing is dialed, built or submitted")
        void givenClprDisabled_thenNothingHappens() {
            given(versionedConfig.getConfigData(ClprConfig.class))
                    .willReturn(ClprConfigBuilder.newBuilder().enabled(false).build());

            synchronize(channel(6L, 0L, 0L));

            verifyNoInteractions(clientCache, stateProofManager, bundleSubmitter, peerSelector);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = ClprChannelStatus.class,
                names = {"PENDING", "CLOSED"})
        @DisplayName("given a PENDING or CLOSED channel, then nothing is dialed, built or submitted")
        void givenIneligibleChannelStatus_thenNothingHappens(final ClprChannelStatus status) {
            synchronize(channel(6L, 0L, 0L).copyBuilder().status(status).build());

            verifyNoInteractions(clientCache, stateProofManager, bundleSubmitter, peerSelector);
        }

        @Test
        @DisplayName("given no peer can be selected, then nothing is dialed, built or submitted")
        void givenNoSelectablePeer_thenNothingHappens() {
            given(peerSelector.selectEndpoint(any(), any())).willReturn(null);

            synchronize(channel(6L, 0L, 0L));

            verifyNoInteractions(clientCache, stateProofManager, bundleSubmitter);
            thenNoOutcomeIsRecorded();
        }

        @Test
        @DisplayName("given mTLS is enabled and the peer has no certificate, then no stream is opened")
        void givenMtlsWithoutPeerCertificate_thenNoStreamIsOpened() {
            given(leafCertManager.isMtlsEnabled()).willReturn(true);
            final var endpoint =
                    endpoint().copyBuilder().tlsCertificate(Bytes.EMPTY).build();
            given(peerSelector.selectEndpoint(any(), any())).willReturn(new SelectedPeer(PEER_ID, endpoint));

            synchronize(channel(6L, 0L, 0L));

            verifyNoInteractions(clientCache, stateProofManager, bundleSubmitter);
            thenNoOutcomeIsRecorded();
        }
    }

    private ClprStreamingSynchronizer newSubject(final int maxBundlesPerCycle) {
        return new ClprStreamingSynchronizer(
                configProvider,
                bundleSubmitter,
                stateProofManager,
                leafCertManager,
                clientCache,
                peerSelector,
                maxBundlesPerCycle);
    }

    private void synchronize(final ClprChannel channel) {
        subject.synchronize(channel, List.of(endpoint()), 0L, 0L);
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

    /**
     * Mocks the peer's messages in order, followed by its clean close.
     */
    private void givenPeerMessages(final ClprStreamingSyncPayload first, final ClprStreamingSyncPayload... rest)
            throws ClprSyncException {
        final var scripted = new ClprStreamingSyncPayload[rest.length + 1];
        System.arraycopy(rest, 0, scripted, 0, rest.length);
        scripted[rest.length] = null;
        given(streamingCall.read()).willReturn(first, scripted);
    }

    /**
     * Stubs the builder to pack {@code messageCount} messages from whatever start it is given.
     */
    private void givenBundleWithMessageCount(final int messageCount) {
        given(stateProofManager.buildBundleProof(any(), anyLong(), any(), anyBoolean(), anyBoolean()))
                .willAnswer(inv -> {
                    final long firstMessageId = inv.getArgument(1);
                    return new ClprStateProofManager.BundleProof(
                            OUTBOUND_BUNDLE, messageCount, firstMessageId + messageCount - 1);
                });
    }

    /**
     * Stubs the builder to find nothing queued for the peer, so the client half-closes right after the peer's opening
     * message. Pair it with a channel that has nothing queued ({@code next_message_id = 1}) so the data agrees with the
     * stub.
     */
    private void givenMessageQueueIsEmpty() {
        given(stateProofManager.buildBundleProof(any(), anyLong(), any(), anyBoolean(), anyBoolean()))
                .willReturn(null);
    }

    private List<ClprStreamingSyncPayload> captureWrites(final int expected) throws ClprSyncException {
        final var captor = ArgumentCaptor.forClass(ClprStreamingSyncPayload.class);
        verify(streamingCall, times(expected)).write(captor.capture());
        return captor.getAllValues();
    }

    private static ClprChannel channel(
            final long nextMessageId, final long ackedMessageId, final long receivedMessageId) {
        return ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .status(ClprChannelStatus.ACTIVE)
                .nextMessageId(nextMessageId)
                .ackedMessageId(ackedMessageId)
                .receivedMessageId(receivedMessageId)
                .peerThrottles(ClprThrottles.newBuilder()
                        .maxMessagesPerBundle(5)
                        .maxSyncBytes(1024 * 1024)
                        .build())
                .build();
    }

    private static ClprEndpoint endpoint() {
        return ClprEndpoint.newBuilder()
                .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                        .ipAddress(PEER_HOST)
                        .port(PEER_PORT)
                        .build())
                // Non-empty dummy cert bytes so the tls_certificate skip check does not fire; the client is
                // stubbed via the cache, so the bytes are never parsed.
                .tlsCertificate(Bytes.wrap(new byte[] {1, 2, 3}))
                .build();
    }

    private static ClprBundleRequest request(final long currentReceivedMessageId) {
        return ClprBundleRequest.newBuilder()
                .currentReceivedMessageId(currentReceivedMessageId)
                .currentStatus(ClprChannelStatus.ACTIVE)
                .build();
    }

    private static ClprStreamingSyncPayload payload(
            @Nullable final ClprBundleRequest request, @Nullable final Bytes bundle) {
        return ClprStreamingSyncPayload.newBuilder()
                .channelId(CHANNEL_ID)
                .bundleRequest(request)
                .bundleResponse(
                        bundle == null
                                ? null
                                : ClprBundleResponse.newBuilder()
                                        .bundlePayload(bundle)
                                        .build())
                .build();
    }
}

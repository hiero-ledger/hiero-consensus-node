// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.clpr.ClprBundleRequest;
import com.hedera.hapi.node.state.clpr.ClprBundleResponse;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprStreamingSyncPayload;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.ReadableChannelStore;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.app.service.clpr.impl.ReadableEndpointManifestStoreImpl;
import com.hedera.node.app.store.ReadableStoreFactoryImpl;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Exercises the server side of the streaming sync protocol: the range start the peer's live
 * {@code ClprBundleRequest} produces (the whole point of the two-phase exchange), the guards around a request that
 * is absent, over-claiming, or reports a CLOSED peer, and the loop-termination rules from the ADR's message sequence.
 */
@ExtendWith(MockitoExtension.class)
class ClprStreamingSyncSessionTest {

    private static final Bytes CHANNEL_ID =
            Bytes.fromHex("01000000000000000000000000000000000000000000000000000000000000ab");
    private static final Bytes OTHER_CHANNEL_ID =
            Bytes.fromHex("02000000000000000000000000000000000000000000000000000000000000cd");
    private static final Bytes BUNDLE = Bytes.wrap("outbound-bundle");
    private static final long LOCAL_MANIFEST_VERSION = 7L;

    @Mock
    private Supplier<AutoCloseableWrapper<State>> stateAccessor;

    @Mock
    private State state;

    @Mock
    private ClprBundleSubmitter bundleSubmitter;

    @Mock
    private ClprStateProofManager stateProofManager;

    @Mock
    private ReadableChannelStore channelStore;

    private ClprStreamingSyncSession session;

    @BeforeEach
    void setUp() {
        lenient().when(stateAccessor.get()).thenReturn(new AutoCloseableWrapper<>(state, () -> {}));
        this.session = new ClprStreamingSyncSession(stateAccessor, bundleSubmitter, stateProofManager);
    }

    @Nested
    @DisplayName("ParseAndValidate")
    class ParseAndValidate {

        @Test
        @DisplayName("switching channel_id mid-stream is rejected")
        void rejectsChannelIdSwitch() {
            final var channel = channel(ClprChannelStatus.ACTIVE, 6L, 0L, 0L);

            mockState(channel, () -> {
                session.onMessage(bytes(payload(CHANNEL_ID, request(0L), null)));

                assertThatThrownBy(() -> session.onMessage(bytes(payload(OTHER_CHANNEL_ID, null, BUNDLE))))
                        .isInstanceOf(StatusRuntimeException.class)
                        .hasMessageContaining("changed mid-stream");
            });
        }

        @Test
        @DisplayName("a short channel_id is rejected")
        void rejectsShortChannelId() {
            assertThatThrownBy(() -> session.onMessage(bytes(payload(Bytes.wrap("too-short"), request(0L), null))))
                    .isInstanceOf(StatusRuntimeException.class)
                    .hasMessageContaining("32 bytes");
        }

        @Test
        @DisplayName("an unparseable message is rejected as INVALID_ARGUMENT")
        void rejectsMalformedMessage() {
            assertThatThrownBy(() -> session.onMessage(Bytes.wrap(new byte[] {(byte) 0xFF, (byte) 0xFF})))
                    .isInstanceOf(StatusRuntimeException.class)
                    .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.INVALID_ARGUMENT);
        }

        @Test
        @DisplayName("an unknown channel is rejected as NOT_FOUND")
        void rejectsUnknownChannel() {
            mockState(null, () -> assertThatThrownBy(
                            () -> session.onMessage(bytes(payload(CHANNEL_ID, request(0L), null))))
                    .isInstanceOf(StatusRuntimeException.class)
                    .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.NOT_FOUND));
        }

        @Test
        @DisplayName("a CLOSED local channel is rejected as FAILED_PRECONDITION")
        void rejectsClosedChannel() {
            mockState(channel(ClprChannelStatus.CLOSED, 6L, 0L, 0L), () -> assertThatThrownBy(
                            () -> session.onMessage(bytes(payload(CHANNEL_ID, request(0L), null))))
                    .isInstanceOf(StatusRuntimeException.class)
                    .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION));
        }
    }

    @Test
    @DisplayName("next message range starts at peer's live received_message_id + 1")
    void shapesRangeFromPeerRequest() {
        final var channel = channel(ClprChannelStatus.ACTIVE, 6L, 0L, 2L);
        givenBundle(BUNDLE);

        mockState(channel, () -> {
            final var reply = session.onMessage(bytes(payload(CHANNEL_ID, request(3L), null)));

            assertThat(reply).isNotNull();
            Assertions.assertNotNull(reply.bundleResponse());
            assertThat(reply.bundleResponse().bundlePayload()).isEqualTo(BUNDLE);
        });

        verify(stateProofManager).buildBundleProof(eq(CHANNEL_ID), eq(4L), any(), eq(true), anyBoolean());
    }

    @Test
    @DisplayName("when receiving a request, reply with its bundle request")
    void replyFirstRequestWithItsOwnRequest() {
        final var channel = channel(ClprChannelStatus.ACTIVE, 6L, 0L, 2L);
        givenBundle(BUNDLE);

        mockState(channel, () -> {
            final var first = session.onMessage(bytes(payload(CHANNEL_ID, request(3L), null)));
            final var second = session.onMessage(bytes(payload(CHANNEL_ID, null, BUNDLE)));

            assertThat(first).isNotNull();
            assertThat(first.bundleRequest()).isNotNull();
            assertThat(first.bundleRequest().currentReceivedMessageId()).isEqualTo(2L);
            assertThat(first.bundleRequest().currentStatus()).isEqualTo(ClprChannelStatus.ACTIVE);
            assertThat(first.bundleRequest().currentEndpointManifestVersion()).isEqualTo(4L);

            // One-shot: it is structurally absent from every later message on this side.
            assertThat(second).isNotNull();
            assertThat(second.bundleRequest()).isNull();
        });
    }

    @Test
    @DisplayName("when requested message id is >= next message id, falls back to next message id")
    void peerMessageIsAheadFallsBackToAcked() {
        // next_message_id is 6, so message 6 has never been sent; a peer claiming to hold it is naming
        // something that does not exist.
        final var channel = channel(ClprChannelStatus.ACTIVE, 6L, 2L, 0L);
        givenBundle(BUNDLE);

        mockState(channel, () -> session.onMessage(bytes(payload(CHANNEL_ID, request(6L), null))));

        verify(stateProofManager).buildBundleProof(eq(CHANNEL_ID), eq(3L), any(), eq(true), anyBoolean());
    }

    @Test
    @DisplayName("when peer requests next message")
    void peerRequestNextMessageInQueue() {
        final var channel = channel(ClprChannelStatus.ACTIVE, 6L, 0L, 0L);

        mockState(channel, () -> session.onMessage(bytes(payload(CHANNEL_ID, request(5L), null))));

        // 5 is the highest ID actually assigned (next_message_id - 1), so the range legitimately starts at 6 —
        // an empty range, which yields the pure-ACK bundle allowPureAck=true asks for.
        verify(stateProofManager).buildBundleProof(eq(CHANNEL_ID), eq(6L), any(), eq(true), anyBoolean());
    }

    @Test
    @DisplayName("when no request from the peer, the range falls back to acked_message_id + 1")
    void absentRequestFallsBackToAcked() {
        final var channel = channel(ClprChannelStatus.ACTIVE, 6L, 3L, 0L);
        givenBundle(BUNDLE);

        mockState(channel, () -> {
            final var reply = session.onMessage(bytes(payload(CHANNEL_ID, null, BUNDLE)));

            // Opening with a bundle and no request is legitimate — "nothing to ask for, but here is my data" — so it
            // must not be read as terminal, and our own one-shot request goes out regardless of the peer sending none.
            assertThat(reply).isNotNull();
            assertThat(reply.bundleRequest()).isNotNull();
            assertThat(reply.bundleRequest().currentReceivedMessageId()).isEqualTo(0L);
            assertThat(session.isComplete()).isFalse();
        });

        verify(stateProofManager).buildBundleProof(eq(CHANNEL_ID), eq(4L), any(), eq(true), anyBoolean());
    }

    @Test
    @DisplayName("no bundle is built for a peer that reports itself CLOSED")
    void skipsBundleForClosedPeer() {
        final var channel = channel(ClprChannelStatus.ACTIVE, 6L, 0L, 0L);

        mockState(channel, () -> {
            final var reply = session.onMessage(bytes(payload(
                    CHANNEL_ID,
                    ClprBundleRequest.newBuilder()
                            .currentReceivedMessageId(3L)
                            .currentStatus(ClprChannelStatus.CLOSED)
                            .build(),
                    null)));

            assertThat(reply).isNotNull();
            assertThat(reply.bundleResponse()).isNull();
        });

        verify(stateProofManager, never()).buildBundleProof(any(), anyLong(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("the manifest leaf is embedded when the peer's request reports a stale manifest version")
    void embedsManifestWhenPeerRequestIsStale() {
        final var channel = channel(ClprChannelStatus.ACTIVE, 6L, 0L, 0L);

        // The request's version (4) is our cache axis; the peer's own report is what decides Criterion 5.
        mockState(
                channel,
                () -> session.onMessage(bytes(payload(
                        CHANNEL_ID,
                        ClprBundleRequest.newBuilder()
                                .currentReceivedMessageId(0L)
                                .currentEndpointManifestVersion(LOCAL_MANIFEST_VERSION - 1)
                                .build(),
                        null))));

        verify(stateProofManager).buildBundleProof(eq(CHANNEL_ID), anyLong(), any(), eq(true), eq(true));
    }

    @Test
    @DisplayName("an inbound bundle is submitted for consensus")
    void submitsInboundBundle() {
        final var channel = channel(ClprChannelStatus.ACTIVE, 6L, 0L, 0L);
        final var inbound = Bytes.wrap("inbound-bundle");

        mockState(channel, () -> session.onMessage(bytes(payload(CHANNEL_ID, null, inbound))));

        verify(bundleSubmitter)
                .submitBundle(ClprSyncPayload.newBuilder()
                        .channelId(CHANNEL_ID)
                        .bundlePayload(inbound)
                        .build());
    }

    @Test
    @DisplayName("when submitBundle fails, error is not propagated")
    void submitFailureDoesNotFailTheStream() {
        final var channel = channel(ClprChannelStatus.ACTIVE, 6L, 0L, 2L);
        givenBundle(BUNDLE);
        given(bundleSubmitter.submitBundle(any())).willThrow(new RuntimeException("gossip unavailable"));

        mockState(channel, () -> {
            final var reply = session.onMessage(bytes(payload(CHANNEL_ID, request(3L), Bytes.wrap("inbound"))));

            // Failing the call would also discard our own bundle and our ACK of what the peer sent, neither of
            // which depends on their bundle reaching consensus.
            assertThat(reply).isNotNull();
            assertThat(reply.bundleRequest()).isNotNull();
            assertThat(reply.bundleResponse().bundlePayload()).isEqualTo(BUNDLE);
            assertThat(session.isComplete()).isFalse();

            // And the peer sees the failure without a status code: our watermark is unmoved, so its next cycle
            // asks for the same range again.
            assertThat(reply.bundleRequest().currentReceivedMessageId()).isEqualTo(2L);
        });
    }

    @Test
    @DisplayName("full cycle is completed with success")
    void completesTheFourMessageCycle() {
        final var channel = channel(ClprChannelStatus.ACTIVE, 6L, 0L, 2L);
        givenBundle(BUNDLE);

        mockState(channel, () -> {
            // Msg 1 in → msg 2 out: our request plus the bundle shaped for the peer.
            final var second = session.onMessage(bytes(payload(CHANNEL_ID, request(3L), null)));
            assertThat(second).isNotNull();
            assertThat(second.bundleRequest()).isNotNull();
            assertThat(second.bundleResponse()).isNotNull();
            assertThat(session.isComplete()).isFalse();

            // Msg 3 in (the peer's bundle) → msg 4 out: nothing progress-bearing left, so terminal. The peer has
            // not gone terminal yet, so the stream must stay open for whatever it still wants to write.
            final var fourth = session.onMessage(bytes(payload(CHANNEL_ID, null, BUNDLE)));
            assertThat(fourth).isNotNull();
            assertThat(fourth.bundleRequest()).isNull();
            assertThat(fourth.bundleResponse()).isNull();
            assertThat(session.isComplete()).isFalse();

            // The peer's terminal message: now both sides are done and there is nothing left to send.
            assertThat(session.onMessage(bytes(payload(CHANNEL_ID, null, null))))
                    .isNull();
            assertThat(session.isComplete()).isTrue();
        });
    }

    @Test
    @DisplayName("a peer that opens with its terminal message closes the exchange with no reply")
    void closesImmediatelyWhenPeerOpensTerminal() {
        // Both fields absent on the very first message: the peer has nothing to send and nothing to ask for, so
        // there is no cycle to run. State is never touched.
        assertThat(session.onMessage(bytes(payload(CHANNEL_ID, null, null)))).isNull();
        assertThat(session.isComplete()).isTrue();

        verify(stateProofManager, never()).buildBundleProof(any(), anyLong(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("null peer's bundle request generates a normal response")
    void absentRequestIsNotTermination() {
        final var channel = channel(ClprChannelStatus.ACTIVE, 6L, 0L, 0L);
        givenBundle(BUNDLE);

        mockState(channel, () -> {
            final var response = session.onMessage(bytes(payload(CHANNEL_ID, request(0L), null)));
            assertThat(response).isNotNull();
            assertThat(response.bundleResponse()).isNotNull();
            assertThat(response.bundleRequest()).isNotNull();
            assertThat(session.isComplete()).isFalse();
        });

        verify(stateProofManager).buildBundleProof(eq(CHANNEL_ID), eq(1L), any(), eq(true), anyBoolean());
    }

    @Test
    @DisplayName("a follow-up bundle would continue where the previous one stopped")
    void cursorAdvancesPastEachBundle() {
        // The limit pins us to one bundle today, but the machinery that would chain them is what is under test:
        // the range start comes from the peer's request, and the next one comes from what the builder actually packed.
        final var channel = channel(ClprChannelStatus.ACTIVE, 101L, 0L, 0L);
        givenBundle(BUNDLE, 5);

        mockState(channel, () -> {
            session.onMessage(bytes(payload(CHANNEL_ID, request(3L), null)));
            assertThat(session.nextRangeStart())
                    .as("first bundle starts at the peer's live received+1 = 4 and packs 5, so the cursor lands on 9")
                    .isEqualTo(9L);
        });
    }

    @Test
    @DisplayName("when max bundle limit reached, replies with an empty bundle")
    void buildsAtMostOneBundlePerCycle() {
        final var channel = channel(ClprChannelStatus.ACTIVE, 101L, 0L, 0L);
        givenBundle(BUNDLE);

        mockState(channel, () -> {
            final var second = session.onMessage(bytes(payload(CHANNEL_ID, request(0L), null)));
            assertThat(second).isNotNull();
            assertThat(second.bundleResponse()).isNotNull();

            // next replies are always empty bundles.
            for (int i = 0; i < 3; i++) {
                final var reply = session.onMessage(bytes(payload(CHANNEL_ID, null, Bytes.wrap("peer-bundle"))));
                assertThat(reply).isNotNull();
                assertThat(reply.bundleResponse()).isNull();
            }
        });

        verify(stateProofManager).buildBundleProof(eq(CHANNEL_ID), eq(1L), any(), eq(true), anyBoolean());
    }

    @Test
    @DisplayName("when max inbound messages are reached then the session is closed")
    void closesAtTheExchangeLimit() {
        final var channel = channel(ClprChannelStatus.ACTIVE, 6L, 0L, 0L);
        givenBundle(BUNDLE);

        mockState(channel, () -> {
            // The peer keeps writing bundles and never sends its terminal message.
            for (int i = 0; i < ClprStreamingSyncSession.MAX_INBOUND_MESSAGES; i++) {
                assertThat(session.onMessage(bytes(payload(CHANNEL_ID, null, Bytes.wrap("peer-bundle")))))
                        .as("message %d is within the limit and must be answered", i + 1)
                        .isNotNull();
                assertThat(session.isComplete()).isFalse();
            }

            // One past the limit: closed with no reply, so the transport tears the stream down.
            assertThat(session.onMessage(bytes(payload(CHANNEL_ID, null, Bytes.wrap("peer-bundle")))))
                    .isNull();
            assertThat(session.isComplete()).isTrue();
        });

        // The bundle that tripped the cap is still submitted — it is well-formed and the peer already paid for it.
        verify(bundleSubmitter, times(ClprStreamingSyncSession.MAX_INBOUND_MESSAGES + 1))
                .submitBundle(any());
    }

    /**
     * Runs {@code body} with the store constructors stubbed out, so the session reads {@code channel} and a
     * manifest at {@link #LOCAL_MANIFEST_VERSION} instead of touching real state.
     */
    private void mockState(@Nullable final ClprChannel channel, final Runnable body) {
        given(channelStore.getChannel(CHANNEL_ID)).willReturn(channel);
        try (var storeFactory = mockConstruction(ReadableStoreFactoryImpl.class, (mock, ctx) -> given(
                                mock.readableStore(ReadableChannelStore.class))
                        .willReturn(channelStore));
                var manifestStore = mockConstruction(ReadableEndpointManifestStoreImpl.class, (mock, ctx) -> lenient()
                        .when(mock.get())
                        .thenReturn(ClprEndpointManifest.newBuilder()
                                .version(LOCAL_MANIFEST_VERSION)
                                .build()))) {
            body.run();
        }
    }

    /** Stubs the builder to return {@code bundle} covering {@code messageCount} messages from whatever start it is given. */
    private void givenBundle(final Bytes bundle) {
        givenBundle(bundle, 2);
    }

    private void givenBundle(final Bytes bundle, final int messageCount) {
        lenient()
                .when(stateProofManager.buildBundleProof(any(), anyLong(), any(), anyBoolean(), anyBoolean()))
                .thenAnswer(inv -> {
                    final long firstMessageId = inv.getArgument(1);
                    return new ClprStateProofManager.BundleProof(
                            bundle, messageCount, firstMessageId + messageCount - 1);
                });
    }

    private static ClprChannel channel(
            final ClprChannelStatus status,
            final long nextMessageId,
            final long ackedMessageId,
            final long receivedMessageId) {
        return ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .status(status)
                .nextMessageId(nextMessageId)
                .ackedMessageId(ackedMessageId)
                .receivedMessageId(receivedMessageId)
                .endpointManifestVersion(4L)
                .peerThrottles(ClprThrottles.newBuilder()
                        .maxMessagesPerBundle(5)
                        .maxSyncBytes(1024 * 1024)
                        .build())
                .build();
    }

    private static ClprBundleRequest request(final long currentReceivedMessageId) {
        return ClprBundleRequest.newBuilder()
                .currentReceivedMessageId(currentReceivedMessageId)
                .currentStatus(ClprChannelStatus.ACTIVE)
                .build();
    }

    private static ClprStreamingSyncPayload payload(
            final Bytes channelId, @Nullable final ClprBundleRequest request, @Nullable final Bytes bundle) {
        return ClprStreamingSyncPayload.newBuilder()
                .channelId(channelId)
                .bundleRequest(request)
                .bundleResponse(
                        bundle == null
                                ? null
                                : ClprBundleResponse.newBuilder()
                                        .bundlePayload(bundle)
                                        .build())
                .build();
    }

    private static Bytes bytes(final ClprStreamingSyncPayload payload) {
        return ClprStreamingSyncPayload.PROTOBUF.toBytes(payload);
    }
}

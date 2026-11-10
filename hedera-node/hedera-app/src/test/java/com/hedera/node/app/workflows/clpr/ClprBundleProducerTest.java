// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static com.hedera.hapi.node.base.BlockHashAlgorithm.SHA2_256;
import static com.hedera.hapi.node.state.clpr.ClprBundleFormat.CLPR_BUNDLE_FORMAT_HIERO_PROOF_V1;
import static com.hedera.node.app.hapi.utils.MiscCryptoUtils.keccak256DigestOf;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.SIGNATURE_LENGTH;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.VK_LENGTH;
import static com.hedera.node.app.service.clpr.impl.ClprHieroProofSupport.SkipReason.INVALID_PROOF;
import static com.hedera.node.app.service.clpr.impl.ClprHieroProofSupport.SkipReason.SHA_384_NETWORK;
import static com.hedera.node.app.service.clpr.impl.ClprHieroProofSupport.SkipReason.UNKNOWN_ANCHOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.SiblingNode;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.ClprBundleRequest;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.HintsKeyWitness;
import com.hedera.hapi.node.state.history.HintsKeyWitnessRecord;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager.BundleProof;
import com.hedera.node.app.spi.state.BlockProvenSnapshot;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprBundleProducerTest {

    private static final Bytes CHANNEL_ID =
            Bytes.fromHex("01000000000000000000000000000000000000000000000000000000000000ab");
    private static final Bytes BUNDLE = Bytes.wrap("bundle");
    private static final ClprThrottles PEER_THROTTLES = ClprThrottles.newBuilder()
            .maxMessagesPerBundle(5)
            .maxSyncBytes(1024 * 1024)
            .build();

    @Mock
    private ClprStateProofManager stateProofManager;

    @Nested
    @DisplayName("initial message id")
    class ResolveInitialMessageId {

        @Test
        @DisplayName("given a peer that received up to message 5, then the range starts at 6")
        void givenPeerReceivedUpToFive_thenRangeStartsAtSix() {
            final var producer = producer(channel(10L, 2L), request(5L));

            assertThat(producer.nextStartingMessageId()).isEqualTo(6L);
            assertThat(producer.overClaimFallback()).isFalse();
        }

        @Test
        @DisplayName("given a peer that has received nothing, then the range starts at 1")
        void givenPeerReceivedNothing_thenRangeStartsAtOne() {
            assertThat(producer(channel(10L, 0L), request(0L)).nextStartingMessageId())
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("given no peer request, then the range starts at acked_message_id + 1")
        void givenNoPeerRequest_thenRangeStartsAfterAcked() {
            final var producer = producer(channel(10L, 3L), null);

            assertThat(producer.nextStartingMessageId()).isEqualTo(4L);
            assertThat(producer.overClaimFallback()).isFalse();
            assertThat(producer.peerClosed()).isFalse();
        }

        @Test
        @DisplayName(
                "given a peer claiming less than acked_message_id, then the range starts at its claim + 1, not at acked_message_id + 1")
        void givenUnderClaimBelowAcked_thenRangeIsNotClampedToAcked() {
            final var producer = producer(channel(10L, 7L), request(2L));

            assertThat(producer.nextStartingMessageId()).isEqualTo(3L);
            assertThat(producer.overClaimFallback()).isFalse();
        }

        @Test
        @DisplayName(
                "given a peer claiming next_message_id - 1, then the claim is accepted and the range starts at next_message_id")
        void givenClaimOfLastSentMessage_thenRangeStartsAtNextMessageId() {
            final var producer = producer(channel(10L, 2L), request(9L));

            assertThat(producer.nextStartingMessageId()).isEqualTo(10L);
            assertThat(producer.overClaimFallback()).isFalse();
        }

        @ParameterizedTest(name = "received={0} with next_message_id=10")
        @ValueSource(longs = {10L, 11L, 1_000L})
        @DisplayName("given a peer claiming a message never sent, then the range falls back to acked_message_id + 1")
        void givenOverClaim_thenRangeFallsBackToAcked(final long claimed) {
            final var producer = producer(channel(10L, 2L), request(claimed));

            assertThat(producer.nextStartingMessageId()).isEqualTo(3L);
            assertThat(producer.overClaimFallback()).isTrue();
        }

        @Test
        @DisplayName(
                "given a peer claiming Long.MAX_VALUE, then the range falls back to acked_message_id + 1 without overflowing")
        void givenMaxValueOverClaim_thenRangeFallsBackWithoutOverflow() {
            final var producer = producer(channel(10L, 2L), request(Long.MAX_VALUE));

            assertThat(producer.nextStartingMessageId()).isEqualTo(3L);
            assertThat(producer.overClaimFallback()).isTrue();
        }

        @Test
        @DisplayName(
                "given a Channel that has sent nothing and a peer claiming any message, then it is treated as an over-claim")
        void givenAnyClaimOnChannelThatSentNothing_thenItIsAnOverClaim() {
            final var producer = producer(channel(1L, 0L), request(1L));

            assertThat(producer.nextStartingMessageId()).isEqualTo(1L);
            assertThat(producer.overClaimFallback()).isTrue();
        }
    }

    @Nested
    @DisplayName("peer status")
    class PeerStatus {

        @Test
        @DisplayName("given a CLOSED peer, then next() returns null and the builder is never called")
        void givenClosedPeer_thenNextReturnsNullWithoutBuilding() {
            final var producer = producer(channel(10L, 0L), request(3L, ClprChannelStatus.CLOSED));

            assertThat(producer.peerClosed()).isTrue();
            assertThat(producer.hasBundleToOffer()).isFalse();
            assertThat(producer.next(false)).isNull();
            verifyNoInteractions(stateProofManager);
        }

        @Test
        @DisplayName("given a CLOSED peer that also over-claims, then both are reported and nothing is offered")
        void givenClosedPeerThatOverClaims_thenBothAreReported() {
            final var producer = producer(channel(10L, 2L), request(10L, ClprChannelStatus.CLOSED));

            assertThat(producer.peerClosed()).isTrue();
            assertThat(producer.overClaimFallback()).isTrue();
            assertThat(producer.hasBundleToOffer()).isFalse();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ClprChannelStatus.class, names = "CLOSED", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("given any peer status other than CLOSED, then next() returns a bundle")
        void givenNonClosedPeerStatus_thenNextReturnsBundle(final ClprChannelStatus status) {
            givenBundleOf(2);
            final var producer = producer(channel(10L, 0L), request(3L, status));

            assertThat(producer.peerClosed()).isFalse();
            assertThat(producer.next(false)).isEqualTo(BUNDLE);
        }
    }

    @Nested
    @DisplayName("next()")
    class Next {

        @ParameterizedTest(name = "allowPureAck={0}, includeEndpointManifest={1}")
        @CsvSource({"true,true", "true,false", "false,true", "false,false"})
        @DisplayName(
                "given the caller's flags, then next() builds against the Channel, the resolved range start, and those flags")
        void givenCallerFlags_thenBuilderReceivesChannelRangeAndFlags(
                final boolean allowPureAck, final boolean includeEndpointManifest) {
            givenBundleOf(2);
            final var producer = new ClprBundleProducer(stateProofManager, channel(10L, 0L), request(3L), allowPureAck);

            assertThat(producer.next(includeEndpointManifest)).isEqualTo(BUNDLE);

            verify(stateProofManager)
                    .buildBundleProof(CHANNEL_ID, 4L, PEER_THROTTLES, allowPureAck, includeEndpointManifest);
        }

        @Test
        @DisplayName(
                "given several bundles allowed, when next() is called repeatedly, then each bundle continues where the previous one stopped")
        void givenSeveralBundlesAllowed_whenNextIsCalledRepeatedly_thenEachContinuesFromThePrevious() {
            givenBundleOf(2);
            final var producer = producer(channel(100L, 0L), request(0L), 3);

            assertThat(producer.next(false)).isEqualTo(BUNDLE);
            assertThat(producer.nextStartingMessageId()).isEqualTo(3L);
            assertThat(producer.next(false)).isEqualTo(BUNDLE);
            assertThat(producer.nextStartingMessageId()).isEqualTo(5L);
            assertThat(producer.next(false)).isEqualTo(BUNDLE);
            assertThat(producer.nextStartingMessageId()).isEqualTo(7L);

            final var order = inOrder(stateProofManager);
            order.verify(stateProofManager).buildBundleProof(any(), eq(1L), any(), anyBoolean(), anyBoolean());
            order.verify(stateProofManager).buildBundleProof(any(), eq(3L), any(), anyBoolean(), anyBoolean());
            order.verify(stateProofManager).buildBundleProof(any(), eq(5L), any(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName(
                "given the builder packs fewer messages than allowed, then the range advances only by what was packed")
        void givenBuilderPacksFewerMessages_thenRangeAdvancesByPackedCount() {
            // max_messages_per_bundle is 5, but max_sync_bytes let only one message through.
            givenBundleOf(1);
            final var producer = producer(channel(100L, 0L), request(0L), 2);

            producer.next(false);

            assertThat(producer.nextStartingMessageId()).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("end of the cycle")
    class EndOfCycle {

        @Test
        @DisplayName(
                "given the default limit, when next() is called repeatedly, then it stops after MAX_BUNDLE_EXCHANGES bundles")
        void givenDefaultLimit_whenNextIsCalledRepeatedly_thenStopsAtMaxBundleExchanges() {
            givenBundleOf(2);
            final var producer = new ClprBundleProducer(stateProofManager, channel(100L, 0L), request(0L), true);

            for (int i = 0; i < ClprBundleProducer.MAX_BUNDLE_EXCHANGES; i++) {
                assertThat(producer.next(false)).isEqualTo(BUNDLE);
            }
            assertThat(producer.hasBundleToOffer()).isFalse();
            assertThat(producer.next(false)).isNull();

            verify(stateProofManager, times(ClprBundleProducer.MAX_BUNDLE_EXCHANGES))
                    .buildBundleProof(any(), anyLong(), any(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName("given the bundle limit is reached, then next() returns null without calling the builder")
        void givenLimitReached_thenNextReturnsNullWithoutBuilding() {
            givenBundleOf(2);
            final var producer = producer(channel(100L, 0L), request(0L), 2);

            producer.next(false);
            producer.next(false);

            assertThat(producer.hasBundleToOffer()).isFalse();
            assertThat(producer.next(false)).isNull();
            assertThat(producer.nextStartingMessageId())
                    .as("the range position is left where the last bundle stopped")
                    .isEqualTo(5L);
            verify(stateProofManager, times(2)).buildBundleProof(any(), anyLong(), any(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName("given a limit of zero, then next() returns null without calling the builder")
        void givenZeroLimit_thenNextReturnsNullWithoutBuilding() {
            final var producer = producer(channel(100L, 0L), request(0L), 0);

            assertThat(producer.hasBundleToOffer()).isFalse();
            assertThat(producer.next(false)).isNull();
            verifyNoInteractions(stateProofManager);
        }

        @Test
        @DisplayName(
                "given the builder returned null, when next() is called again, then it returns null without retrying")
        void givenBuilderReturnedNull_whenNextIsCalledAgain_thenDoesNotRetry() {
            given(stateProofManager.buildBundleProof(any(), anyLong(), any(), anyBoolean(), anyBoolean()))
                    .willReturn(null);
            final var producer = producer(channel(100L, 0L), request(0L), 5);

            assertThat(producer.next(false)).isNull();
            assertThat(producer.hasBundleToOffer()).isFalse();
            assertThat(producer.next(false)).isNull();
            assertThat(producer.nextStartingMessageId())
                    .as("nothing was packed")
                    .isEqualTo(1L);

            verify(stateProofManager, times(1)).buildBundleProof(any(), anyLong(), any(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName(
                "given a bundle with no messages was returned, when next() is called again, then it returns null instead of rebuilding it")
        void givenMessageLessBundle_whenNextIsCalledAgain_thenDoesNotRebuildIt() {
            // A pure-ACK or manifest-only bundle: nothing packed, so lastMessageId sits just before the range start.
            given(stateProofManager.buildBundleProof(any(), anyLong(), any(), anyBoolean(), anyBoolean()))
                    .willAnswer(inv -> new BundleProof(BUNDLE, 0, inv.<Long>getArgument(1) - 1));
            final var producer = producer(channel(10L, 0L), request(9L), 5);

            assertThat(producer.next(false)).isEqualTo(BUNDLE);
            assertThat(producer.nextStartingMessageId())
                    .as("nothing was consumed")
                    .isEqualTo(10L);
            assertThat(producer.hasBundleToOffer()).isFalse();
            assertThat(producer.next(false)).isNull();

            verify(stateProofManager, times(1)).buildBundleProof(any(), anyLong(), any(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName("given next() returned a bundle that carried messages, then the cycle stays open for another")
        void givenBundleWithMessages_thenMoreBundlesMayFollow() {
            givenBundleOf(1);
            final var producer = producer(channel(100L, 0L), request(0L), 5);

            producer.next(false);

            assertThat(producer.hasBundleToOffer()).isTrue();
        }

        @Test
        @DisplayName("given a fresh producer, then it has a bundle to offer")
        void givenFreshProducer_thenHasBundleToOffer() {
            assertThat(producer(channel(10L, 0L), request(0L)).hasBundleToOffer())
                    .isTrue();
            assertThat(producer(channel(10L, 0L), null).hasBundleToOffer()).isTrue();
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("given a null state proof manager, then construction throws")
        void givenNullStateProofManager_thenConstructionThrows() {
            assertThatThrownBy(() -> new ClprBundleProducer(null, channel(10L, 0L), null, true))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("given a null channel, then construction throws")
        void givenNullChannel_thenConstructionThrows() {
            assertThatThrownBy(() -> new ClprBundleProducer(stateProofManager, null, null, true))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class CompactPeer {
        private static final int NATIVE_SIGNATURE_LENGTH = 13_712;

        @Test
        void wrapsStateProofForCurrentAnchorAndUsesCompactPayloadSize() {
            final var snapshot = signedSnapshot(1);
            final var expected = givenMessageProof(snapshot, 1, List.of());
            final var producer = producer(channel(10, 0), compactRequest(1), 2);

            given(stateProofManager.verifyHieroProof(eq(expected), any())).willReturn(true);
            assertThat(producer.next(false)).isEqualTo(expected);
            assertThat(producer.nextStartingMessageId()).isEqualTo(2);
            assertThat(producer.hasBundleToOffer()).isTrue();
        }

        @Test
        void shortRotationCarriesMessagesAndWaitsForAnchorConfirmation() {
            final var snapshot = signedSnapshot(2);
            final var links = givenAdoptionHistory(snapshot, 1, 0);
            final var expected = givenMessageProof(snapshot, 2, links);
            final var producer = producer(channel(10, 0), compactRequest(1), 2);

            given(stateProofManager.verifyHieroProof(eq(expected), any())).willReturn(true);
            assertThat(producer.next(false)).isEqualTo(expected);
            assertThat(producer.nextStartingMessageId()).isEqualTo(2);
            assertThat(producer.next(false)).isNull();
        }

        @Test
        void witnessOnlyProgressKeepsTheCursorAndTheNextRequestCanDeliver() {
            final var snapshot = signedSnapshot(10);
            final var links = givenAdoptionHistory(snapshot, 9, 8);
            final var expected = ClprHieroProofCodec.encodeWitness(links.subList(0, 8), witness(8));
            final var producer = producer(channel(1, 0), compactRequest(1), 2);

            given(stateProofManager.verifyHieroProof(eq(expected), any())).willReturn(true);
            assertThat(producer.next(false)).isEqualTo(expected);
            assertThat(producer.nextStartingMessageId()).isEqualTo(1);
            assertThat(producer.next(false)).isNull();
            verify(stateProofManager, never())
                    .buildBundleProof(any(), any(), anyLong(), any(), anyBoolean(), anyBoolean(), any());

            final var expectedMessage = givenMessageProof(snapshot, 10, links.subList(8, 9));
            final var nextCycle = producer(channel(10, 0), compactRequest(9));
            given(stateProofManager.verifyHieroProof(eq(expectedMessage), any()))
                    .willReturn(true);
            assertThat(nextCycle.next(false)).isEqualTo(expectedMessage);
            assertThat(nextCycle.nextStartingMessageId()).isEqualTo(2);
        }

        @Test
        void missingBatchEndpointKeepsTheCursor() {
            final var snapshot = signedSnapshot(10);
            givenAdoptionHistory(snapshot, 9, 0);
            final var producer = producer(channel(10, 0), compactRequest(1));

            assertThat(producer.next(false)).isNull();
            assertThat(producer.nextStartingMessageId()).isEqualTo(1);
        }

        @Test
        void unknownAnchorKeepsTheCursor() {
            final var snapshot = signedSnapshot(2);
            givenAdoptionHistory(snapshot, 1, 0);
            final var producer = producer(channel(10, 0), compactRequest(3));

            assertThat(producer.next(false)).isNull();
            assertThat(producer.nextStartingMessageId()).isEqualTo(1);
        }

        @Test
        void failedSenderCheckDoesNotSendOrMoveTheCursor() {
            final var snapshot = signedSnapshot(1);
            final var expected = givenMessageProof(snapshot, 1, List.of());
            final var request = compactRequest(1);
            final var producer = producer(channel(10, 0), request);

            assertThat(producer.next(false)).isNull();
            assertThat(producer.nextStartingMessageId()).isEqualTo(1);
            verify(stateProofManager).verifyHieroProof(expected, request.currentTrustAnchorId());
            verify(stateProofManager).reportHieroProofSkip(CHANNEL_ID, INVALID_PROOF);
        }

        @Test
        void sha384NetworkDoesNotBuildCompactProofs() {
            assertThat(producer(channel(10, 0), compactRequest(1)).next(false)).isNull();
            verify(stateProofManager, never()).latestSnapshot();
            verify(stateProofManager).reportHieroProofSkip(CHANNEL_ID, SHA_384_NETWORK);
        }

        @Test
        void ledgerIdDoesNotSelectAnAnchorForCompactProofs() {
            final var request = compactRequest(1)
                    .copyBuilder()
                    .currentTrustAnchorId(Bytes.wrap(new byte[64]))
                    .build();
            assertThat(producer(channel(10, 0), request).next(false)).isNull();
            verify(stateProofManager, never()).latestSnapshot();
            verify(stateProofManager).reportHieroProofSkip(CHANNEL_ID, UNKNOWN_ANCHOR);
        }

        private BlockProvenSnapshot signedSnapshot(final int signer) {
            final var snapshot = mock(BlockProvenSnapshot.class);
            given(stateProofManager.supportsHieroProofV1()).willReturn(true);
            given(stateProofManager.latestSnapshot()).willReturn(Optional.of(snapshot));
            given(snapshot.path())
                    .willReturn(MerklePath.newBuilder()
                            .siblings(SiblingNode.newBuilder()
                                    .hash(Bytes.wrap(new byte[32]))
                                    .build())
                            .build());
            given(snapshot.tssSignature()).willReturn(signature(signer));
            return snapshot;
        }

        private List<HintsKeyProvenance> givenAdoptionHistory(
                final BlockProvenSnapshot snapshot, final int count, final int witnessedLink) {
            final var links = new ArrayList<HintsKeyProvenance>();
            for (int i = 1; i <= count; i++) {
                links.add(HintsKeyProvenance.newBuilder()
                        .sourceVerificationKey(key(i))
                        .targetVerificationKey(key(i + 1))
                        .targetHintsConstructionId(i)
                        .signature(Bytes.wrap(new byte[SIGNATURE_LENGTH]))
                        .build());
            }
            final var records = links.stream()
                    .map(link -> {
                        final var record = HintsKeyWitnessRecord.newBuilder().provenance(link);
                        if (link.targetHintsConstructionId() == witnessedLink) {
                            record.witness(witness(witnessedLink));
                        }
                        return record.build();
                    })
                    .toList();
            given(snapshot.keyProvenanceWithWitnesses()).willReturn(records);
            return links;
        }

        private Bytes givenMessageProof(
                final BlockProvenSnapshot snapshot, final int signer, final List<HintsKeyProvenance> links) {
            final var proof = StateProof.newBuilder()
                    .paths(MerklePath.newBuilder()
                            .hash(Bytes.wrap(new byte[32]))
                            .build())
                    .signedBlockProof(TssSignedBlockProof.newBuilder()
                            .blockSignature(signature(signer))
                            .build())
                    .build();
            final var raw = StateProof.PROTOBUF.toBytes(proof);
            final var expected =
                    ClprHieroProofCodec.encodeStateProof(ClprHieroProofCodec.compactStateProof(raw), links);
            given(stateProofManager.buildBundleProof(
                            eq(snapshot),
                            eq(CHANNEL_ID),
                            eq(1L),
                            eq(PEER_THROTTLES),
                            anyBoolean(),
                            anyBoolean(),
                            any()))
                    .willAnswer(inv -> {
                        final ToLongFunction<StateProof> payloadSize = inv.getArgument(6);
                        assertThat(payloadSize.applyAsLong(proof)).isEqualTo(expected.length());
                        return new BundleProof(raw, 1, 1);
                    });
            return expected;
        }

        private static ClprBundleRequest compactRequest(final int signer) {
            return request(0)
                    .copyBuilder()
                    .bundleFormat(CLPR_BUNDLE_FORMAT_HIERO_PROOF_V1)
                    .currentTrustAnchorId(keccak256DigestOf(key(signer)))
                    .build();
        }

        private static HintsKeyWitness witness(final int construction) {
            return HintsKeyWitness.newBuilder()
                    .targetHintsConstructionId(construction)
                    .timestamp(new Timestamp(1, 0))
                    .siblingHash(Bytes.wrap(new byte[32]))
                    .signature(Bytes.wrap(new byte[SIGNATURE_LENGTH]))
                    .hashAlgorithm(SHA2_256)
                    .build();
        }

        private static Bytes key(final int signer) {
            return signature(signer).slice(0, VK_LENGTH);
        }

        private static Bytes signature(final int signer) {
            final var signature = new byte[NATIVE_SIGNATURE_LENGTH];
            signature[0] = (byte) signer;
            return Bytes.wrap(signature);
        }
    }

    /** Stubs the builder to pack {@code messageCount} messages from whatever start it is given. */
    private void givenBundleOf(final int messageCount) {
        given(stateProofManager.buildBundleProof(any(), anyLong(), any(), anyBoolean(), anyBoolean()))
                .willAnswer(inv -> {
                    final long firstMessageId = inv.getArgument(1);
                    return new BundleProof(BUNDLE, messageCount, firstMessageId + messageCount - 1);
                });
    }

    private ClprBundleProducer producer(final ClprChannel channel, @Nullable final ClprBundleRequest request) {
        return new ClprBundleProducer(stateProofManager, channel, request, true);
    }

    private ClprBundleProducer producer(
            final ClprChannel channel, @Nullable final ClprBundleRequest request, final int maxBundles) {
        return new ClprBundleProducer(stateProofManager, channel, request, true, maxBundles);
    }

    private static ClprChannel channel(final long nextMessageId, final long ackedMessageId) {
        return ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .status(ClprChannelStatus.ACTIVE)
                .nextMessageId(nextMessageId)
                .ackedMessageId(ackedMessageId)
                .peerThrottles(PEER_THROTTLES)
                .build();
    }

    private static ClprBundleRequest request(final long currentReceivedMessageId) {
        return request(currentReceivedMessageId, ClprChannelStatus.ACTIVE);
    }

    private static ClprBundleRequest request(final long currentReceivedMessageId, final ClprChannelStatus status) {
        return ClprBundleRequest.newBuilder()
                .currentReceivedMessageId(currentReceivedMessageId)
                .currentStatus(status)
                .build();
    }
}

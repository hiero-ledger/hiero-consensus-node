// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonBytes.repeat;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonBytes.utf8;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonFixtures.formatHex;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonFixtures.lsuAnnouncement;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonFixtures.lsuOwnerSet;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonFixtures.rlpList;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonFixtures.rlpString;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonFixtures.verdictTypedContent;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonFixtures.versioned;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprMessage;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageReply;
import com.hedera.hapi.node.state.clpr.ClprMessageReplyStatus;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.clpr.ReadableMessageQueueStore;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonFixtures.Pb;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.hiero.base.crypto.Ed25519;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests of {@code CantonProofVerifier.verifyBundle}.
 *
 * <h2>Provenance (REAL vs CONSTRUCTED)</h2>
 * No monolithic real bundle exists: the LocalNet transaction's views are encrypted, so no real Merkle opening (and
 * no real {@code SendMessage}/{@code Channel} view) can be derived from {@code localnet-example-proof-bundle.json}
 * (see {@link CantonOpeningTest}). Every bundle here is therefore assembled by {@link Bundle}, which ports
 * {@code CantonProofBuilder.sol}, and every test is a round trip of constructed parts:
 * <ul>
 *   <li>REAL: the verdict typed content ({@code verdict-typed-content.hex}) is the real Canton verdict, including
 *       its physical synchronizer id and request id; only its 32-byte root hash is replaced (same length) by the
 *       root of the constructed tree, since the real root belongs to the encrypted transaction.
 *   <li>CONSTRUCTED: the mediator (a fresh libsodium Ed25519 key, threshold 1 of 1) signing that verdict, the
 *       packed mediator set, the anchor, the opening (root view, path {@code [[[],[]]]}), the view common data,
 *       the view participant data with its {@code Channel} contract ({@link CantonWriteTest} shapes), the DATA /
 *       REPLY payloads (PBJ-encoded {@code ClprMessagePayload}) and the endpoint manifest (PBJ-encoded).
 * </ul>
 * Reject cases mutate the assembled happy-path inputs. Not byte-identical to a Solidity run.
 */
class CantonProofVerifierBundleTest {

    private static final byte[] TYPED = verdictTypedContent();
    private static final CantonProto.Verdict VERDICT = CantonProto.parseVerdict(TYPED);
    private static final String SYNC = new String(VERDICT.synchronizerId(), StandardCharsets.UTF_8);
    private static final byte[] PACKAGE_ID = repeat((byte) 0x5a, 32);
    private static final byte[] OPERATOR = utf8("operator::1220" + "ab".repeat(32));
    private static final byte[] SERVICE_ADDRESS = CantonBytes.concat(PACKAGE_ID, OPERATOR);
    private static final String APP = "app::1220" + "cd".repeat(32);
    private static final String OTHER = "other::1220" + "ef".repeat(32);
    private static final String ENDPOINTS = "endpoints::1220" + "ee".repeat(32);
    private static final byte[] CHANNEL_ID = repeat((byte) 0x11, 32);
    private static final byte[] CHANNEL_CONTEXT = CantonBytes.concat(CHANNEL_ID, SERVICE_ADDRESS);
    private static final byte[] SUBMITTER = sha("submitter-metadata");
    private static final byte[] PARTICIPANT = sha("participant-metadata");
    private static final int GROUP = 7;
    private static final Med MEDIATOR = Med.of(9);
    private static final byte[] MEDIATOR_SET = MEDIATOR.set();
    private static final byte[] MANIFEST = ClprEndpointManifest.PROTOBUF
            .toBytes(ClprEndpointManifest.newBuilder()
                    .version(2)
                    .serviceAddress(Bytes.wrap(SERVICE_ADDRESS))
                    .build())
            .toByteArray();
    private static final CantonProofVerifier.OutboundLookup NO_LOOKUP = (c, m) -> {
        throw new AssertionError("outbound lookup must not be called");
    };

    @Nested
    class HappyPaths {
        @Test
        void bundleWithPayloadAndManifestIsAccepted() {
            final var b =
                    new Bundle().payload(data(APP, OTHER)).manifest(MANIFEST).commitManifest(MANIFEST);
            final ClprBundleContent c = verify(b);

            assertThat(c.metadata().nextMessageId()).isEqualTo(7);
            assertThat(c.metadata().sentRunningHash().toByteArray()).isEqualTo(repeat((byte) 0x22, 32));
            assertThat(c.metadata().receivedMessageId()).isEqualTo(4);
            assertThat(c.metadata().receivedRunningHash().toByteArray()).isEqualTo(repeat((byte) 0x33, 32));
            assertThat(c.metadata().status()).isEqualTo(ClprChannelStatus.ACTIVE);
            assertThat(c.metadata().endpointManifestVersion()).isEqualTo(3);
            assertThat(c.messages()).hasSize(1);
            assertThat(c.messages().getFirst().message().sender()).isEqualTo(Bytes.wrap(utf8(APP)));
            assertThat(c.newTrustAnchor().length()).isZero();
            assertThat(c.newTrustAnchorId().length()).isZero();
            assertThat(c.newEndpointManifest().version()).isEqualTo(2);
            assertThat(c.newEndpointManifest().serviceAddress()).isEqualTo(Bytes.wrap(SERVICE_ADDRESS));
        }

        @Test
        void bundleWithoutPayloadsOrManifestIsAcceptedWithNoNewAnchor() {
            final ClprBundleContent c = verify(new Bundle());
            assertThat(c.messages()).isEmpty();
            assertThat(c.hasNewEndpointManifest()).isFalse();
            assertThat(c.newTrustAnchor().length()).isZero();
            assertThat(c.hasMetadata()).isTrue();
        }

        @Test
        void v32ParticipantDataIsAccepted() {
            assertThat(verify(new Bundle().vpdVersion(32)).metadata().nextMessageId())
                    .isEqualTo(7);
        }

        @Test
        void replyToDataSentToAppIsCheckedAgainstTheOutboundLookup() {
            final var b = new Bundle()
                    .writeFirst(6)
                    .payload(reply(5, ClprMessageReplyStatus.SUCCESS, new byte[] {1}))
                    .alsoConfirmedBy(ENDPOINTS);
            final ClprBundleContent c =
                    CantonProofVerifier.verifyBundle(b.proof(), b.anchor(), CHANNEL_CONTEXT, (channelId, id) -> {
                        assertThat(channelId).isEqualTo(CHANNEL_ID);
                        assertThat(id).isEqualTo(5);
                        return dataPayload(OTHER, APP);
                    });
            assertThat(c.messages()).hasSize(1);
            assertRejects(
                    "ReplyTargetMismatch",
                    () -> CantonProofVerifier.verifyBundle(
                            b.proof(), b.anchor(), CHANNEL_CONTEXT, (cid, id) -> dataPayload(APP, OTHER)));
        }
    }

    /**
     * Only {@code Channel.WriteReplies}, whose controller is the CLPR endpoint party, writes genuine REPLYs: the
     * app's key alone can create a channel version that folds in a forged one. A bundle that carries a REPLY is
     * therefore accepted only as the messages of one write that the endpoint party also confirmed.
     */
    @Nested
    class ReplyBundles {
        @Test
        void aReplyTheEndpointPartyConfirmedIsAccepted() {
            final var b = new Bundle().writeFirst(6).payload(errorReply()).alsoConfirmedBy(ENDPOINTS);
            assertThat(verify(b).messages()).hasSize(1);
        }

        @Test
        void repliesThatAreTheWritesOwnMessagesAreAccepted() {
            final var b = new Bundle()
                    .writeFirst(5)
                    .payload(errorReply())
                    .payload(errorReply())
                    .alsoConfirmedBy(ENDPOINTS);
            assertThat(verify(b).messages()).hasSize(2);
        }

        @Test
        void aReplyThatOnlyTheAppConfirmedIsRejected() {
            final var b = new Bundle().writeFirst(6).payload(errorReply());
            assertRejects("RepliesNotConfirmedByEndpoints", () -> verify(b));
        }

        @Test
        void anotherPartyConfirmingDoesNotSatisfyTheEndpointRequirement() {
            final var b = new Bundle().writeFirst(6).payload(errorReply()).alsoConfirmedBy(OTHER);
            assertRejects("RepliesNotConfirmedByEndpoints", () -> verify(b));
        }

        @Test
        void anEndpointPartyThatIsAnInformeeButNotRequiredIsRejected() {
            // The endpoint party is named and weighs 1, but the app's weight 2 alone reaches the threshold of 2,
            // so the endpoint party is not a required confirmer.
            final var b = new Bundle()
                    .writeFirst(6)
                    .payload(errorReply())
                    .quorum(2, 0, 2)
                    .alsoInformed(ENDPOINTS, 1, 2);
            assertRejects("RepliesNotConfirmedByEndpoints", () -> verify(b));
        }

        @Test
        void repliesUnderAVersionThatWroteNothingAreRejected() {
            // writeFirstMessageId == nextMessageId: the write wrote no messages of its own.
            final var b = new Bundle().payload(errorReply()).alsoConfirmedBy(ENDPOINTS);
            assertRejects("RepliesFromAnotherWrite", () -> verify(b));
        }

        @Test
        void anEarlierReplyUnderALaterSendIsRejected() {
            // The write wrote two messages, but the bundle delivers one REPLY: it is not that write's own messages.
            final var b = new Bundle().writeFirst(5).payload(errorReply()).alsoConfirmedBy(ENDPOINTS);
            assertRejects("RepliesFromAnotherWrite", () -> verify(b));
        }

        @Test
        void aForgedReplyBeforeAGenuineAnswerIsRejected() {
            // One write's own message is the genuine REPLY; a forged one folded in earlier makes two payloads.
            final var b = new Bundle()
                    .writeFirst(6)
                    .payload(errorReply())
                    .payload(errorReply())
                    .alsoConfirmedBy(ENDPOINTS);
            assertRejects("RepliesFromAnotherWrite", () -> verify(b));
        }

        @Test
        void dataOnlyBundlesNeedNoEndpointConfirmationAndMaySpanSeveralWrites() {
            // Two DATA payloads under a write that wrote one message: accepted, and the endpoint party is absent.
            final var b = new Bundle().writeFirst(6).payload(data(APP, OTHER)).payload(data(APP, OTHER));
            assertThat(verify(b).messages()).hasSize(2);
        }

        /** An APPLICATION_ERROR without data: a genuine REPLY that needs no outbound-message lookup. */
        private static byte[] errorReply() {
            return reply(5, ClprMessageReplyStatus.APPLICATION_ERROR, new byte[0]);
        }
    }

    @Nested
    class LsuCompletionWithEmptyRotations {
        @Test
        void firstVerdictFromSuccessorCompletesTheUpgradeAndReturnsNewAnchor() {
            // The anchor still names another synchronizer; the verdict (request id above upgradeTime 0) names SYNC.
            final var b = new Bundle()
                    .anchorSync("old::1220" + "00".repeat(32) + "::35-0")
                    .successor(SYNC, 0);
            final ClprBundleContent c = verify(b);

            final CantonAnchor.Anchor next =
                    CantonAnchor.Anchor.decode(c.newTrustAnchor().toByteArray());
            assertThat(next.synchronizerId()).isEqualTo(SYNC);
            assertThat(next.successorSynchronizerId()).isEmpty();
            assertThat(next.upgradeTime()).isZero();
            assertThat(next.lsuSerial()).isZero();
            assertThat(next.lastRequestId()).isEqualTo(VERDICT.requestId());
            assertThat(next.mediatorGroup()).isEqualTo(GROUP);
            assertThat(next.app()).isEqualTo(APP);
            assertThat(c.newTrustAnchorId().toByteArray()).hasSize(8);
            assertThat(new java.math.BigInteger(1, c.newTrustAnchorId().toByteArray()).longValue())
                    .isEqualTo(VERDICT.requestId());
        }

        @Test
        void verdictNotPastUpgradeTimeIsCheckedAgainstTheCurrentSynchronizer() {
            // upgradeTime == requestId is not "above": the current synchronizer applies, and it is not the verdict's.
            final var b = new Bundle()
                    .anchorSync("old::1220" + "00".repeat(32) + "::35-0")
                    .successor(SYNC, VERDICT.requestId());
            assertRejects("SynchronizerMismatch", () -> verify(b));
        }
    }

    @Nested
    class Rejections {
        @Test
        void mediatorSetNotMatchingTheAnchorIsTrustSetMismatch() {
            final var b = new Bundle().anchorMediatorsHash(sha("some other set"));
            assertRejects("TrustSetMismatch", () -> verify(b));
        }

        @Test
        void wrongBundleFieldCountIsInvalidPayloadShape() {
            final var b = new Bundle();
            final byte[][] f = items(b.proof());
            assertRejects(
                    "InvalidPayloadShape",
                    () -> CantonProofVerifier.verifyBundle(
                            rlpList(Arrays.copyOf(f, 6)), b.anchor(), CHANNEL_CONTEXT, NO_LOOKUP));
            assertRejects(
                    "InvalidPayloadShape",
                    () -> CantonProofVerifier.verifyBundle(
                            rlpList(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[6]),
                            b.anchor(),
                            CHANNEL_CONTEXT,
                            NO_LOOKUP));
        }

        @Test
        void verdictWithWrongItemCountIsInvalidPayloadShape() {
            final var b = new Bundle();
            final byte[][] f = items(b.proof());
            f[0] = rlpList(items(f[0])[0]);
            assertRejects(
                    "InvalidPayloadShape",
                    () -> CantonProofVerifier.verifyBundle(rlpList(f), b.anchor(), CHANNEL_CONTEXT, NO_LOOKUP));
        }

        @Test
        void appNotARequiredConfirmerIsApproverMissing() {
            // The view's informees do not include the anchor's app.
            final var b = new Bundle().informee(OTHER);
            assertRejects("ApproverMissing", () -> verify(b));
        }

        @Test
        void appWithSurplusWeightIsNotARequiredConfirmer() {
            // The others alone reach the threshold without the app, so it is not required.
            final var b = new Bundle().quorum(1, 1, 1);
            assertRejects("ApproverMissing", () -> verify(b));
        }

        @Test
        void manifestCommittedButAbsentIsManifestMismatch() {
            final var b = new Bundle().commitManifest(MANIFEST);
            assertRejects("ManifestMismatch", () -> verify(b));
        }

        @Test
        void manifestPresentButNotCommittedIsManifestMismatch() {
            final var b = new Bundle().manifest(MANIFEST);
            assertRejects("ManifestMismatch", () -> verify(b));
        }

        @Test
        void manifestNotMatchingTheCommitmentIsManifestMismatch() {
            final byte[] other = ClprEndpointManifest.PROTOBUF
                    .toBytes(ClprEndpointManifest.newBuilder()
                            .version(3)
                            .serviceAddress(Bytes.wrap(SERVICE_ADDRESS))
                            .build())
                    .toByteArray();
            final var b = new Bundle().commitManifest(MANIFEST).manifest(other);
            assertRejects("ManifestMismatch", () -> verify(b));
        }

        @Test
        void manifestForAnotherServiceOrVersionZeroIsManifestMismatch() {
            final byte[] otherService = ClprEndpointManifest.PROTOBUF
                    .toBytes(ClprEndpointManifest.newBuilder()
                            .version(2)
                            .serviceAddress(Bytes.wrap(utf8("elsewhere")))
                            .build())
                    .toByteArray();
            assertRejects(
                    "ManifestMismatch",
                    () -> verify(new Bundle().commitManifest(otherService).manifest(otherService)));
            final byte[] versionZero = ClprEndpointManifest.PROTOBUF
                    .toBytes(ClprEndpointManifest.newBuilder()
                            .serviceAddress(Bytes.wrap(SERVICE_ADDRESS))
                            .build())
                    .toByteArray();
            assertRejects(
                    "ManifestMismatch",
                    () -> verify(new Bundle().commitManifest(versionZero).manifest(versionZero)));
        }

        @Test
        void malformedNonEmptyRotationsRejected() {
            // This is a one-item list, not the required two
            final var b = new Bundle().rotations(rlpList(rlpString(new byte[] {1})));
            assertRejects("InvalidPayloadShape", () -> verify(b));
        }

        @Test
        void nonEmptyRotationsAreRoutedThroughApplyRotations() {
            // A real LsuAnnouncement rotation reaches CantonTopologyVerifier.applyRotations through verifyBundle: the
            // owner set hashes to the anchor, but the announced successor is of a different logical synchronizer than
            // the anchor's, so applyRotations rejects it — proving field 4 is applied, not short-circuited.
            final byte[] ownerSet = lsuOwnerSet();
            final byte[] rotationItem =
                    rlpList(rlpString(lsuAnnouncement()), rlpString(new byte[0]), rlpString(new byte[0]));
            final byte[] rotations = rlpList(rlpString(ownerSet), rlpList(rotationItem));
            final var b = new Bundle()
                    .anchorOwnersHash(MiscCryptoUtils.keccak256DigestOf(ownerSet))
                    .rotations(rotations);
            assertRejects("InvalidLsuAnnouncement", () -> verify(b));
        }

        @Test
        void emptyRotationsAreAcceptedAsStringOrList() {
            verify(new Bundle().rotations(new byte[] {(byte) 0x80}));
            verify(new Bundle().rotations(new byte[] {(byte) 0xc0}));
        }

        @Test
        void staleVerdictIsRejected() {
            assertRejects("StaleVerdict", () -> verify(new Bundle().lastRequestId(VERDICT.requestId())));
        }

        @Test
        void dataFromAnotherSenderIsSenderMismatch() {
            assertRejects("SenderMismatch", () -> verify(new Bundle().payload(data(OTHER, APP))));
        }

        @Test
        void createdIndexOutOfRangeIsWrongContract() {
            assertRejects("WrongContract", () -> verify(new Bundle().createdIndex(new byte[] {1})));
            // A value beyond int is out of range too, not a parse failure.
            assertRejects("WrongContract", () -> verify(new Bundle().createdIndex(repeat((byte) 0xff, 32))));
        }

        @Test
        void createdIndexLongerThan32BytesIsInvalidPayloadShape() {
            assertRejects("InvalidPayloadShape", () -> verify(new Bundle().createdIndex(repeat((byte) 1, 33))));
        }

        @Test
        void openingNotMatchingTheVerdictRootIsRootHashMismatch() {
            final var good = new Bundle();
            final byte[][] f = items(good.proof());
            f[1] = new Bundle().vpdVersion(32).opening(); // another tree
            assertRejects(
                    "RootHashMismatch",
                    () -> CantonProofVerifier.verifyBundle(rlpList(f), good.anchor(), CHANNEL_CONTEXT, NO_LOOKUP));
        }

        @Test
        void channelContextForAnotherChannelIsWrongContract() {
            final var b = new Bundle();
            final byte[] other = CantonBytes.concat(repeat((byte) 0x12, 32), SERVICE_ADDRESS);
            assertRejects(
                    "WrongContract", () -> CantonProofVerifier.verifyBundle(b.proof(), b.anchor(), other, NO_LOOKUP));
        }

        @Test
        void malformedServiceAddressIsInvalidServiceAddress() {
            final var b = new Bundle();
            assertRejects(
                    "InvalidServiceAddress",
                    () -> CantonProofVerifier.verifyBundle(
                            b.proof(),
                            b.anchor(),
                            CantonBytes.concat(CHANNEL_ID, PACKAGE_ID, utf8("operator")),
                            NO_LOOKUP));
            assertRejects(
                    "InvalidServiceAddress",
                    () -> CantonProofVerifier.verifyBundle(
                            b.proof(), b.anchor(), CantonBytes.concat(CHANNEL_ID, PACKAGE_ID), NO_LOOKUP));
            assertRejects(
                    "InvalidServiceAddress",
                    () -> CantonProofVerifier.verifyBundle(b.proof(), b.anchor(), new byte[31], NO_LOOKUP));
        }

        @Test
        void decodeServiceDerivesIdsAndHex() {
            final var s = CantonProofVerifier.decodeService(CHANNEL_CONTEXT);
            assertThat(s.channelId()).isEqualTo(CHANNEL_ID);
            assertThat(s.channelIdHex()).isEqualTo(utf8("11".repeat(32)));
            assertThat(s.packageIdHex()).isEqualTo(utf8("5a".repeat(32)));
            assertThat(s.operator()).isEqualTo(OPERATOR);
            assertThat(s.serviceAddress()).isEqualTo(SERVICE_ADDRESS);
        }
    }

    @Nested
    class ClprVerifierSurface {
        @Test
        void clprVerifierVerifyBundleReturnsContent() {
            final var b = new Bundle().payload(data(APP, OTHER));
            final ClprBundleContent c = new CantonProofVerifier()
                    .verifyBundle(
                            Bytes.wrap(b.proof()),
                            Bytes.wrap(b.anchor()),
                            Bytes.wrap(CHANNEL_CONTEXT),
                            mock(HandleContext.class));
            assertThat(c.messages()).hasSize(1);
            assertThat(c.metadata().nextMessageId()).isEqualTo(7);
        }

        @Test
        void clprVerifierReadsTheOutboundMessageFromTheMessageQueue() {
            final var queue = mock(ReadableMessageQueueStore.class);
            final var storeFactory = mock(StoreFactory.class);
            final var context = mock(HandleContext.class);
            when(context.storeFactory()).thenReturn(storeFactory);
            when(storeFactory.readableStore(ReadableMessageQueueStore.class)).thenReturn(queue);
            when(queue.getMessage(any(), anyLong()))
                    .thenReturn(ClprMessageValue.newBuilder()
                            .payload(dataPayload(APP, OTHER))
                            .build());

            final var b = new Bundle()
                    .writeFirst(6)
                    .payload(reply(5, ClprMessageReplyStatus.SUCCESS, new byte[] {1}))
                    .alsoConfirmedBy(ENDPOINTS);
            // The stored DATA message went to OTHER, but the reply comes from the anchor's app.
            assertThatThrownBy(() -> new CantonProofVerifier()
                            .verifyBundle(
                                    Bytes.wrap(b.proof()),
                                    Bytes.wrap(b.anchor()),
                                    Bytes.wrap(CHANNEL_CONTEXT),
                                    context))
                    .isInstanceOf(HandleException.class)
                    .satisfies(e -> assertThat(((HandleException) e).getStatus())
                            .isEqualTo(ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED));

            when(queue.getMessage(any(), anyLong()))
                    .thenReturn(ClprMessageValue.newBuilder()
                            .payload(dataPayload(OTHER, APP))
                            .build());
            final ClprBundleContent c = new CantonProofVerifier()
                    .verifyBundle(Bytes.wrap(b.proof()), Bytes.wrap(b.anchor()), Bytes.wrap(CHANNEL_CONTEXT), context);
            assertThat(c.messages()).hasSize(1);

            // An absent stored message is nothing to bind: ClprService's reply ordering rejects it.
            when(queue.getMessage(any(), anyLong())).thenReturn(null);
            assertThat(new CantonProofVerifier()
                            .verifyBundle(
                                    Bytes.wrap(b.proof()), Bytes.wrap(b.anchor()), Bytes.wrap(CHANNEL_CONTEXT), context)
                            .messages())
                    .hasSize(1);
        }

        @Test
        void clprVerifierMapsAnyFailureToBundleVerificationFailed() {
            final var b = new Bundle().anchorMediatorsHash(sha("some other set"));
            assertThatThrownBy(() -> new CantonProofVerifier()
                            .verifyBundle(
                                    Bytes.wrap(b.proof()),
                                    Bytes.wrap(b.anchor()),
                                    Bytes.wrap(CHANNEL_CONTEXT),
                                    mock(HandleContext.class)))
                    .isInstanceOf(HandleException.class)
                    .satisfies(e -> assertThat(((HandleException) e).getStatus())
                            .isEqualTo(ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED));
            // Not even RLP: also a HandleException, not a raw runtime exception.
            assertThatThrownBy(() -> new CantonProofVerifier()
                            .verifyBundle(
                                    Bytes.wrap(new byte[] {(byte) 0xff, 1}),
                                    Bytes.wrap(b.anchor()),
                                    Bytes.wrap(CHANNEL_CONTEXT),
                                    mock(HandleContext.class)))
                    .isInstanceOf(HandleException.class);
        }

        @Test
        void clprVerifierMapsAMessageQueueThatCannotBeOpenedToBundleVerificationFailed() {
            final var storeFactory = mock(StoreFactory.class);
            when(storeFactory.readableStore(ReadableMessageQueueStore.class))
                    .thenThrow(new IllegalStateException("gone"));
            final var context = mock(HandleContext.class);
            when(context.storeFactory()).thenReturn(storeFactory);
            // Only a REPLY consults the queue.
            final var b = new Bundle()
                    .writeFirst(6)
                    .payload(reply(5, ClprMessageReplyStatus.SUCCESS, new byte[] {1}))
                    .alsoConfirmedBy(ENDPOINTS);
            assertBundleVerificationFailed(b, context);
        }

        @Test
        void clprVerifierMapsAnUnexpectedRuntimeExceptionToBundleVerificationFailed() {
            final var queue = mock(ReadableMessageQueueStore.class);
            when(queue.getMessage(any(), anyLong())).thenThrow(new IllegalStateException("boom"));
            final var storeFactory = mock(StoreFactory.class);
            when(storeFactory.readableStore(ReadableMessageQueueStore.class)).thenReturn(queue);
            final var context = mock(HandleContext.class);
            when(context.storeFactory()).thenReturn(storeFactory);
            final var b = new Bundle()
                    .writeFirst(6)
                    .payload(reply(5, ClprMessageReplyStatus.SUCCESS, new byte[] {1}))
                    .alsoConfirmedBy(ENDPOINTS);
            assertBundleVerificationFailed(b, context);
        }

        @Test
        void clprVerifierTreatsAStoredMessageWithoutPayloadAsAbsent() {
            final var queue = mock(ReadableMessageQueueStore.class);
            when(queue.getMessage(any(), anyLong()))
                    .thenReturn(ClprMessageValue.newBuilder().build());
            final var storeFactory = mock(StoreFactory.class);
            when(storeFactory.readableStore(ReadableMessageQueueStore.class)).thenReturn(queue);
            final var context = mock(HandleContext.class);
            when(context.storeFactory()).thenReturn(storeFactory);
            final var b = new Bundle()
                    .writeFirst(6)
                    .payload(reply(5, ClprMessageReplyStatus.SUCCESS, new byte[] {1}))
                    .alsoConfirmedBy(ENDPOINTS);
            assertThat(new CantonProofVerifier()
                            .verifyBundle(
                                    Bytes.wrap(b.proof()), Bytes.wrap(b.anchor()), Bytes.wrap(CHANNEL_CONTEXT), context)
                            .messages())
                    .hasSize(1);
        }
    }

    /**
     * The reference's {@code ClprProtobuf.decodeEndpointManifest} rejects a known manifest field carried under the
     * wrong wire type (its counting pass would otherwise disagree with its decode pass). The Java side parses the
     * preimage with PBJ's strict parser; these cases pin that it rejects the same inputs. Each bad field follows a
     * valid version and service address, the shape those two checks would otherwise let through.
     */
    @Nested
    class ManifestWireTypes {
        @Test
        void versionUnderALengthDelimitedWireTypeIsRejected() {
            assertRejectsManifest(new byte[] {0x0a, 0x02, 0x1a, 0x00});
        }

        @Test
        void serviceAddressUnderAVarintWireTypeIsRejected() {
            assertRejectsManifest(new byte[] {0x10, 0x01});
        }

        @Test
        void anEndpointUnderAVarintWireTypeIsRejected() {
            assertRejectsManifest(new byte[] {0x18, 0x01});
        }

        /** A manifest of a valid version and service address, with {@code badField} appended on the wire. */
        private static void assertRejectsManifest(final byte[] badField) {
            final byte[] preimage =
                    CantonBytes.concat(new Pb().varint(1, 2).build(), Pb.lenField(2, SERVICE_ADDRESS), badField);
            final var b = new Bundle().commitManifest(preimage).manifest(preimage);
            // The strict parse is what rejects it: without that, the version and service address check out.
            assertThatThrownBy(() -> verify(b))
                    .isInstanceOf(CantonProofException.class)
                    .hasMessageContaining("ManifestMismatch")
                    .hasMessageContaining("manifest does not parse");
        }
    }

    @Nested
    class PayloadFraming {
        @Test
        void payloadThatDoesNotParseIsCantonProtoMalformed() {
            // A DATA frame (field 1, length 2) whose message is cut off inside its first field.
            assertRejectsPayload("CantonProtoMalformed", new byte[] {0x0a, 0x02, 0x0a, (byte) 0xff});
        }

        @Test
        void dataMessageWithAnUnknownFieldIsCantonProtoMalformed() {
            // The strict parse rejects it, so the connector-id scan never sees a varint or fixed-width field.
            assertRejectsPayload("CantonProtoMalformed", new byte[] {0x0a, 0x02, 0x28, 0x01});
            assertRejectsPayload("CantonProtoMalformed", new byte[] {0x0a, 0x05, 0x29, 0, 0, 0, 0});
        }

        @Test
        void payloadMustBeOneLengthDelimitedFieldOneOrTwoSpanningIt() {
            // Empty, and a tag that never ends.
            assertRejectsPayload("CantonProtoMalformed", new byte[0]);
            assertRejectsPayload("CantonProtoMalformed", repeat((byte) 0x80, 12));
            // A multi-byte tag (field 16), a tag alone, and a field number above the oneof's DATA and REPLY.
            assertRejectsPayload("WrongMessageType", new byte[] {(byte) 0x82, 0x01, 0x00});
            assertRejectsPayload("WrongMessageType", new byte[] {0x0a});
            assertRejectsPayload("WrongMessageType", new byte[] {0x1a, 0x00});
            // Not wire type LEN, and field number 0.
            assertRejectsPayload("WrongMessageType", new byte[] {0x08, 0x01});
            assertRejectsPayload("WrongMessageType", new byte[] {0x02, 0x00});
            // A length that never ends, one that is negative, and one that does not span the payload.
            assertRejectsPayload("WrongMessageType", CantonBytes.concat(new byte[] {0x0a}, repeat((byte) 0x80, 12)));
            assertRejectsPayload(
                    "WrongMessageType",
                    CantonBytes.concat(new byte[] {0x0a}, repeat((byte) 0x80, 9), new byte[] {0x01}));
            assertRejectsPayload("WrongMessageType", new byte[] {0x0a, 0x05, 0x00});
        }
    }

    /** Builds a bundle and the anchor it verifies against; the root of the tree is signed by {@link #MEDIATOR}. */
    private static final class Bundle {
        private int vpdVersion = 31;
        private String anchorSync = SYNC;
        private String successor = "";
        private long upgradeTime;
        private long lastRequestId;
        private byte[] mediatorsHash = MiscCryptoUtils.keccak256DigestOf(MEDIATOR_SET);
        private byte[] ownersHash = new byte[32];
        private String informee = APP;
        private long appWeight = 1;
        private long otherWeight = 0;
        private long threshold = 1;
        private final List<byte[]> payloads = new ArrayList<>();
        private byte[] manifest = new byte[0];
        private String manifestHash = "";
        private byte[] rotations = {(byte) 0x80};
        private byte[] createdIndex = new byte[0];
        private String anchorEndpoints = ENDPOINTS;
        private long writeFirst = 7;
        private String secondConfirmer;
        private long secondWeight = 1;
        private long secondThreshold = -1;

        /** Adds {@code party} as a second informee that the quorum also requires (both weigh 1, threshold 2). */
        Bundle alsoConfirmedBy(final String party) {
            secondConfirmer = party;
            return this;
        }

        /** Adds {@code party} as a second informee of {@code weight}, with {@code thr} as the whole threshold. */
        Bundle alsoInformed(final String party, final long weight, final long thr) {
            secondConfirmer = party;
            secondWeight = weight;
            secondThreshold = thr;
            return this;
        }

        /** The written channel version's {@code writeFirstMessageId}; {@code nextMessageId} is 7. */
        Bundle writeFirst(final long v) {
            writeFirst = v;
            return this;
        }

        Bundle anchorEndpoints(final String e) {
            anchorEndpoints = e;
            return this;
        }

        Bundle vpdVersion(final int v) {
            vpdVersion = v;
            return this;
        }

        Bundle anchorSync(final String s) {
            anchorSync = s;
            return this;
        }

        Bundle successor(final String s, final long time) {
            successor = s;
            upgradeTime = time;
            return this;
        }

        Bundle lastRequestId(final long v) {
            lastRequestId = v;
            return this;
        }

        Bundle anchorMediatorsHash(final byte[] h) {
            mediatorsHash = h;
            return this;
        }

        Bundle anchorOwnersHash(final byte[] h) {
            ownersHash = h;
            return this;
        }

        Bundle informee(final String p) {
            informee = p;
            return this;
        }

        /** The quorum entries: the app at index 0 with {@code app}, a second informee with {@code other}. */
        Bundle quorum(final long app, final long other, final long thr) {
            appWeight = app;
            otherWeight = other;
            threshold = thr;
            return this;
        }

        Bundle payload(final byte[] p) {
            payloads.add(p);
            return this;
        }

        Bundle manifest(final byte[] m) {
            manifest = m;
            return this;
        }

        Bundle commitManifest(final byte[] m) {
            manifestHash = formatHex(MiscCryptoUtils.keccak256DigestOf(m));
            return this;
        }

        Bundle rotations(final byte[] r) {
            rotations = r;
            return this;
        }

        Bundle createdIndex(final byte[] big) {
            createdIndex = big;
            return this;
        }

        private byte[] vcd() {
            final Pb pb = new Pb().field(2, utf8(informee));
            if (otherWeight > 0) {
                pb.field(2, utf8(OTHER));
            }
            final Pb quorum =
                    new Pb().field(1, new Pb().varint(1, 0).varint(2, appWeight).build());
            if (otherWeight > 0) {
                quorum.field(1, new Pb().varint(1, 1).varint(2, otherWeight).build());
            }
            long thr = threshold;
            if (secondConfirmer != null) {
                pb.field(2, utf8(secondConfirmer));
                quorum.field(
                        1,
                        new Pb()
                                .varint(1, otherWeight > 0 ? 2 : 1)
                                .varint(2, secondWeight)
                                .build());
                thr = secondThreshold >= 0 ? secondThreshold : threshold + 1;
            }
            quorum.varint(2, thr);
            return pb.field(3, quorum.build()).build();
        }

        private byte[] vpd() {
            final byte[] ex = new Pb()
                    .field(2, utf8("SendMessage"))
                    .field(10, utf8("template"))
                    .build();
            final Pb pb = new Pb();
            pb.field(3, Pb.lenField(1, Chan.channel(manifestHash, writeFirst)));
            pb.field(6, Pb.lenField(2, ex));
            return pb.build();
        }

        private record Tree(byte[] opening, byte[] root) {}

        private Tree tree() {
            final byte[] vcd = vcd();
            final byte[] vpd = vpd();
            final byte[] cm = commonMetadata();
            final byte[] view = CantonMerkle.inner2(leafHash(8, vcd, 30), leafHash(9, vpd, vpdVersion));
            final byte[] root = CantonMerkle.inner(
                    new byte[][] {SUBMITTER, leafHash(6, cm, 30), PARTICIPANT, CantonMerkle.inner1(view)});
            final byte[] opening = rlpList(
                    rlpString(SUBMITTER),
                    leafRlp(cm, 30),
                    rlpString(PARTICIPANT),
                    rlpList(leafRlp(vcd, 30), leafRlp(vpd, vpdVersion)),
                    rlpList(rlpList(rlpList(), rlpList())));
            return new Tree(opening, root);
        }

        /** CommonMetadata{3: synchronizer id, 5: mediator group}; the opening's sync is the verdict's. */
        private static byte[] commonMetadata() {
            return CantonBytes.concat(
                    Pb.lenField(1, Pb.lenField(2, sha("cm-salt"))),
                    Pb.lenField(3, utf8(SYNC)),
                    Pb.lenField(4, utf8("uuid-0000")),
                    new Pb().varint(5, GROUP).build());
        }

        byte[] opening() {
            return tree().opening();
        }

        /** The real verdict with this tree's root in place of the real one, signed by the constructed mediator. */
        private byte[] signedVerdict() {
            final byte[] typed = replace(TYPED, VERDICT.rootHash(), tree().root());
            final byte[] sig = MEDIATOR.sign(CantonMerkle.verdictSigningInput(typed));
            final byte[] entry = rlpList(rlpString(MEDIATOR.fp()), rlpString(sig), new byte[] {(byte) 0x80});
            return rlpList(rlpString(typed), rlpList(entry));
        }

        byte[] proof() {
            return rlpList(
                    signedVerdict(),
                    tree().opening(),
                    rlpString(createdIndex),
                    rlpString(MEDIATOR_SET),
                    rotations,
                    rlpString(manifest),
                    rlpList(payloads.stream().map(CantonFixtures::rlpString).toArray(byte[][]::new)));
        }

        byte[] anchor() {
            return new CantonAnchor.Anchor(
                            anchorSync,
                            lastRequestId,
                            GROUP,
                            new byte[32],
                            ownersHash,
                            mediatorsHash,
                            successor,
                            upgradeTime,
                            0,
                            APP,
                            anchorEndpoints)
                    .encode();
        }
    }

    /** The {@code Channel} contract of {@link CantonWriteTest}: read fields in their positions, the rest filler. */
    private static final class Chan {
        static byte[] channel(final String manifestHash, final long writeFirst) {
            final byte[] packageHex = formatHex(PACKAGE_ID).getBytes(StandardCharsets.UTF_8);
            final List<byte[]> values = new ArrayList<>();
            for (int i = 0; i < 29; ++i) {
                values.add(text(""));
            }
            values.set(0, Pb.lenField(7, OPERATOR));
            values.set(1, Pb.lenField(7, utf8(APP)));
            values.set(2, text(utf8(formatHex(CHANNEL_ID))));
            values.set(5, new Pb().varint(3, 7L << 1).build());
            values.set(6, text(utf8("22".repeat(32))));
            values.set(10, new Pb().varint(3, 3L << 1).build());
            values.set(14, new Pb().varint(3, 4L << 1).build());
            values.set(17, Pb.lenField(15, Pb.lenField(1, utf8("Active"))));
            values.set(24, text(utf8("33".repeat(32))));
            values.set(25, text(utf8(manifestHash)));
            values.set(28, new Pb().varint(3, writeFirst << 1).build());
            final Pb record = new Pb();
            for (final byte[] v : values) {
                record.field(1, Pb.lenField(1, v));
            }
            final byte[] templateId =
                    CantonProto.encodeIdentifier(packageHex, utf8("Clpr"), utf8("Service"), utf8("Channel"));
            final byte[] fat = new Pb()
                    .field(3, templateId)
                    .field(4, Pb.lenField(13, record.build()))
                    .field(5, utf8(APP))
                    .build();
            return Pb.lenField(2, fat);
        }

        private static byte[] text(final String s) {
            return text(utf8(s));
        }

        private static byte[] text(final byte[] s) {
            return Pb.lenField(8, s);
        }
    }

    private record Med(byte[] secret, byte[] pub, byte[] fp) {
        static Med of(final int seed) {
            final byte[] s = new byte[Ed25519.SEED_BYTES];
            Arrays.fill(s, (byte) seed);
            final byte[] pub = new byte[Ed25519.PUBLIC_KEY_BYTES];
            final byte[] secret = new byte[Ed25519.SECRET_KEY_BYTES];
            Ed25519.newKeypair(pub, secret, s);
            return new Med(secret, pub, sha(pub));
        }

        byte[] sign(final byte[] message) {
            final byte[] sig = new byte[Ed25519.SIGNATURE_BYTES];
            Ed25519.sign(sig, message, secret);
            return sig;
        }

        /** Packed set layout of CantonMediators: one Ed25519 member with one current key, threshold 1. */
        byte[] set() {
            final var out = new ByteArrayOutputStream();
            out.writeBytes(new byte[] {0, 0, 0, 1});
            out.writeBytes(new byte[] {0, 0, 0, 1});
            out.writeBytes(new byte[] {0, 0, 0, 1});
            final byte[] id = new byte[32];
            id[0] = 1;
            out.writeBytes(id);
            out.writeBytes(new byte[] {0, 0, 0, 1});
            out.write(0); // delegations
            out.write(1); // current keys
            out.writeBytes(fp);
            out.write(1); // Ed25519
            out.write(0);
            out.writeBytes(pub);
            out.writeBytes(new byte[32]);
            out.write(0); // previous keys
            return out.toByteArray();
        }
    }

    private static void assertBundleVerificationFailed(final Bundle b, final HandleContext context) {
        assertThatThrownBy(() -> new CantonProofVerifier()
                        .verifyBundle(
                                Bytes.wrap(b.proof()), Bytes.wrap(b.anchor()), Bytes.wrap(CHANNEL_CONTEXT), context))
                .isInstanceOf(HandleException.class)
                .satisfies(e -> assertThat(((HandleException) e).getStatus())
                        .isEqualTo(ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED));
    }

    private static void assertRejectsPayload(final String errorName, final byte[] payload) {
        assertRejects(errorName, () -> CantonProofVerifier.checkPayload(payload, CHANNEL_ID, utf8(APP), NO_LOOKUP));
    }

    private static ClprBundleContent verify(final Bundle b) {
        return CantonProofVerifier.verifyBundle(b.proof(), b.anchor(), CHANNEL_CONTEXT, NO_LOOKUP);
    }

    private static byte[] data(final String sender, final String target) {
        return ClprMessagePayload.PROTOBUF.toBytes(dataPayload(sender, target)).toByteArray();
    }

    private static ClprMessagePayload dataPayload(final String sender, final String target) {
        return ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .sender(Bytes.wrap(utf8(sender)))
                        .targetApplication(Bytes.wrap(utf8(target)))
                        .messageData(Bytes.wrap(new byte[] {7})))
                .build();
    }

    private static byte[] reply(final long id, final ClprMessageReplyStatus status, final byte[] replyData) {
        return ClprMessagePayload.PROTOBUF
                .toBytes(ClprMessagePayload.newBuilder()
                        .messageReply(ClprMessageReply.newBuilder()
                                .messageId(id)
                                .status(status)
                                .messageReplyData(Bytes.wrap(replyData)))
                        .build())
                .toByteArray();
    }

    private static byte[] replace(final byte[] in, final byte[] needle, final byte[] with) {
        assertThat(with).hasSameSizeAs(needle);
        int at = -1;
        for (int i = 0; i + needle.length <= in.length; ++i) {
            if (Arrays.equals(in, i, i + needle.length, needle, 0, needle.length)) {
                assertThat(at).as("needle occurs once").isEqualTo(-1);
                at = i;
            }
        }
        assertThat(at).as("needle found").isNotNegative();
        final byte[] out = in.clone();
        System.arraycopy(with, 0, out, at, with.length);
        return out;
    }

    private static void assertRejects(final String errorName, final org.junit.jupiter.api.function.Executable body) {
        assertThatThrownBy(body::execute)
                .isInstanceOf(CantonProofException.class)
                .satisfies(
                        e -> assertThat(((CantonProofException) e).errorName()).isEqualTo(errorName));
    }

    private static byte[] sha(final String s) {
        return sha(utf8(s));
    }

    private static byte[] sha(final byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] leafHash(final int purpose, final byte[] data, final int version) {
        return CantonMerkle.leaf(purpose, sha(data), versioned(data, version));
    }

    private static byte[] leafRlp(final byte[] data, final int version) {
        return rlpList(rlpString(sha(data)), rlpString(versioned(data, version)));
    }

    private static byte[][] items(final byte[] rlpList) {
        final var l = CantonRlp.readList(CantonRlp.of(rlpList));
        final byte[][] out = new byte[l.size()][];
        for (int i = 0; i < out.length; ++i) {
            out[i] = Arrays.copyOfRange(
                    l.get(i).buf(), l.get(i).off(), l.get(i).off() + l.get(i).len());
        }
        return out;
    }
}

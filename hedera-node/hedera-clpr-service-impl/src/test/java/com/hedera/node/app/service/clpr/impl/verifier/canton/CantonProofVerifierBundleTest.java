// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

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
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.hiero.base.crypto.Ed25519;
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
    private static final HexFormat HEX = HexFormat.of();

    private static final byte[] TYPED = CantonFixtures.verdictTypedContent();
    private static final CantonProto.Verdict VERDICT = CantonProto.parseVerdict(TYPED);
    private static final String SYNC = new String(VERDICT.synchronizerId(), StandardCharsets.UTF_8);

    private static final byte[] PACKAGE_ID = repeat((byte) 0x5a, 32);
    private static final byte[] OPERATOR = utf8("operator::1220" + "ab".repeat(32));
    private static final byte[] SERVICE_ADDRESS = concat(PACKAGE_ID, OPERATOR);
    private static final String APP = "app::1220" + "cd".repeat(32);
    private static final String OTHER = "other::1220" + "ef".repeat(32);
    private static final byte[] CHANNEL_ID = repeat((byte) 0x11, 32);
    private static final byte[] CHANNEL_CONTEXT = concat(CHANNEL_ID, SERVICE_ADDRESS);
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

    // ── happy paths ──────────────────────────────────────────────────────────

    @Test
    void bundleWithPayloadAndManifestIsAccepted() {
        final var b = new Bundle().payload(data(APP, OTHER)).manifest(MANIFEST).commitManifest(MANIFEST);
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
        final var b = new Bundle().payload(reply(5, ClprMessageReplyStatus.SUCCESS, new byte[] {1}));
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

    // ── LSU completion with empty rotations ─────────────────────────────────

    @Test
    void firstVerdictFromSuccessorCompletesTheUpgradeAndReturnsNewAnchor() {
        // The anchor still names another synchronizer; the verdict (request id above upgradeTime 0) names SYNC.
        final var b = new Bundle()
                .anchorSync("old::1220" + "00".repeat(32) + "::35-0")
                .successor(SYNC, 0);
        final ClprBundleContent c = verify(b);

        final CantonAnchor.Anchor next = CantonAnchor.decode(c.newTrustAnchor().toByteArray());
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

    // ── rejections ───────────────────────────────────────────────────────────

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
        // Under PR B rotations are applied; a non-empty value that is not RLP [ownerSet, [rotation…]] is rejected
        // by CantonTopologyVerifier.applyRotations (here: a one-item list, not the required two).
        final var b = new Bundle().rotations(rlpList(rlpString(new byte[] {1})));
        assertRejects("InvalidPayloadShape", () -> verify(b));
    }

    @Test
    void nonEmptyRotationsAreRoutedThroughApplyRotations() {
        // A real LsuAnnouncement rotation reaches CantonTopologyVerifier.applyRotations through verifyBundle: the
        // owner set hashes to the anchor, but the announced successor is of a different logical synchronizer than
        // the anchor's, so applyRotations rejects it — proving field 4 is applied, not short-circuited.
        final byte[] ownerSet = CantonFixtures.lsuOwnerSet();
        final byte[] rotationItem =
                rlpList(rlpString(CantonFixtures.lsuAnnouncement()), rlpString(new byte[0]), rlpString(new byte[0]));
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
        final byte[] other = concat(repeat((byte) 0x12, 32), SERVICE_ADDRESS);
        assertRejects("WrongContract", () -> CantonProofVerifier.verifyBundle(b.proof(), b.anchor(), other, NO_LOOKUP));
    }

    @Test
    void malformedServiceAddressIsInvalidServiceAddress() {
        final var b = new Bundle();
        assertRejects(
                "InvalidServiceAddress",
                () -> CantonProofVerifier.verifyBundle(
                        b.proof(), b.anchor(), concat(CHANNEL_ID, PACKAGE_ID, utf8("operator")), NO_LOOKUP));
        assertRejects(
                "InvalidServiceAddress",
                () -> CantonProofVerifier.verifyBundle(
                        b.proof(), b.anchor(), concat(CHANNEL_ID, PACKAGE_ID), NO_LOOKUP));
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

    // ── the ClprVerifier surface (HandleContext) ─────────────────────────────

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

        final var b = new Bundle().payload(reply(5, ClprMessageReplyStatus.SUCCESS, new byte[] {1}));
        // The stored DATA message went to OTHER, but the reply comes from the anchor's app.
        assertThatThrownBy(() -> new CantonProofVerifier()
                        .verifyBundle(
                                Bytes.wrap(b.proof()), Bytes.wrap(b.anchor()), Bytes.wrap(CHANNEL_CONTEXT), context))
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

    // ── bundle assembly ─────────────────────

    private static ClprBundleContent verify(final Bundle b) {
        return CantonProofVerifier.verifyBundle(b.proof(), b.anchor(), CHANNEL_CONTEXT, NO_LOOKUP);
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
            manifestHash = HEX.formatHex(MiscCryptoUtils.keccak256DigestOf(m));
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
            quorum.varint(2, threshold);
            return pb.field(3, quorum.build()).build();
        }

        private byte[] vpd() {
            final byte[] ex = new Pb()
                    .field(2, utf8("SendMessage"))
                    .field(10, utf8("template"))
                    .build();
            final Pb pb = new Pb();
            pb.field(3, Pb.lenField(1, Chan.channel(manifestHash)));
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
            return concat(
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
                    rlpList(payloads.stream()
                            .map(CantonProofVerifierBundleTest::rlpString)
                            .toArray(byte[][]::new)));
        }

        byte[] anchor() {
            return CantonAnchor.encode(new CantonAnchor.Anchor(
                    anchorSync,
                    lastRequestId,
                    GROUP,
                    new byte[32],
                    ownersHash,
                    mediatorsHash,
                    successor,
                    upgradeTime,
                    0,
                    APP));
        }
    }

    /** The {@code Channel} contract of {@link CantonWriteTest}: nine read fields at their positions, rest filler. */
    private static final class Chan {
        static byte[] channel(final String manifestHash) {
            final byte[] packageHex = HEX.formatHex(PACKAGE_ID).getBytes(StandardCharsets.UTF_8);
            final List<byte[]> values = new ArrayList<>();
            for (int i = 0; i < 26; ++i) {
                values.add(text(""));
            }
            values.set(0, Pb.lenField(7, OPERATOR));
            values.set(1, Pb.lenField(7, utf8(APP)));
            values.set(2, text(utf8(HEX.formatHex(CHANNEL_ID))));
            values.set(5, new Pb().varint(3, 7L << 1).build());
            values.set(6, text(utf8("22".repeat(32))));
            values.set(10, new Pb().varint(3, 3L << 1).build());
            values.set(14, new Pb().varint(3, 4L << 1).build());
            values.set(17, Pb.lenField(15, Pb.lenField(1, utf8("Active"))));
            values.set(24, text(utf8("33".repeat(32))));
            values.set(25, text(utf8(manifestHash)));
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

    // ── payload / crypto helpers ─────────────────────────────────────────────

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

    private static byte[] utf8(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] repeat(final byte b, final int n) {
        final byte[] out = new byte[n];
        Arrays.fill(out, b);
        return out;
    }

    private static byte[] concat(final byte[]... parts) {
        final var out = new ByteArrayOutputStream();
        for (final byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static byte[] versioned(final byte[] data, final int version) {
        return concat(Pb.lenField(1, data), new Pb().varint(2, version).build());
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

    private static byte[] rlpString(final byte[] b) {
        if (b.length == 1 && (b[0] & 0xff) < 0x80) {
            return b.clone();
        }
        return concat(rlpHeader(0x80, b.length), b);
    }

    private static byte[] rlpList(final byte[]... items) {
        final byte[] payload = concat(items);
        return concat(rlpHeader(0xc0, payload.length), payload);
    }

    private static byte[] rlpHeader(final int base, final int len) {
        if (len <= 55) {
            return new byte[] {(byte) (base + len)};
        }
        int n = 4;
        while (n > 1 && (len >>> (8 * (n - 1))) == 0) {
            --n;
        }
        final byte[] lenBytes = new byte[n];
        for (int i = 0; i < n; ++i) {
            lenBytes[i] = (byte) (len >>> (8 * (n - 1 - i)));
        }
        return concat(new byte[] {(byte) (base + 55 + n)}, lenBytes);
    }

    /** Minimal protobuf wire-format builder. */
    private static final class Pb {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Pb field(final int number, final byte[] value) {
            out.writeBytes(lenField(number, value));
            return this;
        }

        Pb varint(final int number, final long value) {
            writeVarint(out, (long) number << 3);
            writeVarint(out, value);
            return this;
        }

        byte[] build() {
            return out.toByteArray();
        }

        static byte[] lenField(final long number, final byte[] value) {
            final var o = new ByteArrayOutputStream();
            writeVarint(o, (number << 3) | CantonProto.WIRE_LEN);
            writeVarint(o, value.length);
            o.writeBytes(value);
            return o.toByteArray();
        }

        private static void writeVarint(final ByteArrayOutputStream o, long v) {
            while (true) {
                final int b = (int) (v & 0x7f);
                v >>>= 7;
                if (v != 0) {
                    o.write(b | 0x80);
                } else {
                    o.write(b);
                    return;
                }
            }
        }
    }
}

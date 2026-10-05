// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.clpr.verify;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprMessage;
import com.hedera.hapi.node.state.clpr.ClprMessageKey;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.platform.state.StateItem;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.node.app.hapi.utils.blocks.NativeTssVerifier;
import com.hedera.node.app.hapi.utils.blocks.StateProofVerifier;
import com.hedera.node.app.hapi.utils.blocks.TssVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.verify.VerifyBundleCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link VerifyBundleCall}, in two flavours:
 *
 * <ul>
 *   <li>{@link CapturedFixtureReplay} — an offline replay of the verifier path against checked-in
 *       {@code .bin} fixtures captured from a real cross-ledger run.</li>
 *   <li>{@link ManifestOnlyBranch} — drives {@link VerifyBundleCall#execute} with a stubbed
 *       {@link TssVerifier} and synthetic {@link StateProof}s to cover the manifest-only recovery
 *       branch (spec §8.1.4).</li>
 *   <li>{@link BundleRange} — the bundle-scoped {@code next_message_id} derived from the proven
 *       message keys, and the rejection of message leaves that are not one contiguous run.</li>
 * </ul>
 */
class VerifyBundleCallTest {

    private static final Bytes CHANNEL_ID = Bytes.wrap(new byte[] {1, 1, 1});

    /**
     * Offline replay of the {@code VerifyBundle} verifier path against checked-in fixtures captured
     * from a real cross-ledger run. The fixtures live next to this test on the classpath:
     *
     * <ul>
     *   <li>{@code stateProof.bin} — the raw {@code bundlePayload} bytes (serialized {@code StateProof}).</li>
     *   <li>{@code trustAnchor.bin} — the 32-byte peer ledger id that signed the bundle
     *       (i.e. the {@code Channel.trust_anchor} value the verifier was invoked with).</li>
     * </ul>
     *
     * <p>The test parses the proof and runs the same structural + TSS checks {@link VerifyBundleCall}
     * performs in production. A failure here means either (a) the checked-in fixture pair is
     * internally inconsistent (e.g. proof from one ledger paired with the other ledger's id), or
     * (b) something in the verifier / TSS chain has regressed in a way that breaks previously-valid
     * proofs.
     *
     * <p>To refresh the fixtures, temporarily re-enable the {@code dumpBytesFailOpen} calls in
     * {@code VerifyBundleCall.execute(...)}, run an end-to-end flow, then copy the resulting files
     * from {@code verification-inputs/} into this test's resource package.
     */
    @Nested
    class CapturedFixtureReplay {

        private static final String PROOF_RESOURCE = "stateProof.bin";
        private static final String TRUST_ANCHOR_RESOURCE = "trustAnchor.bin";

        @Test
        @DisplayName("checked-in stateProof.bin verifies against checked-in trustAnchor.bin")
        @Disabled("stateProof.bin was captured before the block-root Merkle tree migrated from SHA-384 to"
                + " SHA-256 (see BlockImplUtils.HASH_SIZE / StateProofVerifier), so its Merkle path no"
                + " longer reconstructs to the root hash the TSS signature was made over. Needs a fresh"
                + " capture via the procedure in this nested class's javadoc.")
        void capturedProofVerifiesAgainstCapturedTrustAnchor() throws IOException, ParseException {
            final byte[] proofBytes = loadResource(PROOF_RESOURCE);
            final byte[] trustAnchor = loadResource(TRUST_ANCHOR_RESOURCE);
            final var trustAnchorBytes = Bytes.wrap(trustAnchor);

            // 1) Parse the StateProof.
            final StateProof proof =
                    StateProof.PROTOBUF.parse(Bytes.wrap(proofBytes).toReadableSequentialData());

            // 2) Structural checks (mirror VerifyBundleCall.execute lines 121-145).
            assertThat(proof.hasSignedBlockProof())
                    .as("captured proof is missing signedBlockProof")
                    .isTrue();
            final var signature = proof.signedBlockProofOrThrow().blockSignature();
            assertThat(signature.length())
                    .as("captured proof has empty blockSignature")
                    .isPositive();

            byte[] blockRootHash = null;
            for (final var path : proof.paths()) {
                if (!path.hasStateItemLeaf()) {
                    continue;
                }
                blockRootHash = StateProofVerifier.computeBlockRootHashFromPath(path);
                break;
            }
            assertThat(blockRootHash)
                    .as("captured proof has no state-item-leaf path to root a block hash from")
                    .isNotNull();

            // 3) TSS aggregate-signature check against the captured trust anchor.
            //    Equivalent to VerifyBundleCall.execute line 120:
            //        tssVerifier.verifyTss(trustAnchorBytes, signature, Bytes.wrap(blockRootHash))
            final var ok = new NativeTssVerifier().verifyTss(trustAnchorBytes, signature, Bytes.wrap(blockRootHash));

            assertThat(ok)
                    .as(
                            "TSS verification FAILED: captured stateProof.bin is not signed by the "
                                    + "ledger identified by trustAnchor.bin (0x%s). The most common cause is "
                                    + "a mismatched capture — e.g. stateProof.bin from one ledger's outbound "
                                    + "bundle paired with the other ledger's id.",
                            trustAnchorBytes.toHex())
                    .isTrue();
        }

        private byte[] loadResource(final String name) throws IOException {
            try (final InputStream in = Objects.requireNonNull(
                    getClass().getResourceAsStream(name),
                    "missing test resource: " + name + " (expected next to "
                            + VerifyBundleCallTest.class.getSimpleName() + " on the classpath)")) {
                return in.readAllBytes();
            }
        }
    }

    /**
     * Covers the manifest-only recovery branch of {@link VerifyBundleCall} (spec §8.1.4): a
     * state-proven endpoint manifest with no channel leaf.
     *
     * <p>Each test drives {@link VerifyBundleCall#execute} with a stubbed {@link TssVerifier}
     * (returns true) so a synthetic {@link StateProof} reaches the extraction logic without a real
     * TSS-signed fixture — the same approach as {@code VerifyConfigCallTest}. The single-leaf cases
     * use a {@code nextPathIndex = -1} path that the real {@link StateProofVerifier} accepts as
     * self-rooting; the multi-leaf case stubs {@link StateProofVerifier} so two independent leaves
     * both verify against one block root, isolating the {@code messages.isEmpty()} guard.
     */
    @Nested
    class ManifestOnlyBranch extends CallTestBase {

        private static final byte[] TRUST_ANCHOR = {1, 2, 3, 4};
        private static final Bytes SERVICE_ADDR = Bytes.wrap(new byte[20]);

        @Test
        @DisplayName("manifest-only bundle (no channel leaf, flag on) → SUCCESS with absent metadata + manifest")
        void manifestOnlyBundleSucceedsWhenFlagOn() {
            final var manifest = ClprEndpointManifest.newBuilder()
                    .version(2L)
                    .serviceAddress(SERVICE_ADDR)
                    .build();
            stubManifestFlag(true);

            final var result = subject(singleLeafProof(manifestLeaf(manifest))).execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);

            final var decoded = ClprVerifierAbi.VERIFY_BUNDLE_WITH_MANIFEST_RETURN.decode(
                    result.fullResult().output().toArray());
            assertThat(decoded.size()).isEqualTo(5);
            // Member 0: absent-metadata sentinel — nextMessageId == 0.
            final Tuple metaTuple = decoded.get(0);
            assertThat(ClprVerifierAbi.isMetadataAbsent(metaTuple)).isTrue();
            // Members 1-3: no messages, no trust-anchor rotation.
            assertThat((byte[][]) decoded.get(1)).isEmpty();
            assertThat((byte[]) decoded.get(2)).isEmpty();
            assertThat((byte[]) decoded.get(3)).isEmpty();
            // Member 4: the proven manifest (version 2, matching service address).
            final Tuple manifestStruct = decoded.get(4);
            assertThat(((BigInteger) manifestStruct.get(0)).longValue()).isEqualTo(2L);
            assertThat((byte[]) manifestStruct.get(1)).isEqualTo(SERVICE_ADDR.toByteArray());
        }

        @Test
        @DisplayName("manifest-only bundle with the endpoint-manifest flag off → CLPR_BUNDLE_VERIFICATION_FAILED")
        void manifestOnlyBundleRejectedWhenFlagOff() {
            final var manifest = ClprEndpointManifest.newBuilder()
                    .version(2L)
                    .serviceAddress(SERVICE_ADDR)
                    .build();
            stubManifestFlag(false);

            final var result = subject(singleLeafProof(manifestLeaf(manifest))).execute(frame);

            assertThat(result.responseCode()).isEqualTo(CLPR_BUNDLE_VERIFICATION_FAILED);
            assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
        }

        @Test
        @DisplayName("no channel leaf + a message leaf alongside the manifest → rejected (messages.isEmpty() guard)")
        void bundleWithMessageAndManifestButNoChannelRejected() {
            final var manifest = ClprEndpointManifest.newBuilder()
                    .version(2L)
                    .serviceAddress(SERVICE_ADDR)
                    .build();
            final var message = messageValue(1);
            stubManifestFlag(true);

            // Two independent leaves cannot share a self-rooting nextPathIndex=-1 root, so stub the
            // path verifier: both leaves verify against one block root and reach the branch together.
            // Without the messages.isEmpty() guard this would wrongly return manifestOnlySuccess.
            try (final var verifier = mockStatic(StateProofVerifier.class)) {
                verifier.when(() -> StateProofVerifier.computeBlockRootHashFromPath(any(), any()))
                        .thenReturn(new byte[32]);
                verifier.when(() -> StateProofVerifier.verifyPath(any(), any(), any()))
                        .thenReturn(true);

                final var result = subject(
                                multiLeafProof(manifestLeaf(manifest), keyedMessageLeaf(CHANNEL_ID, 1, message)))
                        .execute(frame);

                assertThat(result.responseCode()).isEqualTo(CLPR_BUNDLE_VERIFICATION_FAILED);
                assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
            }
        }

        // ---- helpers ----

        private VerifyBundleCall subject(@NonNull final byte[] bundlePayload) {
            return new VerifyBundleCall(mockEnhancement(), gasCalculator, bundlePayload, TRUST_ANCHOR, acceptingTss());
        }

        /** Stubs {@code configOf(frame)} to a config with the given endpoint-manifest flag value. */
        private void stubManifestFlag(final boolean enabled) {
            final Configuration config = HederaTestConfigBuilder.create()
                    .withValue("clpr.endpointManifestEnabled", enabled)
                    .getOrCreateConfig();
            given(frame.getMessageFrameStack()).willReturn(new ArrayDeque<>());
            given(frame.getContextVariable(FrameUtils.CONFIG_CONTEXT_VARIABLE)).willReturn(config);
        }
    }

    /**
     * Covers how {@link VerifyBundleCall} places the bundle in the sender's queue: {@code next_message_id} is one past
     * the last proven message key, so the receiver's positional
     * {@code bundle_first_id = next_message_id - messages.length} holds for any range start — in particular for a
     * streaming bundle shaped from the peer's {@code received_message_id + 1} rather than
     * {@code acked_message_id + 1}.
     *
     * <p>Multi-leaf proofs stub {@link StateProofVerifier} so independent synthetic leaves share one block root,
     * isolating the range logic from Merkle-path construction.
     */
    @Nested
    class BundleRange extends CallTestBase {

        private static final byte[] TRUST_ANCHOR = {1, 2, 3, 4};

        /**
         * The sender's Channel: nothing acked yet, messages 1..5 queued.
         */
        private static final ClprChannel CHANNEL = ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .ackedMessageId(0)
                .nextMessageId(6)
                .sentRunningHash(Bytes.wrap(new byte[32]))
                .receivedRunningHash(Bytes.wrap(new byte[32]))
                .status(ClprChannelStatus.ACTIVE)
                .build();

        @Test
        @DisplayName("given messages 4 and 5 with nothing acked, then nextMessageId is 6, one past the last key")
        void givenMessagesAboveAckedPlusOne_thenNextMessageIdIsOnePastTheLastKey() {
            stubManifestFlag();

            final var metadata = executeWithStubbedPaths(channelLeaf(CHANNEL), messageLeaf(4), messageLeaf(5));

            assertThat(nextMessageIdOf(metadata)).isEqualTo(6L);
            assertThat((byte[]) metadata.get(1))
                    .isEqualTo(messageValue(5).runningHashAfterProcessing().toByteArray());
        }

        @Test
        @DisplayName("given messages 1 to 3 starting at acked + 1, then nextMessageId matches the unary formula")
        void givenMessagesStartingAtAckedPlusOne_thenNextMessageIdMatchesAckedPlusOnePlusSize() {
            stubManifestFlag();

            final var metadata =
                    executeWithStubbedPaths(channelLeaf(CHANNEL), messageLeaf(1), messageLeaf(2), messageLeaf(3));

            assertThat(nextMessageIdOf(metadata)).isEqualTo(CHANNEL.ackedMessageId() + 1 + 3);
        }

        @Test
        @DisplayName("given a pure-ACK bundle whose sender's ack lags its queue, then nextMessageId is the channel's")
        void givenPureAckBundleWithLaggingAck_thenNextMessageIdIsTheChannels() {
            // The receiver already holds messages 1..5, but the sender has only seen our ack of 1 and 2. A pure-ACK
            // bundle must end at the sender's queue tip: acked + 1 would make the receiver read 3..5 as a replayed
            // prefix the bundle does not carry, and reject it.
            stubManifestFlag();
            final var channel = CHANNEL.copyBuilder().ackedMessageId(2).build();

            final var metadata = executeWithStubbedPaths(channelLeaf(channel));

            assertThat(nextMessageIdOf(metadata)).isEqualTo(channel.nextMessageId());
        }

        @Test
        @DisplayName(
                "given the captured real bundle, then it verifies and nextMessageId is one past its last message key")
        void givenCapturedRealBundle_thenNextMessageIdIsOnePastItsLastMessageKey() throws IOException, ParseException {
            final byte[] proofBytes = loadResource("stateProof.bin");
            final byte[] trustAnchor = loadResource("trustAnchor.bin");
            final var proof = StateProof.PROTOBUF.parse(Bytes.wrap(proofBytes).toReadableSequentialData());
            final var messageIds = new ArrayList<Long>();
            ClprChannel channel = null;
            for (final var path : proof.paths()) {
                if (!path.hasStateItemLeaf()) {
                    continue;
                }
                final var item =
                        StateItem.PROTOBUF.parse(path.stateItemLeafOrThrow().toReadableSequentialData());
                if (item.valueOrThrow().hasClprServiceIMessageQueue()) {
                    messageIds.add(
                            item.keyOrThrow().clprServiceIMessageQueueOrThrow().messageId());
                } else if (item.valueOrThrow().hasClprServiceIChannels()) {
                    channel = item.valueOrThrow().clprServiceIChannelsOrThrow();
                }
            }
            assertThat(channel).as("captured proof carries a channel leaf").isNotNull();
            final long expectedNextMessageId =
                    messageIds.isEmpty() ? channel.nextMessageId() : messageIds.getLast() + 1;
            stubManifestFlag();

            final var result = new VerifyBundleCall(
                            mockEnhancement(), gasCalculator, proofBytes, trustAnchor, new NativeTssVerifier())
                    .execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            final Tuple decoded = ClprVerifierAbi.VERIFY_BUNDLE_WITH_MANIFEST_RETURN.decode(
                    result.fullResult().output().toArray());
            assertThat((byte[][]) decoded.get(1)).hasNumberOfRows(messageIds.size());
            assertThat(nextMessageIdOf(decoded.get(0))).isEqualTo(expectedNextMessageId);
        }

        @Test
        @DisplayName("given message leaves out of id order, then the messages are returned sorted by their proven ids")
        void givenMessageLeavesOutOfIdOrder_thenMessagesAreReturnedSortedByProvenId() throws ParseException {
            stubManifestFlag();

            final var decoded = executeWithStubbedPathsForOutput(
                    channelLeaf(CHANNEL), messageLeaf(5), messageLeaf(3), messageLeaf(4));

            final Tuple metadata = decoded.get(0);
            assertThat(nextMessageIdOf(metadata)).isEqualTo(6L);
            // The running hash handed to the receiver is the one after the highest id, not after the last leaf.
            assertThat((byte[]) metadata.get(1))
                    .isEqualTo(messageValue(5).runningHashAfterProcessing().toByteArray());
            final var messageIds = new ArrayList<Long>();
            for (final byte[] payloadBytes : (byte[][]) decoded.get(1)) {
                final var payload = ClprMessagePayload.PROTOBUF.parse(
                        Bytes.wrap(payloadBytes).toReadableSequentialData());
                messageIds.add((long) payload.messageOrThrow().messageData().getByte(0));
            }
            assertThat(messageIds).containsExactly(3L, 4L, 5L);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("nonContiguousRuns")
        @DisplayName("given message leaves that are not one contiguous run of the channel, then the bundle is rejected")
        void givenNonContiguousMessageLeaves_thenRejected(final String description, final List<Bytes> messageLeaves) {
            stubManifestFlag();
            final var leaves = new ArrayList<Bytes>();
            leaves.add(channelLeaf(CHANNEL));
            leaves.addAll(messageLeaves);

            final var result = executeWithStubbedPathsForResult(leaves.toArray(Bytes[]::new));

            assertThat(result.responseCode()).isEqualTo(CLPR_BUNDLE_VERIFICATION_FAILED);
            assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
        }

        static Stream<Arguments> nonContiguousRuns() {
            final var otherChannelId = Bytes.wrap(new byte[] {7, 7, 7});
            return Stream.of(
                    Arguments.of("gap (4, 6)", List.of(messageLeaf(4), messageLeaf(6))),
                    Arguments.of("duplicate (4, 4)", List.of(messageLeaf(4), messageLeaf(4))),
                    Arguments.of("message id 0", List.of(messageLeaf(0), messageLeaf(1))),
                    Arguments.of("at the channel's nextMessageId (5, 6)", List.of(messageLeaf(5), messageLeaf(6))),
                    Arguments.of(
                            "key of another channel",
                            List.of(messageLeaf(4), keyedMessageLeaf(otherChannelId, 5, messageValue(5)))));
        }

        @Test
        @DisplayName("given a message leaf without a key, then the bundle is rejected")
        void givenMessageLeafWithoutKey_thenRejected() {
            stubManifestFlag();
            final var keylessMessageLeaf = leaf(
                    null,
                    StateValue.newBuilder()
                            .clprServiceIMessageQueue(messageValue(4))
                            .build());

            final var result = executeWithStubbedPathsForResult(channelLeaf(CHANNEL), keylessMessageLeaf);

            assertThat(result.responseCode()).isEqualTo(CLPR_BUNDLE_VERIFICATION_FAILED);
        }

        @Test
        @DisplayName("given a message leaf keyed as another state, then the bundle is rejected")
        void givenMessageLeafWithNonMessageKey_thenRejected() {
            stubManifestFlag();
            final var channelKey = StateKey.newBuilder()
                    .clprServiceIChannels(
                            ProtoBytes.newBuilder().value(CHANNEL_ID).build())
                    .build();
            final var misKeyedMessageLeaf = leaf(
                    channelKey,
                    StateValue.newBuilder()
                            .clprServiceIMessageQueue(messageValue(4))
                            .build());

            final var result = executeWithStubbedPathsForResult(channelLeaf(CHANNEL), misKeyedMessageLeaf);

            assertThat(result.responseCode()).isEqualTo(CLPR_BUNDLE_VERIFICATION_FAILED);
        }

        private Tuple executeWithStubbedPaths(@NonNull final Bytes... leaves) {
            return executeWithStubbedPathsForOutput(leaves).get(0);
        }

        private Tuple executeWithStubbedPathsForOutput(@NonNull final Bytes... leaves) {
            final var result = executeWithStubbedPathsForResult(leaves);
            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            return ClprVerifierAbi.VERIFY_BUNDLE_WITH_MANIFEST_RETURN.decode(
                    result.fullResult().output().toArray());
        }

        private PricedResult executeWithStubbedPathsForResult(@NonNull final Bytes... leaves) {
            try (var verifier = mockStatic(StateProofVerifier.class)) {
                verifier.when(() -> StateProofVerifier.computeBlockRootHashFromPath(any(), any()))
                        .thenReturn(new byte[32]);
                verifier.when(() -> StateProofVerifier.verifyPath(any(), any(), any()))
                        .thenReturn(true);
                return new VerifyBundleCall(
                                mockEnhancement(), gasCalculator, multiLeafProof(leaves), TRUST_ANCHOR, acceptingTss())
                        .execute(frame);
            }
        }

        /**
         * Stubs {@code configOf(frame)} with the endpoint-manifest flag on, so the manifest-aware ABI is returned.
         */
        private void stubManifestFlag() {
            final Configuration config = HederaTestConfigBuilder.create()
                    .withValue("clpr.endpointManifestEnabled", true)
                    .getOrCreateConfig();
            given(frame.getMessageFrameStack()).willReturn(new ArrayDeque<>());
            given(frame.getContextVariable(FrameUtils.CONFIG_CONTEXT_VARIABLE)).willReturn(config);
        }

        private byte[] loadResource(final String name) throws IOException {
            try (InputStream input = Objects.requireNonNull(
                    VerifyBundleCallTest.class.getResourceAsStream(name), "missing test resource: " + name)) {
                return input.readAllBytes();
            }
        }

        private static long nextMessageIdOf(@NonNull final Tuple metadata) {
            return ((BigInteger) metadata.get(0)).longValue();
        }

        private static Bytes channelLeaf(@NonNull final ClprChannel channel) {
            return leaf(
                    null, StateValue.newBuilder().clprServiceIChannels(channel).build());
        }

        private static Bytes messageLeaf(final long messageId) {
            return keyedMessageLeaf(CHANNEL_ID, messageId, messageValue(messageId));
        }
    }

    private static TssVerifier acceptingTss() {
        final var verifier = mock(TssVerifier.class);
        given(verifier.verifyTss(any(), any(), any())).willReturn(true);
        return verifier;
    }

    private static Bytes manifestLeaf(@NonNull final ClprEndpointManifest manifest) {
        return leaf(
                StateValue.newBuilder().clprServiceIEndpointManifest(manifest).build());
    }

    /**
     * A message leaf keyed, as in real state, by its {@code ClprMessageKey}.
     */
    private static Bytes keyedMessageLeaf(
            @NonNull final Bytes channelId, final long messageId, @NonNull final ClprMessageValue message) {
        final var key = StateKey.newBuilder()
                .clprServiceIMessageQueue(ClprMessageKey.newBuilder()
                        .channelId(channelId)
                        .messageId(messageId)
                        .build())
                .build();
        return leaf(
                key, StateValue.newBuilder().clprServiceIMessageQueue(message).build());
    }

    /**
     * A message value whose running hash and payload data both identify the message, so tests can tell which one was
     * returned and in what order.
     */
    private static ClprMessageValue messageValue(final long messageId) {
        final var runningHash = new byte[32];
        runningHash[31] = (byte) messageId;
        return ClprMessageValue.newBuilder()
                .payload(ClprMessagePayload.newBuilder()
                        .message(ClprMessage.newBuilder()
                                .messageData(Bytes.wrap(new byte[] {(byte) messageId}))
                                .build())
                        .build())
                .runningHashAfterProcessing(Bytes.wrap(runningHash))
                .build();
    }

    private static Bytes leaf(@NonNull final StateValue stateValue) {
        return leaf(null, stateValue);
    }

    private static Bytes leaf(@Nullable final StateKey key, @NonNull final StateValue stateValue) {
        final var item = StateItem.newBuilder().value(stateValue);
        if (key != null) {
            item.key(key);
        }
        return StateItem.PROTOBUF.toBytes(item.build());
    }

    /**
     * A single-leaf {@link StateProof} that {@code computeBlockRootHashFromPath} accepts: one
     * {@code state_item_leaf} path with {@code nextPathIndex = -1}, plus a signed block proof
     * carrying a non-empty (unchecked — the TssVerifier is stubbed) block signature.
     */
    private static byte[] singleLeafProof(@NonNull final Bytes leaf) {
        final var path =
                MerklePath.newBuilder().stateItemLeaf(leaf).nextPathIndex(-1).build();
        return proofOf(path);
    }

    /**
     * A multi-leaf {@link StateProof}, one path per leaf in order; use only with a stubbed {@link StateProofVerifier}.
     */
    private static byte[] multiLeafProof(@NonNull final Bytes... leaves) {
        final var paths = Arrays.stream(leaves)
                .map(leaf -> MerklePath.newBuilder()
                        .stateItemLeaf(leaf)
                        .nextPathIndex(-1)
                        .build())
                .toArray(MerklePath[]::new);
        return proofOf(paths);
    }

    private static byte[] proofOf(@NonNull final MerklePath... paths) {
        final var proof = StateProof.newBuilder()
                .paths(paths)
                .signedBlockProof(TssSignedBlockProof.newBuilder()
                        .blockSignature(Bytes.wrap(new byte[] {9, 9, 9, 9}))
                        .build())
                .build();
        return StateProof.PROTOBUF.toBytes(proof).toByteArray();
    }
}

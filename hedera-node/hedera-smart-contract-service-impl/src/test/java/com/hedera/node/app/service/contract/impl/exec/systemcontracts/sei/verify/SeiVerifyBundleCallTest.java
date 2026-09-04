// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.sei.verify;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.sei.SeiCometBftProofVerifier;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.util.ArrayDeque;
import java.util.List;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;

class SeiVerifyBundleCallTest extends CallTestBase {
    private static final byte[] BUNDLE_PAYLOAD = {1, 2, 3};
    private static final byte[] TRUST_ANCHOR = {4, 5, 6};
    private static final byte[] BLOCK_HASH = filled(32, 0x01);
    private static final byte[] SENT_HASH = filled(32, 0x02);
    private static final byte[] RECEIVED_HASH = filled(32, 0x03);
    private static final byte[] LAST_HASH = filled(32, 0x04);

    @Test
    void allowsStaticFrame() {
        assertThat(subject().allowsStaticFrame()).isTrue();
    }

    @Test
    void returnsVerifiedBundleContentWithoutRotation() throws ParseException {
        final var content = content(metadata(), Bytes.EMPTY, Bytes.EMPTY);
        final var verified = verified(content, null, null);

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyBundle(BUNDLE_PAYLOAD, TRUST_ANCHOR))
                    .thenReturn(verified);

            final var result = subject().execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);
            assertThat(decodedContent(result.fullResult().output().toArray())).isEqualTo(content);
        }
    }

    @Test
    void acceptsFourProofBundleWhenContentHasNoMessages() {
        final var content = content(metadata(), Bytes.EMPTY, Bytes.EMPTY);
        final var verified = verifiedBundle()
                .blockHash(BLOCK_HASH)
                .content(content)
                .metadata(provenWithoutLastMessage())
                .build();

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyBundle(BUNDLE_PAYLOAD, TRUST_ANCHOR))
                    .thenReturn(verified);

            assertThat(subject().execute(frame).responseCode()).isEqualTo(SUCCESS);
        }
    }

    @Test
    void rejectsFourProofBundleWhenContentHasMessages() {
        final var content = ClprBundleContent.newBuilder()
                .metadata(metadata())
                .messages(List.of(ClprMessagePayload.DEFAULT))
                .build();
        final var verified = verifiedBundle()
                .blockHash(BLOCK_HASH)
                .content(content)
                .metadata(provenWithoutLastMessage())
                .build();

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyBundle(BUNDLE_PAYLOAD, TRUST_ANCHOR))
                    .thenReturn(verified);

            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void injectsVerifiedRotationWhenContentOmitsIt() throws ParseException {
        final byte[] newAnchor = {9, 9, 9};
        final byte[] newAnchorId = {8, 8, 8};
        final var content = content(metadata(), Bytes.EMPTY, Bytes.EMPTY);
        final var verified = verified(content, newAnchor, newAnchorId);

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyBundle(BUNDLE_PAYLOAD, TRUST_ANCHOR))
                    .thenReturn(verified);

            final var out = decodedContent(
                    subject().execute(frame).fullResult().output().toArray());

            assertThat(out.newTrustAnchor().toByteArray()).isEqualTo(newAnchor);
            assertThat(out.newTrustAnchorId().toByteArray()).isEqualTo(newAnchorId);
        }
    }

    @Test
    void acceptsMatchingClaimedRotationAndRewritesId() throws ParseException {
        final byte[] newAnchor = {9, 9, 9};
        final byte[] newAnchorId = {8, 8, 8};
        final var content = content(metadata(), Bytes.wrap(newAnchor), Bytes.wrap(new byte[] {7}));
        final var verified = verified(content, newAnchor, newAnchorId);

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyBundle(BUNDLE_PAYLOAD, TRUST_ANCHOR))
                    .thenReturn(verified);

            final var out = decodedContent(
                    subject().execute(frame).fullResult().output().toArray());

            assertThat(out.newTrustAnchor().toByteArray()).isEqualTo(newAnchor);
            assertThat(out.newTrustAnchorId().toByteArray()).isEqualTo(newAnchorId);
        }
    }

    @Test
    void synthesizesContentForTrustUpdateOnlyBundle() throws ParseException {
        final byte[] newAnchor = {9, 9, 9};
        final byte[] newAnchorId = {8, 8, 8};
        final var verified =
                verifiedBundle().trustAnchor(newAnchor, newAnchorId).build();

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyBundle(BUNDLE_PAYLOAD, TRUST_ANCHOR))
                    .thenReturn(verified);

            final var result = subject().execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            final var out = decodedContent(result.fullResult().output().toArray());
            assertThat(out.newTrustAnchor().toByteArray()).isEqualTo(newAnchor);
            assertThat(out.newTrustAnchorId().toByteArray()).isEqualTo(newAnchorId);
            assertThat(out.messages()).isEmpty();
            assertThat(out.metadata()).isNull();
        }
    }

    @Test
    void trustUpdateOnlyReturnsV2TupleWithAbsentMetadataSentinelWhenFlagOff() {
        // Via the V2 selector a trust-anchor rotation returns the SAME 4-member tuple shape as a normal
        // bundle — no legacy (bytes) special case. Absent queue metadata is the zero-nextMessageId sentinel.
        final byte[] newAnchor = {9, 9, 9};
        final byte[] newAnchorId = {8, 8, 8};
        final var verified =
                verifiedBundle().trustAnchor(newAnchor, newAnchorId).build();
        stubManifestFlag(false);

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyBundle(BUNDLE_PAYLOAD, TRUST_ANCHOR))
                    .thenReturn(verified);

            final var result = subjectV2().execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            final var decoded = SeiVerifyBundleTranslator.VERIFY_BUNDLE_V2
                    .getOutputs()
                    .decode(result.fullResult().output().toArray());
            assertThat(decoded.size()).isEqualTo(4);
            final Tuple metaTuple = decoded.get(0);
            assertThat(((java.math.BigInteger) metaTuple.get(0)).longValue()).isZero();
            assertThat((Object[]) decoded.get(1)).isEmpty();
            assertThat((byte[]) decoded.get(2)).isEqualTo(newAnchor);
            assertThat((byte[]) decoded.get(3)).isEqualTo(newAnchorId);
        }
    }

    @Test
    void trustUpdateOnlyReturnsV3TupleWithAbsentManifestWhenFlagOn() {
        // Same trust-anchor rotation via the V2 selector with the flag on: the 5-member V3 shape, absent
        // metadata sentinel, and a version-0 (absent) manifest member — again identical to a normal bundle.
        final byte[] newAnchor = {9, 9, 9};
        final byte[] newAnchorId = {8, 8, 8};
        final var verified =
                verifiedBundle().trustAnchor(newAnchor, newAnchorId).build();
        stubManifestFlag(true);

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyBundle(BUNDLE_PAYLOAD, TRUST_ANCHOR))
                    .thenReturn(verified);

            final var result = subjectV2().execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            final var decoded = ClprVerifierAbi.VERIFY_BUNDLE_V3_RETURN.decode(
                    result.fullResult().output().toArray());
            assertThat(decoded.size()).isEqualTo(5);
            final Tuple metaTuple = decoded.get(0);
            assertThat(((java.math.BigInteger) metaTuple.get(0)).longValue()).isZero();
            assertThat((byte[]) decoded.get(2)).isEqualTo(newAnchor);
            assertThat((byte[]) decoded.get(3)).isEqualTo(newAnchorId);
            final Tuple manifestStruct = decoded.get(4);
            // version 0 = absent manifest
            assertThat(((java.math.BigInteger) manifestStruct.get(0)).longValue())
                    .isZero();
        }
    }

    @Test
    void rejectsTrustUpdateOnlyBundleWithoutAnchor() {
        final var verified = verifiedBundle().build();

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyBundle(BUNDLE_PAYLOAD, TRUST_ANCHOR))
                    .thenReturn(verified);

            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void rejectsVerifierProofException() {
        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyBundle(BUNDLE_PAYLOAD, TRUST_ANCHOR))
                    .thenThrow(ProofException.sei("bad proof"));

            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void rejectsUnexpectedVerifierException() {
        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyBundle(BUNDLE_PAYLOAD, TRUST_ANCHOR))
                    .thenThrow(new IllegalStateException("boom"));

            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void rejectsInvalidInnerBundleContentBytes() {
        final var verified = verifiedBundle()
                .blockHash(BLOCK_HASH)
                .contentBytes(new byte[] {(byte) 0xff})
                .metadata(proven())
                .build();

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyBundle(BUNDLE_PAYLOAD, TRUST_ANCHOR))
                    .thenReturn(verified);

            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void rejectsMissingMetadata() {
        final var content = ClprBundleContent.newBuilder().build();

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyBundle(BUNDLE_PAYLOAD, TRUST_ANCHOR))
                    .thenReturn(verified(content, null, null));

            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void rejectsMetadataMismatch() {
        final var mismatched = metadata().copyBuilder().nextMessageId(43).build();

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyBundle(BUNDLE_PAYLOAD, TRUST_ANCHOR))
                    .thenReturn(verified(content(mismatched, Bytes.EMPTY, Bytes.EMPTY), null, null));

            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void rejectsClaimedRotationWithoutProof() {
        final var content = content(metadata(), Bytes.wrap(new byte[] {1}), Bytes.wrap(new byte[] {2}));

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyBundle(BUNDLE_PAYLOAD, TRUST_ANCHOR))
                    .thenReturn(verified(content, null, null));

            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void rejectsClaimedRotationThatDiffersFromProof() {
        final var content = content(metadata(), Bytes.wrap(new byte[] {1}), Bytes.wrap(new byte[] {2}));

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyBundle(BUNDLE_PAYLOAD, TRUST_ANCHOR))
                    .thenReturn(verified(content, new byte[] {3}, new byte[] {4}));

            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void returnsV2HeadlongTupleOnBundleSuccess() {
        final byte[] channelContext = {7, 8, 9};
        final var content = content(metadata(), Bytes.EMPTY, Bytes.EMPTY);
        final var verified = verified(content, null, null);
        stubManifestFlag(false);

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyBundle(BUNDLE_PAYLOAD, TRUST_ANCHOR))
                    .thenReturn(verified);

            final var result = new SeiVerifyBundleCall(
                            mockEnhancement(), gasCalculator, BUNDLE_PAYLOAD, TRUST_ANCHOR, channelContext)
                    .execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            final var decoded = SeiVerifyBundleTranslator.VERIFY_BUNDLE_V2
                    .getOutputs()
                    .decode(result.fullResult().output().toArray());
            assertThat(decoded.size()).isEqualTo(4);
        }
    }

    @Test
    void returnsV3TupleWithManifestWhenFlagOn() {
        final byte[] channelContext = {7, 8, 9};
        final var content = content(metadata(), Bytes.EMPTY, Bytes.EMPTY);
        // The verifier proved a manifest advance (version 3); with the feature flag on the bundle return
        // grows to the 5-member V3 shape carrying the manifest struct (mirrors Hiero/Besu).
        final var manifest = ClprEndpointManifest.newBuilder()
                .version(3L)
                .serviceAddress(Bytes.wrap(new byte[20]))
                .build();
        final byte[] manifestBytes =
                ClprEndpointManifest.PROTOBUF.toBytes(manifest).toByteArray();
        final var verified = verifiedBundle()
                .blockHash(BLOCK_HASH)
                .content(content)
                .metadata(proven())
                .manifestBytes(manifestBytes)
                .build();
        stubManifestFlag(true);

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyBundle(BUNDLE_PAYLOAD, TRUST_ANCHOR))
                    .thenReturn(verified);

            final var result = new SeiVerifyBundleCall(
                            mockEnhancement(), gasCalculator, BUNDLE_PAYLOAD, TRUST_ANCHOR, channelContext)
                    .execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            final var decoded = ClprVerifierAbi.VERIFY_BUNDLE_V3_RETURN.decode(
                    result.fullResult().output().toArray());
            assertThat(decoded.size()).isEqualTo(5);
            final Tuple manifestStruct = decoded.get(4);
            // manifest struct: (uint64 version, bytes serviceAddress, endpoints[])
            assertThat(((java.math.BigInteger) manifestStruct.get(0)).longValue())
                    .isEqualTo(3L);
            assertThat(((byte[]) manifestStruct.get(1)).length).isEqualTo(20);
        }
    }

    @Test
    void returnsV2FourMemberWhenFlagOff() {
        final byte[] channelContext = {7, 8, 9};
        final var content = content(metadata(), Bytes.EMPTY, Bytes.EMPTY);
        // Even though the verifier proved a manifest, with the flag off the return stays the 4-member V2.
        final var manifest = ClprEndpointManifest.newBuilder()
                .version(3L)
                .serviceAddress(Bytes.wrap(new byte[20]))
                .build();
        final byte[] manifestBytes =
                ClprEndpointManifest.PROTOBUF.toBytes(manifest).toByteArray();
        final var verified = verifiedBundle()
                .blockHash(BLOCK_HASH)
                .content(content)
                .metadata(proven())
                .manifestBytes(manifestBytes)
                .build();
        stubManifestFlag(false);

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyBundle(BUNDLE_PAYLOAD, TRUST_ANCHOR))
                    .thenReturn(verified);

            final var result = new SeiVerifyBundleCall(
                            mockEnhancement(), gasCalculator, BUNDLE_PAYLOAD, TRUST_ANCHOR, channelContext)
                    .execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            final var decoded = SeiVerifyBundleTranslator.VERIFY_BUNDLE_V2
                    .getOutputs()
                    .decode(result.fullResult().output().toArray());
            assertThat(decoded.size()).isEqualTo(4);
        }
    }

    @Test
    void manifestOnlyBundleReturnsV3ManifestOnlySuccessWhenFlagOn() {
        // Manifest-only recovery bundle (spec §8.1.4): the verifier surfaced null content and null queue
        // metadata but a proven manifest. Via the V2 selector (channel context present) with the feature
        // flag on the Call emits the 5-member V3 return whose metaTuple carries the zero nextMessageId
        // sentinel (metadata absent) plus the manifest — identical to a normal V3 bundle return.
        final var manifest = ClprEndpointManifest.newBuilder()
                .version(9L)
                .serviceAddress(Bytes.wrap(new byte[20]))
                .build();
        final byte[] manifestBytes =
                ClprEndpointManifest.PROTOBUF.toBytes(manifest).toByteArray();
        final var verified = verifiedBundle()
                .blockHash(BLOCK_HASH)
                .manifestBytes(manifestBytes)
                .build();
        stubManifestFlag(true);

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyBundle(BUNDLE_PAYLOAD, TRUST_ANCHOR))
                    .thenReturn(verified);

            final var result = subjectV2().execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            final var decoded = ClprVerifierAbi.VERIFY_BUNDLE_V3_RETURN.decode(
                    result.fullResult().output().toArray());
            assertThat(decoded.size()).isEqualTo(5);
            final Tuple metaTuple = decoded.get(0);
            // metadata-absent sentinel: nextMessageId == 0
            assertThat(((java.math.BigInteger) metaTuple.get(0)).longValue()).isZero();
            // no messages, no rotation
            assertThat((Object[]) decoded.get(1)).isEmpty();
            assertThat(((byte[]) decoded.get(2)).length).isZero();
            assertThat(((byte[]) decoded.get(3)).length).isZero();
            final Tuple manifestStruct = decoded.get(4);
            assertThat(((java.math.BigInteger) manifestStruct.get(0)).longValue())
                    .isEqualTo(9L);
            assertThat(((byte[]) manifestStruct.get(1)).length).isEqualTo(20);
        }
    }

    @Test
    void manifestOnlyBundleRejectedWhenFlagOff() {
        final var manifest = ClprEndpointManifest.newBuilder()
                .version(9L)
                .serviceAddress(Bytes.wrap(new byte[20]))
                .build();
        final byte[] manifestBytes =
                ClprEndpointManifest.PROTOBUF.toBytes(manifest).toByteArray();
        final var verified = verifiedBundle()
                .blockHash(BLOCK_HASH)
                .manifestBytes(manifestBytes)
                .build();
        stubManifestFlag(false);

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyBundle(BUNDLE_PAYLOAD, TRUST_ANCHOR))
                    .thenReturn(verified);

            assertFailed(subject().execute(frame));
        }
    }

    /** Stubs {@code configOf(frame)} to a config with the given endpoint-manifest flag value. */
    private void stubManifestFlag(final boolean enabled) {
        final Configuration config = HederaTestConfigBuilder.create()
                .withValue("clpr.endpointManifestEnabled", enabled)
                .getOrCreateConfig();
        given(frame.getMessageFrameStack()).willReturn(new ArrayDeque<>());
        given(frame.getContextVariable(FrameUtils.CONFIG_CONTEXT_VARIABLE)).willReturn(config);
    }

    private SeiVerifyBundleCall subject() {
        return new SeiVerifyBundleCall(mockEnhancement(), gasCalculator, BUNDLE_PAYLOAD, TRUST_ANCHOR);
    }

    /** The 3-arg (V2 selector) form, with a channel context, so returns use the tuple ABI. */
    private SeiVerifyBundleCall subjectV2() {
        final byte[] channelContext = {7, 8, 9};
        return new SeiVerifyBundleCall(mockEnhancement(), gasCalculator, BUNDLE_PAYLOAD, TRUST_ANCHOR, channelContext);
    }

    private static SeiCometBftProofVerifier.VerifiedBundle verified(
            final ClprBundleContent content, final byte[] newTrustAnchor, final byte[] newTrustAnchorId) {
        return verifiedBundle()
                .blockHash(BLOCK_HASH)
                .content(content)
                .metadata(proven())
                .trustAnchor(newTrustAnchor, newTrustAnchorId)
                .build();
    }

    private static VerifiedBundleBuilder verifiedBundle() {
        return new VerifiedBundleBuilder();
    }

    /** Fluent builder for the 6-field {@link SeiCometBftProofVerifier.VerifiedBundle} used across these tests. */
    private static final class VerifiedBundleBuilder {
        private byte[] blockHash;
        private byte[] contentBytes;
        private SeiCometBftProofVerifier.QueueMetadata queueMetadata;
        private byte[] newTrustAnchor;
        private byte[] newTrustAnchorId;
        private byte[] newManifestBytes = new byte[0];

        VerifiedBundleBuilder blockHash(final byte[] value) {
            this.blockHash = value;
            return this;
        }

        VerifiedBundleBuilder content(final ClprBundleContent content) {
            this.contentBytes = ClprBundleContent.PROTOBUF.toBytes(content).toByteArray();
            return this;
        }

        VerifiedBundleBuilder contentBytes(final byte[] value) {
            this.contentBytes = value;
            return this;
        }

        VerifiedBundleBuilder metadata(final SeiCometBftProofVerifier.QueueMetadata value) {
            this.queueMetadata = value;
            return this;
        }

        VerifiedBundleBuilder trustAnchor(final byte[] anchor, final byte[] anchorId) {
            this.newTrustAnchor = anchor;
            this.newTrustAnchorId = anchorId;
            return this;
        }

        VerifiedBundleBuilder manifestBytes(final byte[] value) {
            this.newManifestBytes = value;
            return this;
        }

        SeiCometBftProofVerifier.VerifiedBundle build() {
            return new SeiCometBftProofVerifier.VerifiedBundle(
                    blockHash, contentBytes, queueMetadata, newTrustAnchor, newTrustAnchorId, newManifestBytes);
        }
    }

    private static SeiCometBftProofVerifier.QueueMetadata proven() {
        return new SeiCometBftProofVerifier.QueueMetadata(
                42, SENT_HASH, 17, RECEIVED_HASH, ClprChannelStatus.ACTIVE.protoOrdinal(), LAST_HASH);
    }

    private static SeiCometBftProofVerifier.QueueMetadata provenWithoutLastMessage() {
        return new SeiCometBftProofVerifier.QueueMetadata(
                42, SENT_HASH, 17, RECEIVED_HASH, ClprChannelStatus.ACTIVE.protoOrdinal(), LAST_HASH, false);
    }

    private static ClprQueueMetadata metadata() {
        return ClprQueueMetadata.newBuilder()
                .nextMessageId(42)
                .sentRunningHash(Bytes.wrap(SENT_HASH))
                .receivedMessageId(17)
                .receivedRunningHash(Bytes.wrap(RECEIVED_HASH))
                .status(ClprChannelStatus.ACTIVE)
                .trustAnchorId(Bytes.wrap(new byte[] {7}))
                .build();
    }

    private static ClprBundleContent content(
            final ClprQueueMetadata metadata, final Bytes newTrustAnchor, final Bytes newTrustAnchorId) {
        return ClprBundleContent.newBuilder()
                .metadata(metadata)
                .newTrustAnchor(newTrustAnchor)
                .newTrustAnchorId(newTrustAnchorId)
                .build();
    }

    private static ClprBundleContent decodedContent(final byte[] output) throws ParseException {
        final var tuple = SeiVerifyBundleTranslator.VERIFY_BUNDLE.getOutputs().decode(output);
        return ClprBundleContent.PROTOBUF.parse(
                Bytes.wrap((byte[]) tuple.get(0)).toReadableSequentialData());
    }

    private static void assertFailed(
            final com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult result) {
        assertThat(result.responseCode()).isEqualTo(CLPR_BUNDLE_VERIFICATION_FAILED);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
    }

    private static byte[] filled(final int length, final int value) {
        final byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}

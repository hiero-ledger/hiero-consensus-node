// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.verify;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockConstruction;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumSyncCommitteeProofVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.QueueMetadata;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.VerifiedBundle;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Arrays;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

class EthereumVerifyBundleCallTest extends CallTestBase {
    private static final String VERIFIER_NAME = "EthereumSyncCommitteeProofVerifier";
    private static final byte[] BUNDLE_PAYLOAD = {1, 2, 3};
    // The trust anchor is opaque to the call (the verifier is mocked), so any bytes will do.
    private static final byte[] TRUST_ANCHOR = {4, 5, 6};
    private static final byte[] NEXT_ANCHOR = {9, 9, 9};
    private static final byte[] NEXT_ANCHOR_ID = {7, 7, 7};
    private static final byte[] BEACON_ROOT = filled(32, 0x01);
    private static final byte[] SENT_HASH = filled(32, 0x02);
    private static final byte[] RECEIVED_HASH = filled(32, 0x03);
    private static final byte[] LAST_HASH = filled(32, 0x04);
    // Present channel context => the V2/V3 (flag-gated) return path rather than the V1 single-bytes return.
    private static final byte[] CHANNEL_CONTEXT = {0x0A, 0x0B};
    private static final byte[] SERVICE_ADDR = filled(20, 0x55);

    @Test
    void allowsStaticFrame() {
        assertThat(subject().allowsStaticFrame()).isTrue();
    }

    @Test
    void returnsVerifiedBundleContentWithoutRotation() throws ParseException {
        final var content = content(metadata(), Bytes.EMPTY, Bytes.EMPTY);

        try (final var ignored = mockVerifier(verified(content, null, null))) {
            final var result = subject().execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);
            assertThat(decodedContent(result.fullResult().output().toArray())).isEqualTo(content);
        }
    }

    @Test
    void injectsVerifiedRotationWhenContentOmitsIt() throws ParseException {
        final var content = content(metadata(), Bytes.EMPTY, Bytes.EMPTY);

        try (final var ignored = mockVerifier(verified(content, NEXT_ANCHOR, NEXT_ANCHOR_ID))) {
            final var out = decodedContent(
                    subject().execute(frame).fullResult().output().toArray());

            assertThat(out.newTrustAnchor().toByteArray()).isEqualTo(NEXT_ANCHOR);
            assertThat(out.newTrustAnchorId().toByteArray()).isEqualTo(NEXT_ANCHOR_ID);
        }
    }

    @Test
    void acceptsMatchingClaimedRotationAndRewritesId() throws ParseException {
        // Content claims a different ID — the verifier's ID must win.
        final var content = content(metadata(), Bytes.wrap(NEXT_ANCHOR), Bytes.wrap(new byte[] {3, 3, 3}));

        try (final var ignored = mockVerifier(verified(content, NEXT_ANCHOR, NEXT_ANCHOR_ID))) {
            final var out = decodedContent(
                    subject().execute(frame).fullResult().output().toArray());

            assertThat(out.newTrustAnchor().toByteArray()).isEqualTo(NEXT_ANCHOR);
            assertThat(out.newTrustAnchorId().toByteArray()).isEqualTo(NEXT_ANCHOR_ID);
        }
    }

    @Test
    void rejectsVerifierProofException() {
        try (final var ignored = mockVerifierThrowing(new ProofException(VERIFIER_NAME, "bad proof"))) {
            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void rejectsUnexpectedVerifierException() {
        try (final var ignored = mockVerifierThrowing(new IllegalStateException("boom"))) {
            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void rejectsInvalidInnerBundleContentBytes() {
        final var verified = VerifiedBundle.builder()
                .beaconBlockRoot32(BEACON_ROOT)
                .bundleContentBytes(new byte[] {(byte) 0xff})
                .queueMetadata(proven())
                .build();

        try (final var ignored = mockVerifier(verified)) {
            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void rejectsMissingMetadata() {
        final var content = ClprBundleContent.newBuilder().build();

        try (final var ignored = mockVerifier(verified(content, null, null))) {
            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void rejectsMetadataMismatch() {
        final var mismatched = metadata().copyBuilder().nextMessageId(43).build();

        try (final var ignored = mockVerifier(verified(content(mismatched, Bytes.EMPTY, Bytes.EMPTY), null, null))) {
            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void rejectsClaimedRotationWithoutProof() {
        final var content = content(metadata(), Bytes.wrap(new byte[] {1}), Bytes.wrap(new byte[] {2}));

        try (final var ignored = mockVerifier(verified(content, null, null))) {
            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void rejectsClaimedRotationThatDiffersFromProof() {
        final var content = content(metadata(), Bytes.wrap(new byte[] {1}), Bytes.wrap(new byte[] {2}));

        try (final var ignored = mockVerifier(verified(content, NEXT_ANCHOR, NEXT_ANCHOR_ID))) {
            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void v3ReturnAppendsManifestWhenFlagOn() throws ParseException {
        stubManifestFlag(true);
        final var manifest = ClprEndpointManifest.newBuilder()
                .version(5L)
                .serviceAddress(Bytes.wrap(SERVICE_ADDR))
                .build();
        final var content = content(metadata(), Bytes.EMPTY, Bytes.EMPTY);

        try (final var ignored = mockVerifier(verifiedWithManifest(content, manifest))) {
            final var out = subject(CHANNEL_CONTEXT)
                    .execute(frame)
                    .fullResult()
                    .output()
                    .toArray();

            // Flag on => 5-member V3 return; the trailing member is the manifest struct.
            final Tuple tuple = ClprVerifierAbi.VERIFY_BUNDLE_V3_RETURN.decode(out);
            assertThat(tuple.size()).isEqualTo(5);
            final Tuple manifestTuple = tuple.get(4);
            assertThat(((BigInteger) manifestTuple.get(0)).longValueExact()).isEqualTo(5L);
        }
    }

    @Test
    void v2ReturnHasFourMembersWhenFlagOff() throws ParseException {
        stubManifestFlag(false);
        final var content = content(metadata(), Bytes.EMPTY, Bytes.EMPTY);

        try (final var ignored = mockVerifier(verified(content, null, null))) {
            final var out = subject(CHANNEL_CONTEXT)
                    .execute(frame)
                    .fullResult()
                    .output()
                    .toArray();

            // Flag off => 4-member V2 return (no manifest member).
            final Tuple tuple =
                    EthereumVerifyBundleTranslator.VERIFY_BUNDLE_V2.getOutputs().decode(out);
            assertThat(tuple.size()).isEqualTo(4);
        }
    }

    @Test
    void manifestOnlyReturnsV3WhenFlagOn() {
        // Manifest-only recovery bundle (spec §8.1.4): the verifier returns empty content + a manifest.
        stubManifestFlag(true);
        final var manifest = ClprEndpointManifest.newBuilder()
                .version(9L)
                .serviceAddress(Bytes.wrap(SERVICE_ADDR))
                .build();

        // Manifest-only is V3-only: it is reachable only via the V2 selector (channel context present),
        // so exercise it through the 5-arg form to assert what the design actually guarantees.
        try (final var ignored = mockVerifier(manifestOnly(manifest))) {
            final var out = subject(CHANNEL_CONTEXT)
                    .execute(frame)
                    .fullResult()
                    .output()
                    .toArray();

            // Flag on => 5-member V3 return; metadata absent (nextMessageId == 0 sentinel); trailing manifest.
            final Tuple tuple = ClprVerifierAbi.VERIFY_BUNDLE_V3_RETURN.decode(out);
            assertThat(tuple.size()).isEqualTo(5);
            final Tuple metaTuple = tuple.get(0);
            assertThat(((BigInteger) metaTuple.get(0)).longValueExact()).isZero();
            final Tuple manifestTuple = tuple.get(4);
            assertThat(((BigInteger) manifestTuple.get(0)).longValueExact()).isEqualTo(9L);
        }
    }

    @Test
    void manifestOnlyFailsWhenFlagOff() {
        stubManifestFlag(false);
        final var manifest = ClprEndpointManifest.newBuilder()
                .version(9L)
                .serviceAddress(Bytes.wrap(SERVICE_ADDR))
                .build();

        try (final var ignored = mockVerifier(manifestOnly(manifest))) {
            assertFailed(subject(CHANNEL_CONTEXT).execute(frame));
        }
    }

    private EthereumVerifyBundleCall subject() {
        return new EthereumVerifyBundleCall(mockEnhancement(), gasCalculator, BUNDLE_PAYLOAD, TRUST_ANCHOR);
    }

    private EthereumVerifyBundleCall subject(final byte[] channelContext) {
        return new EthereumVerifyBundleCall(
                mockEnhancement(), gasCalculator, BUNDLE_PAYLOAD, TRUST_ANCHOR, channelContext);
    }

    private void stubManifestFlag(final boolean enabled) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("clpr.endpointManifestEnabled", enabled)
                .getOrCreateConfig();
        given(frame.getMessageFrameStack()).willReturn(new ArrayDeque<>());
        given(frame.getContextVariable(FrameUtils.CONFIG_CONTEXT_VARIABLE)).willReturn(config);
    }

    private static VerifiedBundle verifiedWithManifest(
            final ClprBundleContent content, final ClprEndpointManifest manifest) {
        return VerifiedBundle.builder()
                .beaconBlockRoot32(BEACON_ROOT)
                .bundleContentBytes(ClprBundleContent.PROTOBUF.toBytes(content).toByteArray())
                .queueMetadata(proven())
                .newEndpointManifest(manifest)
                .build();
    }

    private static VerifiedBundle manifestOnly(final ClprEndpointManifest manifest) {
        // Empty content + a proven manifest is the manifest-only recovery signal the Call detects.
        return VerifiedBundle.builder()
                .beaconBlockRoot32(BEACON_ROOT)
                .bundleContentBytes(new byte[0])
                .queueMetadata(new QueueMetadata(0, filled(32, 0x00), 0, filled(32, 0x00), 0, filled(32, 0x00)))
                .newEndpointManifest(manifest)
                .build();
    }

    private static MockedConstruction<EthereumSyncCommitteeProofVerifier> mockVerifier(final VerifiedBundle verified) {
        return mockConstruction(
                EthereumSyncCommitteeProofVerifier.class,
                (mock, ctx) -> given(mock.verifyBundle(any(), any())).willReturn(verified));
    }

    private static MockedConstruction<EthereumSyncCommitteeProofVerifier> mockVerifierThrowing(
            final RuntimeException toThrow) {
        return mockConstruction(
                EthereumSyncCommitteeProofVerifier.class,
                (mock, ctx) -> given(mock.verifyBundle(any(), any())).willThrow(toThrow));
    }

    private static VerifiedBundle verified(
            final ClprBundleContent content, final byte[] nextTrustAnchor, final byte[] nextTrustAnchorId) {
        return VerifiedBundle.builder()
                .beaconBlockRoot32(BEACON_ROOT)
                .bundleContentBytes(ClprBundleContent.PROTOBUF.toBytes(content).toByteArray())
                .queueMetadata(proven())
                .nextTrustAnchor(nextTrustAnchor)
                .nextTrustAnchorId(nextTrustAnchorId)
                .build();
    }

    private static QueueMetadata proven() {
        return new QueueMetadata(42, SENT_HASH, 17, RECEIVED_HASH, ClprChannelStatus.ACTIVE.protoOrdinal(), LAST_HASH);
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
        final var tuple =
                EthereumVerifyBundleTranslator.VERIFY_BUNDLE.getOutputs().decode(output);
        return ClprBundleContent.PROTOBUF.parse(
                Bytes.wrap((byte[]) tuple.get(0)).toReadableSequentialData());
    }

    private static void assertFailed(final PricedResult result) {
        assertThat(result.responseCode()).isEqualTo(CLPR_BUNDLE_VERIFICATION_FAILED);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
    }

    private static byte[] filled(final int length, final int value) {
        final byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}

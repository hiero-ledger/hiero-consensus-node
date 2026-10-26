// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft.verify;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockConstruction;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.node.app.service.clpr.impl.verifier.BesuQbftVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi;
import com.hedera.node.app.service.clpr.impl.verifier.Rlp;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

class BesuQBFTVerifyBundleCallTest extends CallTestBase {

    // A realistic multi-node trust anchor: RLP([encodedValidatorSet, serviceAddr20, codeHash32])
    // encodedValidatorSet = BesuQbftVerifier.encodeValidatorSet([addr20])
    private static final byte[] VALIDATOR_ADDR = new byte[20];
    private static final byte[] NEW_VALIDATOR_ADDR = new byte[20];
    private static final byte[] SERVICE_ADDR = new byte[20];
    private static final byte[] CODE_HASH = new byte[32];
    private static final byte[] CHANNEL_ID = new byte[32];

    static {
        VALIDATOR_ADDR[0] = 0x11;
        NEW_VALIDATOR_ADDR[0] = 0x12;
        SERVICE_ADDR[0] = 0x22;
        CODE_HASH[0] = 0x33;
        CHANNEL_ID[0] = 0x44;
    }

    private static byte[] buildTrustAnchorRlp() {
        return fullTrustAnchor(VALIDATOR_ADDR);
    }

    private static byte[] fullTrustAnchor(final byte[] validator) {
        byte[] encodedValidatorSet = BesuQbftVerifier.encodeValidatorSet(List.of(validator));
        return Rlp.encodeList(List.of(
                Rlp.encodeBytes(encodedValidatorSet), Rlp.encodeBytes(SERVICE_ADDR), Rlp.encodeBytes(CODE_HASH)));
    }

    /** {@code channelId(32) || serviceAddress(20)}, as returned by {@code verifyConfig}. */
    private static byte[] channelContext(final byte[] channelId, final byte[] serviceAddress) {
        return Bytes.wrap(channelId).append(Bytes.wrap(serviceAddress)).toByteArray();
    }

    @Test
    void returnsProvenManifestVersionIgnoringRelayedContent() {
        // The relayed content claims an inflated endpoint-manifest version (999); the proven queue metadata says 7.
        // The returned metaTuple must carry the PROVEN value — a relay cannot inflate it to make the peer look
        // up-to-date and suppress our manifest re-pushes (spec §4.5, liveness).
        final var relayed = relayedMetadata(filled(32, 0x02))
                .copyBuilder()
                .endpointManifestVersion(999L)
                .build();

        final var result = executeWith(verifiedBundle(contentBytes(relayed), new byte[0]));

        assertThat(result.responseCode()).isEqualTo(SUCCESS);
        final Tuple metaTuple = ClprVerifierAbi.VERIFY_BUNDLE_RETURN
                .decode(result.fullResult().output().toArray())
                .get(0);
        assertThat(((BigInteger) metaTuple.get(0)).longValueExact()).isEqualTo(42L);
        assertThat(((BigInteger) metaTuple.get(5)).longValueExact()).isEqualTo(7L); // from PROVEN metadata
    }

    @Test
    void acceptsRelayedMetadataThatMatchesTheProvenState() {
        final var result = executeWith(verifiedBundle(contentBytes(relayedMetadata(filled(32, 0x02))), new byte[0]));

        assertThat(result.responseCode()).isEqualTo(SUCCESS);
        final Tuple metaTuple = ClprVerifierAbi.VERIFY_BUNDLE_RETURN
                .decode(result.fullResult().output().toArray())
                .get(0);
        assertThat(((BigInteger) metaTuple.get(0)).longValueExact()).isEqualTo(42L);
        assertThat(((BigInteger) metaTuple.get(2)).longValueExact()).isEqualTo(17L);
    }

    @Test
    void rejectsRelayedMetadataThatDiffersFromTheProvenState() {
        final ClprQueueMetadata honest = relayedMetadata(filled(32, 0x02));
        final List<UnaryOperator<ClprQueueMetadata>> tamperings = List.of(
                m -> m.copyBuilder().nextMessageId(43).build(),
                m -> m.copyBuilder().receivedMessageId(18).build(),
                m -> m.copyBuilder().status(ClprChannelStatus.CLOSING).build(),
                m -> m.copyBuilder()
                        .sentRunningHash(Bytes.wrap(filled(32, 0x09)))
                        .build(),
                m -> m.copyBuilder()
                        .receivedRunningHash(Bytes.wrap(filled(32, 0x09)))
                        .build());

        for (final var tamper : tamperings) {
            final var result = executeWith(verifiedBundle(contentBytes(tamper.apply(honest)), new byte[0]));
            assertThat(result.responseCode()).isEqualTo(CLPR_BUNDLE_VERIFICATION_FAILED);
        }
    }

    @Test
    void rejectsContentWithoutMetadata() {
        // Non-empty content (it carries another field) without the metadata it must be checked against.
        final byte[] content = contentBytes(ClprBundleContent.newBuilder()
                .newTrustAnchorId(Bytes.wrap(new byte[] {1}))
                .build());

        final var result = executeWith(verifiedBundle(content, new byte[0]));

        assertThat(result.responseCode()).isEqualTo(CLPR_BUNDLE_VERIFICATION_FAILED);
    }

    @Test
    void rejectsRelayClaimedTrustAnchorWithoutProvenRotation() {
        final var content = ClprBundleContent.newBuilder()
                .metadata(relayedMetadata(filled(32, 0x02)))
                .newTrustAnchor(Bytes.wrap(fullTrustAnchor(NEW_VALIDATOR_ADDR)))
                .newTrustAnchorId(Bytes.wrap(new byte[] {9}))
                .build();

        final var result = executeWith(verifiedBundle(contentBytes(content), new byte[0]));

        assertThat(result.responseCode()).isEqualTo(CLPR_BUNDLE_VERIFICATION_FAILED);
    }

    @Test
    void rejectsRelayClaimedTrustAnchorIdWithoutProvenRotation() {
        final var content = ClprBundleContent.newBuilder()
                .metadata(relayedMetadata(filled(32, 0x02)))
                .newTrustAnchorId(Bytes.wrap(new byte[] {9}))
                .build();

        final var result = executeWith(verifiedBundle(contentBytes(content), new byte[0]));

        assertThat(result.responseCode()).isEqualTo(CLPR_BUNDLE_VERIFICATION_FAILED);
    }

    @Test
    void returnsProvenTrustAnchorRotation() {
        final byte[] provenSet = BesuQbftVerifier.encodeValidatorSet(List.of(NEW_VALIDATOR_ADDR));
        final var verified =
                verifiedBundle(contentBytes(relayedMetadata(filled(32, 0x02))), new byte[0], provenSet, new byte[] {1});

        final var result = executeWith(verified);

        assertThat(result.responseCode()).isEqualTo(SUCCESS);
        final var decoded = ClprVerifierAbi.VERIFY_BUNDLE_RETURN.decode(
                result.fullResult().output().toArray());
        // The proven validator set, with the service address and code hash carried over from the current anchor.
        assertThat((byte[]) decoded.get(2)).isEqualTo(fullTrustAnchor(NEW_VALIDATOR_ADDR));
        assertThat((byte[]) decoded.get(3)).containsExactly(1);
    }

    @Test
    void acceptsRelayClaimedTrustAnchorEqualToTheProvenRotation() {
        final byte[] provenSet = BesuQbftVerifier.encodeValidatorSet(List.of(NEW_VALIDATOR_ADDR));
        final var content = ClprBundleContent.newBuilder()
                .metadata(relayedMetadata(filled(32, 0x02)))
                .newTrustAnchor(Bytes.wrap(fullTrustAnchor(NEW_VALIDATOR_ADDR)))
                .build();

        final var result = executeWith(verifiedBundle(contentBytes(content), new byte[0], provenSet, new byte[] {1}));

        assertThat(result.responseCode()).isEqualTo(SUCCESS);
    }

    @Test
    void rejectsRelayClaimedTrustAnchorThatDiffersFromTheProvenRotation() {
        final byte[] provenSet = BesuQbftVerifier.encodeValidatorSet(List.of(NEW_VALIDATOR_ADDR));
        final var content = ClprBundleContent.newBuilder()
                .metadata(relayedMetadata(filled(32, 0x02)))
                .newTrustAnchor(Bytes.wrap(fullTrustAnchor(VALIDATOR_ADDR)))
                .build();

        final var result = executeWith(verifiedBundle(contentBytes(content), new byte[0], provenSet, new byte[] {1}));

        assertThat(result.responseCode()).isEqualTo(CLPR_BUNDLE_VERIFICATION_FAILED);
    }

    @Test
    void rejectsChannelContextOfWrongLength() {
        final var verified = verifiedBundle(contentBytes(relayedMetadata(filled(32, 0x02))), new byte[0]);

        for (final byte[] badContext : List.of(new byte[0], CHANNEL_ID, new byte[53])) {
            assertThat(executeWith(verified, badContext).responseCode()).isEqualTo(CLPR_BUNDLE_VERIFICATION_FAILED);
        }
    }

    @Test
    void rejectsChannelContextForAnotherServiceAddress() {
        final byte[] otherService = SERVICE_ADDR.clone();
        otherService[19] = 0x01;
        final var verified = verifiedBundle(contentBytes(relayedMetadata(filled(32, 0x02))), new byte[0]);

        final var result = executeWith(verified, channelContext(CHANNEL_ID, otherService));

        assertThat(result.responseCode()).isEqualTo(CLPR_BUNDLE_VERIFICATION_FAILED);
    }

    @Test
    void returnsProvenEndpointManifestStruct() {
        final var result =
                executeWith(verifiedBundle(contentBytes(relayedMetadata(filled(32, 0x02))), manifestBytes(3L)));

        assertThat(result.responseCode()).isEqualTo(SUCCESS);
        final Tuple manifestStruct = ClprVerifierAbi.VERIFY_BUNDLE_RETURN
                .decode(result.fullResult().output().toArray())
                .get(4);
        assertThat(((BigInteger) manifestStruct.get(0)).longValueExact()).isEqualTo(3L);
    }

    @Test
    void rejectsMalformedEndpointManifestBytes() {
        final byte[] malformedManifest =
                Bytes.wrap(manifestBytes(3L)).append(Bytes.fromHex("c03e01")).toByteArray();

        final var result =
                executeWith(verifiedBundle(contentBytes(relayedMetadata(filled(32, 0x02))), malformedManifest));

        assertThat(result.responseCode()).isEqualTo(CLPR_BUNDLE_VERIFICATION_FAILED);
    }

    @Test
    void rejectsQueueMetadataThatIsNotAbiEncodable() {
        // A 5-byte sentRunningHash cannot be ABI-encoded as bytes32, so the call must fail instead of throwing.
        final var result = executeWith(verifiedBundle(contentBytes(relayedMetadata(filled(5, 0x02))), new byte[0]));

        assertThat(result.responseCode()).isEqualTo(CLPR_BUNDLE_VERIFICATION_FAILED);
    }

    @Test
    void manifestOnlyRecoveryBundleReturnsAbsentMetadataWithManifest() {
        final var result = executeWith(verifiedBundle(new byte[0], manifestBytes(4L)));

        assertThat(result.responseCode()).isEqualTo(SUCCESS);
        final var decoded = ClprVerifierAbi.VERIFY_BUNDLE_RETURN.decode(
                result.fullResult().output().toArray());
        final Tuple metaTuple = decoded.get(0);
        final Tuple manifestStruct = decoded.get(4);
        assertThat(((BigInteger) metaTuple.get(0)).longValueExact()).isZero();
        assertThat(((BigInteger) manifestStruct.get(0)).longValueExact()).isEqualTo(4L);
    }

    @Test
    void rejectsEmptyBundleWithoutEndpointManifest() {
        final var result = executeWith(verifiedBundle(new byte[0], new byte[0]));

        assertThat(result.responseCode()).isEqualTo(CLPR_BUNDLE_VERIFICATION_FAILED);
    }

    private Call.PricedResult executeWith(final BesuQbftVerifier.VerifiedBundle verified) {
        return executeWith(verified, channelContext(CHANNEL_ID, SERVICE_ADDR));
    }

    /**
     * Runs the call with the verifier mocked to return {@code verified} only when it is asked to verify
     * {@link #CHANNEL_ID}; any other channel id makes the mock return {@code null}, which fails the call.
     */
    private Call.PricedResult executeWith(final BesuQbftVerifier.VerifiedBundle verified, final byte[] channelContext) {
        try (final MockedConstruction<BesuQbftVerifier> ignored = mockConstruction(
                BesuQbftVerifier.class, (mock, ctx) -> given(mock.verifyBundle(any(), any(), aryEq(CHANNEL_ID)))
                        .willReturn(verified))) {
            return new BesuQBFTVerifyBundleCall(
                            mockEnhancement(),
                            gasCalculator,
                            new byte[] {1, 2, 3},
                            buildTrustAnchorRlp(),
                            channelContext)
                    .execute(frame);
        }
    }

    private static BesuQbftVerifier.VerifiedBundle verifiedBundle(
            final byte[] contentBytes, final byte[] newEndpointManifestBytes) {
        return verifiedBundle(contentBytes, newEndpointManifestBytes, new byte[0], new byte[0]);
    }

    private static BesuQbftVerifier.VerifiedBundle verifiedBundle(
            final byte[] contentBytes,
            final byte[] newEndpointManifestBytes,
            final byte[] newTrustAnchor,
            final byte[] newTrustAnchorId) {
        final var proven = new BesuQbftVerifier.QueueMetadata(
                42,
                filled(32, 0x02),
                17,
                filled(32, 0x03),
                ClprChannelStatus.ACTIVE.protoOrdinal(),
                filled(32, 0x04),
                7L);
        return new BesuQbftVerifier.VerifiedBundle(
                filled(32, 0x01), contentBytes, proven, newTrustAnchor, newTrustAnchorId, newEndpointManifestBytes);
    }

    private static ClprQueueMetadata relayedMetadata(final byte[] sentRunningHash) {
        return ClprQueueMetadata.newBuilder()
                .nextMessageId(42)
                .sentRunningHash(Bytes.wrap(sentRunningHash))
                .receivedMessageId(17)
                .receivedRunningHash(Bytes.wrap(filled(32, 0x03)))
                .status(ClprChannelStatus.ACTIVE)
                .build();
    }

    private static byte[] contentBytes(final ClprQueueMetadata metadata) {
        return contentBytes(ClprBundleContent.newBuilder().metadata(metadata).build());
    }

    private static byte[] contentBytes(final ClprBundleContent content) {
        return ClprBundleContent.PROTOBUF.toBytes(content).toByteArray();
    }

    private static byte[] manifestBytes(final long version) {
        return ClprEndpointManifest.PROTOBUF
                .toBytes(ClprEndpointManifest.newBuilder()
                        .version(version)
                        .serviceAddress(Bytes.wrap(new byte[20]))
                        .build())
                .toByteArray();
    }

    private static byte[] filled(final int length, final int value) {
        final byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.SiblingNode;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.clpr.ClprGetEndpointManifestQuery;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.hapi.utils.blocks.NativeTssVerifier;
import com.hedera.node.app.hapi.utils.blocks.StateProofVerifier;
import com.hedera.node.app.hapi.utils.blocks.TssVerifier;
import com.hedera.node.app.service.clpr.ReadableEndpointManifestStore;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager.ManifestWithProof;
import com.hedera.node.app.service.clpr.impl.handlers.ClprGetEndpointManifestHandler;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Arrays;
import java.util.List;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprGetEndpointManifestHandlerTest {

    @Mock(strictness = LENIENT)
    private QueryContext queryContext;

    @Mock(strictness = LENIENT)
    private ReadableEndpointManifestStore manifestStore;

    @Mock(strictness = LENIENT)
    private ClprStateProofManager stateProofManager;

    private ClprGetEndpointManifestHandler subject;

    @BeforeEach
    void setUp() {
        subject = new ClprGetEndpointManifestHandler(
                stateProofManager, new NativeTssVerifier(), HederaTestConfigBuilder.createConfigProvider());
    }

    @Test
    @DisplayName("should extract header from query")
    void extractsHeader() {
        final var header =
                QueryHeader.newBuilder().responseType(ResponseType.ANSWER_ONLY).build();
        final var query = Query.newBuilder()
                .clprGetEndpointManifest(
                        ClprGetEndpointManifestQuery.newBuilder().header(header).build())
                .build();

        assertThat(subject.extractHeader(query)).isEqualTo(header);
    }

    @Test
    @DisplayName("should create empty response with header")
    void createsEmptyResponse() {
        final var header = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.BUSY)
                .build();
        final var response = subject.createEmptyResponse(header);

        assertThat(response.clprGetEndpointManifest()).isNotNull();
        assertThat(response.clprGetEndpointManifest().header()).isEqualTo(header);
        assertThat(response.clprGetEndpointManifest().manifest()).isNull();
    }

    @Test
    @DisplayName("should not require node payment (free query)")
    void doesNotRequireNodePayment() {
        assertThat(subject.requiresNodePayment(ResponseType.ANSWER_ONLY)).isFalse();
        assertThat(subject.requiresNodePayment(ResponseType.COST_ANSWER)).isFalse();
    }

    @Test
    @DisplayName("should reject when CLPR is disabled")
    void rejectsWhenClprDisabled() {
        given(queryContext.configuration())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("clpr.enabled", false)
                        .getOrCreateConfig());

        assertThatThrownBy(() -> subject.validate(queryContext))
                .isInstanceOf(com.hedera.node.app.spi.workflows.PreCheckException.class)
                .has(responseCode(CLPR_NOT_ENABLED));
    }

    @Test
    @DisplayName("returns the proven manifest+proof pair from the same snapshot when precheck OK")
    void returnsProvenManifestWhenOk() {
        // Value comes from buildManifestStateProofWithValue (proven snapshot), not a live store read.
        final var manifest = ClprEndpointManifest.newBuilder().version(7L).build();
        given(stateProofManager.buildManifestStateProofWithValue())
                .willReturn(new ClprStateProofManager.ManifestWithProof(Bytes.EMPTY, manifest));
        given(stateProofManager.latestLedgerId()).willReturn(Bytes.EMPTY);

        final var header =
                ResponseHeader.newBuilder().nodeTransactionPrecheckCode(OK).build();
        final var response = subject.findResponse(queryContext, header);

        final var returned = response.clprGetEndpointManifest();
        assertThat(returned).isNotNull();
        assertThat(returned.manifest()).isEqualTo(manifest);
        assertThat(returned.manifestStateProof()).isEqualTo(Bytes.EMPTY);
    }

    @Test
    @DisplayName("falls back to the live manifest with an empty proof before any signed snapshot")
    void fallsBackToLiveManifestWhenNoSnapshot() {
        final var manifest = ClprEndpointManifest.newBuilder().version(3L).build();
        given(stateProofManager.buildManifestStateProofWithValue()).willReturn(null);
        given(queryContext.createStore(ReadableEndpointManifestStore.class)).willReturn(manifestStore);
        given(manifestStore.get()).willReturn(manifest);

        final var header =
                ResponseHeader.newBuilder().nodeTransactionPrecheckCode(OK).build();
        final var response = subject.findResponse(queryContext, header);

        final var returned = response.clprGetEndpointManifest();
        assertThat(returned).isNotNull();
        assertThat(returned.manifest()).isEqualTo(manifest);
        assertThat(returned.manifestStateProof()).isEqualTo(Bytes.EMPTY);
    }

    @Test
    @DisplayName("should not populate manifest when precheck code is not OK")
    void doesNotReturnManifestWhenPrecheckFailed() {
        final var header = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.BUSY)
                .build();
        final var response = subject.findResponse(queryContext, header);

        assertThat(response.clprGetEndpointManifest()).isNotNull();
        assertThat(response.clprGetEndpointManifest().manifest()).isNull();
    }

    @Test
    @DisplayName("verifies a SHA-384 proof even when the configured digest is SHA-256 (digest inferred from proof)")
    void verifiesSha384ProofWhenConfiguredDigestIsSha256() {
        assertProofVerifiedUnderMismatchedConfig(DigestType.SHA_384, "SHA_256");
    }

    @Test
    @DisplayName("verifies a SHA-256 proof even when the configured digest is SHA-384 (digest inferred from proof)")
    void verifiesSha256ProofWhenConfiguredDigestIsSha384() {
        assertProofVerifiedUnderMismatchedConfig(DigestType.SHA_256, "SHA_384");
    }

    @Test
    @DisplayName("falls back to the configured SHA-256 digest when the proof carries no hash-length signal")
    void fallsBackToConfiguredSha256WhenProofHasNoHashLengthSignal() {
        assertProofVerified(proofWithoutHashLengthSignal(), DigestType.SHA_256, "SHA_256");
    }

    @Test
    @DisplayName("falls back to the configured SHA-384 digest when the proof carries no hash-length signal")
    void fallsBackToConfiguredSha384WhenProofHasNoHashLengthSignal() {
        assertProofVerified(proofWithoutHashLengthSignal(), DigestType.SHA_384, "SHA_384");
    }

    private void assertProofVerifiedUnderMismatchedConfig(
            final DigestType proofDigest, final String configuredDigestName) {
        assertProofVerified(proofWithHashLength(proofDigest.digestLength()), proofDigest, configuredDigestName);
    }

    /**
     * Configures the node with {@code configuredDigestName} and asserts the sender-side self-check still returns
     * the manifest proof bytes. A digest-sensitive {@link TssVerifier} accepts only the block root computed with
     * {@code expectedDigest}, so the proof survives the check only when the handler hashes with that digest.
     */
    private void assertProofVerified(
            final StateProof proof, final DigestType expectedDigest, final String configuredDigestName) {
        final var proofBytes = StateProof.PROTOBUF.toBytes(proof);
        final byte[] expectedRoot = StateProofVerifier.computeBlockRootHash(proof, expectedDigest.buildDigest());
        final var ledgerId = Bytes.wrap(new byte[] {7, 7, 7, 7});

        final TssVerifier digestSensitiveTss = (lid, sig, root) -> Arrays.equals(root.toByteArray(), expectedRoot);
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.digestType", configuredDigestName)
                .getOrCreateConfig();
        final ConfigProvider provider = () -> new VersionedConfigImpl(config, 0);
        final var handler = new ClprGetEndpointManifestHandler(stateProofManager, digestSensitiveTss, provider);

        given(stateProofManager.buildManifestStateProofWithValue())
                .willReturn(new ManifestWithProof(proofBytes, ClprEndpointManifest.DEFAULT));
        given(stateProofManager.latestLedgerId()).willReturn(ledgerId);

        final var header =
                ResponseHeader.newBuilder().nodeTransactionPrecheckCode(OK).build();
        final var response = handler.findResponse(queryContext, header);

        assertThat(response.clprGetEndpointManifest().manifestStateProof()).isEqualTo(proofBytes);
    }

    /** A single self-rooting path whose explicit base hash and sibling are {@code size} bytes, so the proof's
     * digest is inferable from its hash length. */
    private static StateProof proofWithHashLength(final int size) {
        final var path = MerklePath.newBuilder()
                .hash(Bytes.wrap(filled(1, size)))
                .siblings(List.of(SiblingNode.newBuilder()
                        .isLeft(false)
                        .hash(Bytes.wrap(filled(2, size)))
                        .build()))
                .nextPathIndex(-1)
                .build();
        return StateProof.newBuilder()
                .paths(path)
                .signedBlockProof(TssSignedBlockProof.newBuilder()
                        .blockSignature(Bytes.wrap(new byte[] {9, 9, 9, 9}))
                        .build())
                .build();
    }

    /** A single self-rooting state-item leaf path with no sibling hashes, so the proof's digest can't be inferred. */
    private static StateProof proofWithoutHashLengthSignal() {
        final var path = MerklePath.newBuilder()
                .stateItemLeaf(Bytes.wrap(filled(3, 17)))
                .nextPathIndex(-1)
                .build();
        return StateProof.newBuilder()
                .paths(path)
                .signedBlockProof(TssSignedBlockProof.newBuilder()
                        .blockSignature(Bytes.wrap(new byte[] {9, 9, 9, 9}))
                        .build())
                .build();
    }

    private static byte[] filled(final int value, final int size) {
        final var bytes = new byte[size];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.verify;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumSyncCommitteeProofVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.VerifiedConfig;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

class EthereumVerifyConfigCallTest extends CallTestBase {
    private static final String VERIFIER_NAME = "EthereumSyncCommitteeProofVerifier";
    private static final byte[] CONFIG_PAYLOAD = {1, 2, 3};
    private static final byte[] MANIFEST_PROOF = {4, 5, 6};

    /** ABI index of the manifest struct in the manifest-aware output tuple. */
    private static final int MANIFEST_INDEX = 7;

    @Test
    void allowsStaticFrame() {
        assertThat(subject().allowsStaticFrame()).isTrue();
    }

    @Test
    void returnsVerifiedLedgerConfiguration() {
        // The verifier already bakes the complete trust anchor (committee + chain pins + service
        // address) into initial_trust_anchor; the call preserves it in the returned tuple.
        final var config = ClprLedgerConfiguration.newBuilder()
                .chainId("ethereum:mainnet")
                .serviceAddress(Bytes.wrap(new byte[20]))
                .initialTrustAnchor(Bytes.wrap(new byte[] {9}))
                .initialTrustAnchorId(Bytes.wrap(new byte[] {9}))
                .build();

        try (final var ignored = mockVerifier(new VerifiedConfig(config, 7L, uninitializedManifest()))) {
            final var result = subject().execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);
            final var decoded = EthereumVerifyConfigTranslator.VERIFY_CONFIG
                    .getOutputs()
                    .decode(result.fullResult().output().toArray());
            assertThat(decoded.size()).isEqualTo(8);
            assertThat((String) decoded.get(1)).isEqualTo(config.chainId());
            assertThat((byte[]) decoded.get(2))
                    .isEqualTo(config.serviceAddress().toByteArray());
            assertThat((byte[]) decoded.get(5))
                    .isEqualTo(config.initialTrustAnchor().toByteArray());
            assertThat((byte[]) decoded.get(6))
                    .isEqualTo(config.initialTrustAnchorId().toByteArray());
            // manifest struct at index 7: the verifier's uninitialized manifest (version 0).
            final Tuple manifestStruct = decoded.get(MANIFEST_INDEX);
            assertThat(((BigInteger) manifestStruct.get(0)).longValue()).isZero();
        }
    }

    @Test
    void rejectsVerifierPayloadException() {
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
    void rejectsEmptyInitialTrustAnchor() {
        final var config = ClprLedgerConfiguration.newBuilder()
                .chainId("ethereum:mainnet")
                .serviceAddress(Bytes.wrap(new byte[20]))
                .build();

        try (final var ignored = mockVerifier(new VerifiedConfig(config, 7L, uninitializedManifest()))) {
            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void manifestEncodingReturns8TupleWithAllConfigFields() {
        final byte[] channelId32 = new byte[32];
        channelId32[0] = (byte) 0xAB;
        final var config = ClprLedgerConfiguration.newBuilder()
                .chainId("ethereum:mainnet")
                .serviceAddress(Bytes.wrap(new byte[20]))
                .initialTrustAnchor(Bytes.wrap(new byte[] {9}))
                .initialTrustAnchorId(Bytes.wrap(new byte[] {8}))
                .throttles(ClprThrottles.newBuilder().maxMessagesPerBundle(42).build())
                .build();

        try (final var ignored = mockVerifier(new VerifiedConfig(config, 7L, uninitializedManifest()))) {
            final var result = new EthereumVerifyConfigCall(
                            mockEnhancement(), gasCalculator, CONFIG_PAYLOAD, channelId32, new byte[0])
                    .execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);

            final var decoded = EthereumVerifyConfigTranslator.VERIFY_CONFIG
                    .getOutputs()
                    .decode(result.fullResult().output().toArray());
            // field 0: channelContext = channelId32 ++ serviceAddress
            final byte[] ctx = decoded.get(0);
            assertThat(ctx[0]).isEqualTo((byte) 0xAB);
            // field 1: chainId
            assertThat((String) decoded.get(1)).isEqualTo("ethereum:mainnet");
            // field 2: serviceAddress
            assertThat(((byte[]) decoded.get(2)).length).isEqualTo(20);
            // field 4: throttles tuple — index 0 = maxMessagesPerBundle
            final Tuple throttlesTuple = (Tuple) decoded.get(4);
            assertThat(((Number) throttlesTuple.get(0)).intValue()).isEqualTo(42);
        }
    }

    @Test
    @DisplayName("given a verified manifest proof, then the call returns the manifest the verifier proved")
    void returnsConfigFieldsAndManifest() {
        final var manifest = ClprEndpointManifest.newBuilder()
                .version(2L)
                .serviceAddress(Bytes.wrap(new byte[20]))
                .endpoints(ClprEndpoint.newBuilder()
                        .tlsCertificate(Bytes.wrap(new byte[] {1}))
                        .build())
                .build();

        try (final var ignored = mockVerifier(new VerifiedConfig(configWithAnchor(), 7L, manifest))) {
            final var result = subjectWithManifestProof(MANIFEST_PROOF).execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            final Tuple manifestStruct =
                    manifestStructOf(result.fullResult().output().toArray());
            assertThat(((BigInteger) manifestStruct.get(0)).longValue()).isEqualTo(2L);
            assertThat((byte[]) manifestStruct.get(1)).isEqualTo(new byte[20]);
            assertThat((Tuple[]) manifestStruct.get(2)).hasSize(1);
        }
    }

    @Test
    @DisplayName("given a manifest proof, then the call hands it to the verifier with the config payload")
    void givenManifestProof_thenPassesItToVerifier() {
        try (final var verifiers = mockVerifier(new VerifiedConfig(configWithAnchor(), 7L, uninitializedManifest()))) {
            subjectWithManifestProof(MANIFEST_PROOF).execute(frame);

            verify(verifiers.constructed().getFirst()).verifyConfigPayload(CONFIG_PAYLOAD, MANIFEST_PROOF);
        }
    }

    private EthereumVerifyConfigCall subject() {
        return new EthereumVerifyConfigCall(
                mockEnhancement(), gasCalculator, CONFIG_PAYLOAD, new byte[32], new byte[0]);
    }

    private EthereumVerifyConfigCall subjectWithManifestProof(final byte[] manifestProofBytes) {
        return new EthereumVerifyConfigCall(
                mockEnhancement(), gasCalculator, CONFIG_PAYLOAD, new byte[32], manifestProofBytes);
    }

    private static ClprLedgerConfiguration configWithAnchor() {
        return ClprLedgerConfiguration.newBuilder()
                .chainId("ethereum:mainnet")
                .serviceAddress(Bytes.wrap(new byte[20]))
                .initialTrustAnchor(Bytes.wrap(new byte[] {9}))
                .initialTrustAnchorId(Bytes.wrap(new byte[] {9}))
                .build();
    }

    /**
     * The manifest the verifier returns when no proof is given: version 0, no endpoints.
     */
    private static ClprEndpointManifest uninitializedManifest() {
        return ClprEndpointManifest.newBuilder()
                .serviceAddress(Bytes.wrap(new byte[20]))
                .build();
    }

    /** Decodes the manifest-aware output tuple and returns the manifest struct {@code (version, serviceAddress, endpoints[])}. */
    private static Tuple manifestStructOf(final byte[] output) {
        final var tuple =
                EthereumVerifyConfigTranslator.VERIFY_CONFIG.getOutputs().decode(output);
        return tuple.get(MANIFEST_INDEX);
    }

    private static MockedConstruction<EthereumSyncCommitteeProofVerifier> mockVerifier(final VerifiedConfig verified) {
        return mockConstruction(
                EthereumSyncCommitteeProofVerifier.class,
                (mock, ctx) -> given(mock.verifyConfigPayload(any(), any())).willReturn(verified));
    }

    private static MockedConstruction<EthereumSyncCommitteeProofVerifier> mockVerifierThrowing(
            final RuntimeException toThrow) {
        return mockConstruction(
                EthereumSyncCommitteeProofVerifier.class,
                (mock, ctx) -> given(mock.verifyConfigPayload(any(), any())).willThrow(toThrow));
    }

    private static void assertFailed(final PricedResult result) {
        assertThat(result.responseCode()).isEqualTo(CLPR_VERIFIER_CONFIG_FAILED);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
    }
}

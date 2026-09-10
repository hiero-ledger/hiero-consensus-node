// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.sei.verify;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.sei.SeiCometBftProofVerifier;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Arrays;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;

class SeiVerifyConfigCallTest extends CallTestBase {
    private static final byte[] CONFIG_PAYLOAD = {1, 2, 3};

    @Test
    void allowsStaticFrame() {
        assertThat(subject(CONFIG_PAYLOAD).allowsStaticFrame()).isTrue();
    }

    @Test
    void returnsVerifiedLedgerConfiguration() throws ParseException {
        final var config = ClprLedgerConfiguration.newBuilder()
                .chainId("sei:atlantic-2")
                .serviceAddress(Bytes.wrap(new byte[20]))
                .initialTrustAnchor(Bytes.wrap(new byte[] {9}))
                .initialTrustAnchorId(Bytes.wrap(new byte[] {8}))
                .build();
        final var verified = new SeiCometBftProofVerifier.VerifiedConfig(config, new byte[0]);

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyConfigPayload(CONFIG_PAYLOAD))
                    .thenReturn(verified);

            final var result = subject(CONFIG_PAYLOAD).execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);
            assertThat(decodedConfig(result.fullResult().output().toArray())).isEqualTo(config);
        }
    }

    @Test
    void rejectsVerifierPayloadException() {
        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyConfigPayload(CONFIG_PAYLOAD))
                    .thenThrow(ProofException.sei("bad proof"));

            assertFailed(subject(CONFIG_PAYLOAD).execute(frame));
        }
    }

    @Test
    void rejectsUnexpectedVerifierException() {
        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyConfigPayload(CONFIG_PAYLOAD))
                    .thenThrow(new IllegalStateException("boom"));

            assertFailed(subject(CONFIG_PAYLOAD).execute(frame));
        }
    }

    @Test
    void rejectsEmptyInitialTrustAnchor() {
        final var config = ClprLedgerConfiguration.newBuilder()
                .chainId("sei:atlantic-2")
                .serviceAddress(Bytes.wrap(new byte[20]))
                .build();
        final var verified = new SeiCometBftProofVerifier.VerifiedConfig(config, new byte[0]);

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyConfigPayload(CONFIG_PAYLOAD))
                    .thenReturn(verified);

            assertFailed(subject(CONFIG_PAYLOAD).execute(frame));
        }
    }

    @Test
    void returnsV2HeadlongTupleOnSuccess() {
        final byte[] channelId32 = new byte[32];
        channelId32[0] = (byte) 0xAB;
        final var config = ClprLedgerConfiguration.newBuilder()
                .chainId("sei:atlantic-2")
                .serviceAddress(Bytes.wrap(new byte[20]))
                .initialTrustAnchor(Bytes.wrap(new byte[] {9}))
                .initialTrustAnchorId(Bytes.wrap(new byte[] {8}))
                .build();
        final var verified = new SeiCometBftProofVerifier.VerifiedConfig(config, new byte[0]);

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyConfigPayload(CONFIG_PAYLOAD))
                    .thenReturn(verified);

            final var result = new SeiVerifyConfigCall(mockEnhancement(), gasCalculator, CONFIG_PAYLOAD, channelId32)
                    .execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            final var decoded = SeiVerifyConfigTranslator.VERIFY_CONFIG_V2
                    .getOutputs()
                    .decode(result.fullResult().output().toArray());
            final byte[] channelContext = (byte[]) decoded.get(0);
            assertThat(Arrays.copyOf(channelContext, 32)).isEqualTo(channelId32);
        }
    }

    @Test
    void returnsV3HeadlongTupleWithSeedFallbackManifest() {
        final byte[] channelId32 = new byte[32];
        channelId32[0] = (byte) 0xCD;
        // Sei has no config-path manifest-proof producer, so the 3rd arg drives the seed-fallback (version 1,
        // bound to the config's service address) rather than a proven manifest.
        final byte[] manifestProof = {7, 7, 7};
        final var config = ClprLedgerConfiguration.newBuilder()
                .chainId("sei:atlantic-2")
                .serviceAddress(Bytes.wrap(new byte[20]))
                .initialTrustAnchor(Bytes.wrap(new byte[] {9}))
                .initialTrustAnchorId(Bytes.wrap(new byte[] {8}))
                .build();
        final var verified = new SeiCometBftProofVerifier.VerifiedConfig(config, new byte[0]);

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyConfigPayload(CONFIG_PAYLOAD))
                    .thenReturn(verified);

            final var result = new SeiVerifyConfigCall(
                            mockEnhancement(), gasCalculator, CONFIG_PAYLOAD, channelId32, manifestProof)
                    .execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            final var decoded = SeiVerifyConfigTranslator.VERIFY_CONFIG_V3
                    .getOutputs()
                    .decode(result.fullResult().output().toArray());
            assertThat(decoded.size()).isEqualTo(8);
            final byte[] channelContext = (byte[]) decoded.get(0);
            assertThat(Arrays.copyOf(channelContext, 32)).isEqualTo(channelId32);
            // manifest struct at index 7: (uint64 version, bytes serviceAddress, endpoints[]) — seed-fallback.
            final com.esaulpaugh.headlong.abi.Tuple manifestStruct = decoded.get(7);
            assertThat(((java.math.BigInteger) manifestStruct.get(0)).longValue())
                    .isEqualTo(1L);
            assertThat(((byte[]) manifestStruct.get(1)).length).isEqualTo(20);
        }
    }

    @Test
    void v2FailureStillReverts() {
        final byte[] channelId32 = new byte[32];

        try (final var verifier = mockStatic(SeiCometBftProofVerifier.class)) {
            verifier.when(() -> SeiCometBftProofVerifier.verifyConfigPayload(CONFIG_PAYLOAD))
                    .thenThrow(ProofException.sei("bad proof"));

            assertFailed(new SeiVerifyConfigCall(mockEnhancement(), gasCalculator, CONFIG_PAYLOAD, channelId32)
                    .execute(frame));
        }
    }

    private SeiVerifyConfigCall subject(final byte[] configPayload) {
        return new SeiVerifyConfigCall(mockEnhancement(), gasCalculator, configPayload);
    }

    private static ClprLedgerConfiguration decodedConfig(final byte[] output) throws ParseException {
        final var tuple = SeiVerifyConfigTranslator.VERIFY_CONFIG.getOutputs().decode(output);
        return ClprLedgerConfiguration.PROTOBUF.parse(
                Bytes.wrap((byte[]) tuple.get(0)).toReadableSequentialData());
    }

    private static void assertFailed(
            final com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult result) {
        assertThat(result.responseCode()).isEqualTo(CLPR_VERIFIER_CONFIG_FAILED);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
    }
}

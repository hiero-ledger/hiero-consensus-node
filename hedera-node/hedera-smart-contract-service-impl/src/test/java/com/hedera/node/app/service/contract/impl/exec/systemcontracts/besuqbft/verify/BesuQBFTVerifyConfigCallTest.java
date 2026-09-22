// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft.verify;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockConstruction;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.impl.verifier.BesuQbftVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

class BesuQBFTVerifyConfigCallTest extends CallTestBase {
    private static final byte[] STATE_PROOF = {1, 2, 3};

    @Test
    void allowsStaticFrame() {
        assertThat(subject().allowsStaticFrame()).isTrue();
    }

    @Test
    void returnsVerifiedLedgerConfiguration() {
        try (final var ignored = mockVerifier(minimalConfig())) {
            final var result = subject().execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);
        }
    }

    @Test
    void rejectsVerifierPayloadException() {
        try (final var ignored = mockVerifierThrowing(new ProofException("QBFT", "bad proof"))) {
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
                .chainId("besu:qbft:testnet")
                .serviceAddress(Bytes.wrap(new byte[20]))
                .build();

        try (final var ignored = mockVerifier(config)) {
            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void v2EncodingReturns8TupleWithAllConfigFields() throws Exception {
        final byte[] channelId32 = new byte[32];
        channelId32[0] = (byte) 0xAB;
        final ClprThrottles throttles = ClprThrottles.newBuilder()
                .maxMessagesPerBundle(100)
                .maxMessagePayloadBytes(65_536)
                .maxGasPerMessage(1_000_000L)
                .maxQueueDepth(10)
                .maxSyncBytes(2_000_000L)
                .build();
        final var config = ClprLedgerConfiguration.newBuilder()
                .chainId("besu:qbft:testnet")
                .serviceAddress(Bytes.wrap(new byte[20]))
                .initialTrustAnchor(Bytes.wrap(new byte[] {9}))
                .initialTrustAnchorId(Bytes.wrap(new byte[] {8}))
                .throttles(throttles)
                .build();

        try (final var ignored = mockVerifier(config)) {
            final var result = new BesuQBFTVerifyConfigCall(mockEnhancement(), gasCalculator, STATE_PROOF, channelId32)
                    .execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);

            final var decoded = BesuQBFTVerifyConfigTranslator.VERIFY_CONFIG_V2
                    .getOutputs()
                    .decode(result.fullResult().output().toArray());
            // field 0: channelContext = channelId32 ++ serviceAddress
            final byte[] ctx = decoded.get(0);
            assertThat(ctx[0]).isEqualTo((byte) 0xAB);
            // field 1: chainId
            assertThat((String) decoded.get(1)).isEqualTo("besu:qbft:testnet");
            // field 2: serviceAddress
            assertThat(((byte[]) decoded.get(2)).length).isEqualTo(20);
            // field 4: throttles tuple — index 0 = maxMessagesPerBundle, index 2 = maxGasPerMessage
            final Tuple throttlesTuple = decoded.get(4);
            assertThat(((BigInteger) throttlesTuple.get(0)).intValue()).isEqualTo(100);
            assertThat(((BigInteger) throttlesTuple.get(2)).longValue()).isEqualTo(1_000_000L);
        }
    }

    private BesuQBFTVerifyConfigCall subject() {
        return new BesuQBFTVerifyConfigCall(mockEnhancement(), gasCalculator, STATE_PROOF);
    }

    private static ClprLedgerConfiguration minimalConfig() {
        return ClprLedgerConfiguration.newBuilder()
                .chainId("besu:qbft:testnet")
                .serviceAddress(Bytes.wrap(new byte[20]))
                .initialTrustAnchor(Bytes.wrap(new byte[] {9}))
                .initialTrustAnchorId(Bytes.wrap(new byte[] {8}))
                .build();
    }

    private static MockedConstruction<BesuQbftVerifier> mockVerifier(final ClprLedgerConfiguration config) {
        return mockConstruction(BesuQbftVerifier.class, (mock, ctx) -> given(mock.verifyConfigPayload(any(), any()))
                .willReturn(new BesuQbftVerifier.VerifiedConfig(new byte[32], config, new byte[0])));
    }

    private static MockedConstruction<BesuQbftVerifier> mockVerifierThrowing(final RuntimeException toThrow) {
        return mockConstruction(BesuQbftVerifier.class, (mock, ctx) -> given(mock.verifyConfigPayload(any(), any()))
                .willThrow(toThrow));
    }

    private static void assertFailed(final PricedResult result) {
        assertThat(result.responseCode()).isEqualTo(CLPR_VERIFIER_CONFIG_FAILED);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
    }
}

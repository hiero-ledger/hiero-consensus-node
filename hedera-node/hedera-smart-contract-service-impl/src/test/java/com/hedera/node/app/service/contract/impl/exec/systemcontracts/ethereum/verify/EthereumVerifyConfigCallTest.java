// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.verify;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockConstruction;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumSyncCommitteeProofVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.VerifiedConfig;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

class EthereumVerifyConfigCallTest extends CallTestBase {
    private static final String VERIFIER_NAME = "EthereumSyncCommitteeProofVerifier";
    private static final byte[] CONFIG_PAYLOAD = {1, 2, 3};

    /** ABI index of the manifest struct in the V3 output tuple. */
    private static final int V3_MANIFEST_INDEX = 7;

    @Test
    void allowsStaticFrame() {
        assertThat(subject().allowsStaticFrame()).isTrue();
    }

    @Test
    void returnsVerifiedLedgerConfiguration() throws ParseException {
        // The verifier already bakes the complete trust anchor (committee + chain pins + service
        // address) into initial_trust_anchor; the call returns the configuration verbatim.
        final var config = ClprLedgerConfiguration.newBuilder()
                .chainId("ethereum:mainnet")
                .serviceAddress(Bytes.wrap(new byte[20]))
                .initialTrustAnchor(Bytes.wrap(new byte[] {9}))
                .initialTrustAnchorId(Bytes.wrap(new byte[] {9}))
                .build();

        try (final var ignored = mockVerifier(new VerifiedConfig(config, 7L))) {
            final var result = subject().execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);
            assertThat(decodedConfig(result.fullResult().output().toArray())).isEqualTo(config);
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

        try (final var ignored = mockVerifier(new VerifiedConfig(config, 7L))) {
            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void v2EncodingReturns8TupleWithAllConfigFields() {
        final byte[] channelId32 = new byte[32];
        channelId32[0] = (byte) 0xAB;
        final var config = ClprLedgerConfiguration.newBuilder()
                .chainId("ethereum:mainnet")
                .serviceAddress(Bytes.wrap(new byte[20]))
                .initialTrustAnchor(Bytes.wrap(new byte[] {9}))
                .initialTrustAnchorId(Bytes.wrap(new byte[] {8}))
                .throttles(ClprThrottles.newBuilder().maxMessagesPerBundle(42).build())
                .build();

        try (final var ignored = mockVerifier(new VerifiedConfig(config, 7L))) {
            final var result = new EthereumVerifyConfigCall(
                            mockEnhancement(), gasCalculator, CONFIG_PAYLOAD, channelId32)
                    .execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);

            final var decoded = EthereumVerifyConfigTranslator.VERIFY_CONFIG_V2
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
            assertThat(((BigInteger) throttlesTuple.get(0)).intValue()).isEqualTo(42);
        }
    }

    @Test
    void v3ReturnsConfigFieldsAndManifest() {
        final var config = configWithAnchor();
        final var manifest = ClprEndpointManifest.newBuilder()
                .version(2L)
                .serviceAddress(Bytes.wrap(new byte[20]))
                .build();

        try (final var ignored = mockVerifier(new VerifiedConfig(config, 7L))) {
            final var result = subjectV3(serializeManifest(manifest)).execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            final Tuple manifestStruct =
                    manifestStructOf(result.fullResult().output().toArray());
            assertThat(((BigInteger) manifestStruct.get(0)).longValue()).isEqualTo(2L);
            assertThat(((byte[]) manifestStruct.get(1)).length).isEqualTo(20);
        }
    }

    @Test
    void v3EmptyManifest_synthesizesFromConfig() {
        final var config = configWithAnchor();

        try (final var ignored = mockVerifier(new VerifiedConfig(config, 7L))) {
            final var result = subjectV3(new byte[0]).execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            final Tuple manifestStruct =
                    manifestStructOf(result.fullResult().output().toArray());
            // Empty manifest bytes → synthesized manifest bound to the config: version 1, config's serviceAddress.
            assertThat(((BigInteger) manifestStruct.get(0)).longValue()).isEqualTo(1L);
            assertThat(((byte[]) manifestStruct.get(1)).length).isEqualTo(20);
        }
    }

    @Test
    void v3ManifestVersionZero_fails() {
        final var config = configWithAnchor();
        final var manifest = ClprEndpointManifest.newBuilder()
                .version(0L)
                .serviceAddress(Bytes.wrap(new byte[20]))
                .build();

        try (final var ignored = mockVerifier(new VerifiedConfig(config, 7L))) {
            assertFailed(subjectV3(serializeManifest(manifest)).execute(frame));
        }
    }

    @Test
    void v3ManifestServiceAddressMismatch_fails() {
        final var config = configWithAnchor();
        final byte[] otherAddr = new byte[20];
        otherAddr[0] = 0x7;
        final var manifest = ClprEndpointManifest.newBuilder()
                .version(1L)
                .serviceAddress(Bytes.wrap(otherAddr))
                .build();

        try (final var ignored = mockVerifier(new VerifiedConfig(config, 7L))) {
            assertFailed(subjectV3(serializeManifest(manifest)).execute(frame));
        }
    }

    private EthereumVerifyConfigCall subject() {
        // Legacy V1 (flag off): config payload only.
        return new EthereumVerifyConfigCall(mockEnhancement(), gasCalculator, CONFIG_PAYLOAD);
    }

    /** V3 (flag on): channel context + raw manifest bytes. */
    private EthereumVerifyConfigCall subjectV3(final byte[] manifestBytes) {
        return new EthereumVerifyConfigCall(
                mockEnhancement(), gasCalculator, CONFIG_PAYLOAD, new byte[32], manifestBytes);
    }

    private static ClprLedgerConfiguration configWithAnchor() {
        return ClprLedgerConfiguration.newBuilder()
                .chainId("ethereum:mainnet")
                .serviceAddress(Bytes.wrap(new byte[20]))
                .initialTrustAnchor(Bytes.wrap(new byte[] {9}))
                .initialTrustAnchorId(Bytes.wrap(new byte[] {9}))
                .build();
    }

    private static byte[] serializeManifest(final ClprEndpointManifest manifest) {
        return ClprEndpointManifest.PROTOBUF.toBytes(manifest).toByteArray();
    }

    /** Decodes the V3 output tuple and returns the manifest struct {@code (version, serviceAddress, endpoints[])}. */
    private static Tuple manifestStructOf(final byte[] output) {
        final var tuple =
                EthereumVerifyConfigTranslator.VERIFY_CONFIG_V3.getOutputs().decode(output);
        return tuple.get(V3_MANIFEST_INDEX);
    }

    private static MockedConstruction<EthereumSyncCommitteeProofVerifier> mockVerifier(final VerifiedConfig verified) {
        return mockConstruction(
                EthereumSyncCommitteeProofVerifier.class,
                (mock, ctx) -> given(mock.verifyConfigPayload(any())).willReturn(verified));
    }

    private static MockedConstruction<EthereumSyncCommitteeProofVerifier> mockVerifierThrowing(
            final RuntimeException toThrow) {
        return mockConstruction(
                EthereumSyncCommitteeProofVerifier.class,
                (mock, ctx) -> given(mock.verifyConfigPayload(any())).willThrow(toThrow));
    }

    private static ClprLedgerConfiguration decodedConfig(final byte[] output) throws ParseException {
        final var tuple =
                EthereumVerifyConfigTranslator.VERIFY_CONFIG.getOutputs().decode(output);
        return ClprLedgerConfiguration.PROTOBUF.parse(
                Bytes.wrap((byte[]) tuple.get(0)).toReadableSequentialData());
    }

    private static void assertFailed(final PricedResult result) {
        assertThat(result.responseCode()).isEqualTo(CLPR_VERIFIER_CONFIG_FAILED);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
    }
}

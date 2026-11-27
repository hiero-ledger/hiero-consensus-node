// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.canton.verify;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.ReadableLedgerConfigurationStore;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi;
import com.hedera.node.app.service.clpr.impl.verifier.VerifiedConfig;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProofException;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProofVerifier;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;

class CantonVerifyConfigCallTest extends CallTestBase {
    private static final long GAS = 100_000L;
    private static final String LOCAL_CHAIN = "eip155:298";
    private static final byte[] CONFIG_PROOF = {1, 2, 3};
    private static final byte[] CHANNEL_ID = filled(32, 0x11);
    private static final byte[] MANIFEST_PROOF = {4, 5};
    private static final byte[] SERVICE_ADDRESS = filled(40, 0x5a);
    private static final byte[] ANCHOR = {9, 9, 9};
    private static final byte[] ANCHOR_ID = {0, 0, 0, 0, 0, 0, 0, 42};
    private static final long CONFIG_NANOS = 1_700_000_000_123_456_789L;

    @Mock
    private ReadableLedgerConfigurationStore ledgerConfigurationStore;

    @Test
    void allowsStaticFrame() {
        assertThat(subject().allowsStaticFrame()).isTrue();
    }

    @Test
    void passesTheProofsAndThisLedgersChainIdToTheCantonVerifier() {
        givenLocalChain(LOCAL_CHAIN);
        try (final MockedStatic<CantonProofVerifier> canton = mockStatic(CantonProofVerifier.class)) {
            canton.when(() -> CantonProofVerifier.verifyConfig(any(), any(), any(), any()))
                    .thenReturn(verified(ClprEndpointManifest.DEFAULT));

            subject().execute(frame);

            canton.verify(() -> CantonProofVerifier.verifyConfig(
                    CONFIG_PROOF, CHANNEL_ID, MANIFEST_PROOF, LOCAL_CHAIN.getBytes(StandardCharsets.UTF_8)));
        }
        // Read through the typed accessor, never storeFactory(), which throws in a query context
        verify(nativeOperations, never()).storeFactory();
    }

    @Test
    void encodesTheVerifiedConfigAndManifestInTheSharedVerifierAbi() {
        givenLocalChain(LOCAL_CHAIN);
        final var manifest = ClprEndpointManifest.newBuilder()
                .version(3L)
                .serviceAddress(Bytes.wrap(SERVICE_ADDRESS))
                .endpoints(ClprEndpoint.newBuilder()
                        .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                                .ipAddress("10.0.0.1")
                                .port(50211))
                        .tlsCertificate(Bytes.wrap(new byte[] {7}))
                        .build())
                .build();
        try (final MockedStatic<CantonProofVerifier> canton = mockStatic(CantonProofVerifier.class)) {
            canton.when(() -> CantonProofVerifier.verifyConfig(any(), any(), any(), any()))
                    .thenReturn(verified(manifest));

            final var result = subject().execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);
            assertThat(result.fullResult().gasRequirement()).isEqualTo(GAS);
            final Tuple out = ClprVerifierAbi.VERIFY_CONFIG_RETURN.decode(
                    result.fullResult().output().toArray());
            assertThat((byte[]) out.get(0)).isEqualTo(concat(CHANNEL_ID, SERVICE_ADDRESS));
            assertThat((String) out.get(1)).isEqualTo("canton:peer");
            assertThat((byte[]) out.get(2)).isEqualTo(SERVICE_ADDRESS);
            assertThat((BigInteger) out.get(3)).isEqualTo(BigInteger.valueOf(CONFIG_NANOS));
            assertThat((Tuple) out.get(4))
                    .isEqualTo(Tuple.from(
                            50L,
                            BigInteger.valueOf(1_000_000),
                            BigInteger.valueOf(5_000_000),
                            100L,
                            BigInteger.valueOf(10_000_000),
                            20L,
                            30L));
            assertThat((byte[]) out.get(5)).isEqualTo(ANCHOR);
            assertThat((byte[]) out.get(6)).isEqualTo(ANCHOR_ID);
            assertThat((Tuple) out.get(7)).isEqualTo(ClprVerifierAbi.manifestStructTuple(manifest));
        }
    }

    @Test
    void revertsWhenTheCantonVerifierRejectsTheProof() {
        givenLocalChain(LOCAL_CHAIN);
        try (final MockedStatic<CantonProofVerifier> canton = mockStatic(CantonProofVerifier.class)) {
            canton.when(() -> CantonProofVerifier.verifyConfig(any(), any(), any(), any()))
                    .thenThrow(CantonProofException.invalidPayloadShape("bad"));

            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void revertsOnAnUnexpectedVerifierError() {
        givenLocalChain(LOCAL_CHAIN);
        try (final MockedStatic<CantonProofVerifier> canton = mockStatic(CantonProofVerifier.class)) {
            canton.when(() -> CantonProofVerifier.verifyConfig(any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("boom"));

            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void revertsWithoutVerifyingWhenTheLedgerConfigurationIsUnavailable() {
        given(nativeOperations.readableLedgerConfigurationStore()).willThrow(new IllegalStateException("gone"));
        try (final MockedStatic<CantonProofVerifier> canton = mockStatic(CantonProofVerifier.class)) {
            assertFailed(subject().execute(frame));

            canton.verifyNoInteractions();
        }
    }

    private void givenLocalChain(final String chainId) {
        given(nativeOperations.readableLedgerConfigurationStore()).willReturn(ledgerConfigurationStore);
        given(ledgerConfigurationStore.getConfiguration())
                .willReturn(
                        ClprLedgerConfiguration.newBuilder().chainId(chainId).build());
    }

    private CantonVerifyConfigCall subject() {
        return new CantonVerifyConfigCall(mockEnhancement(), gasCalculator, CONFIG_PROOF, CHANNEL_ID, MANIFEST_PROOF);
    }

    private static VerifiedConfig verified(final ClprEndpointManifest manifest) {
        final var config = ClprLedgerConfiguration.newBuilder()
                .chainId("canton:peer")
                .serviceAddress(Bytes.wrap(SERVICE_ADDRESS))
                .timestamp(Timestamp.newBuilder()
                        .seconds(CONFIG_NANOS / 1_000_000_000L)
                        .nanos((int) (CONFIG_NANOS % 1_000_000_000L)))
                .throttles(ClprThrottles.newBuilder()
                        .maxMessagesPerBundle(50)
                        .maxMessagePayloadBytes(1_000_000)
                        .maxGasPerMessage(5_000_000)
                        .maxQueueDepth(100)
                        .maxSyncBytes(10_000_000)
                        .maxLocalEndpoints(20)
                        .maxPeerEndpoints(30))
                .initialTrustAnchor(Bytes.wrap(ANCHOR))
                .initialTrustAnchorId(Bytes.wrap(ANCHOR_ID))
                .build();
        return new VerifiedConfig(config, manifest);
    }

    private static void assertFailed(final PricedResult result) {
        assertThat(result.responseCode()).isEqualTo(CLPR_VERIFIER_CONFIG_FAILED);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
        assertThat(result.fullResult().gasRequirement()).isEqualTo(GAS);
    }

    private static byte[] concat(final byte[] a, final byte[] b) {
        final byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] filled(final int length, final int value) {
        final byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}

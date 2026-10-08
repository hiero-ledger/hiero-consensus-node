// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_EVM_ADDRESS_BYTES;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_SERVICE_ACCOUNT_ID;
import static com.hedera.node.app.spi.fees.NoopFeeCharging.DISPATCH_ONLY_NOOP_FEE_CHARGING;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.CLPR_DISPATCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.token.records.HookDispatchStreamBuilder;
import com.hedera.node.app.spi.workflows.ClprDispatchMetadata;
import com.hedera.node.app.spi.workflows.DispatchOptions;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit test for {@link EvmClprVerifier#verifyConfig}, which dispatches to the channel's verifier
 * contract and decodes the ABI-encoded tuple return. It uses the manifest-aware ABI
 * {@code verifyConfig(bytes,bytes32,bytes)}. The verifier's {@code context.dispatch(...)} is mocked
 * to return the raw ABI tuple the contract would produce, so no real proof/TSS machinery is involved.
 */
@ExtendWith(MockitoExtension.class)
class EvmClprVerifierTest {

    private static final ContractID VERIFIER =
            ContractID.newBuilder().contractNum(0x16eL).build();
    private static final AccountID PAYER = AccountID.newBuilder().accountNum(2L).build();
    private static final byte[] SERVICE_ADDR = new byte[20];

    // Manifest-aware config return tuple — single-sourced with the producer + consumer via ClprVerifierAbi.
    private static final TupleType<Tuple> CONFIG_RETURN = ClprVerifierAbi.VERIFY_CONFIG_RETURN;
    // Manifest-aware bundle return tuple — single-sourced with the producer + consumer via ClprVerifierAbi.
    private static final TupleType<Tuple> BUNDLE_RETURN = ClprVerifierAbi.VERIFY_BUNDLE_RETURN;

    @Mock
    private HandleContext context;

    @Mock
    private HookDispatchStreamBuilder dispatchResult;

    private final EvmClprVerifier subject = new EvmClprVerifier(VERIFIER);

    @Test
    @DisplayName("returns the proven config and the state-proven manifest from the tuple return")
    void verifyConfigWithManifestReturnsProvenConfigAndManifest() {
        givenConfig();
        givenDispatchReturns(configWithManifestReturn("295", SERVICE_ADDR, 3L));

        final var verified = subject.verifyConfig(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context);

        assertThat(verified.config().chainId()).isEqualTo("295");
        assertThat(verified.config().serviceAddress()).isEqualTo(Bytes.wrap(SERVICE_ADDR));
        assertThat(verified.manifest().version()).isEqualTo(3L);
        assertThat(verified.manifest().serviceAddress()).isEqualTo(Bytes.wrap(SERVICE_ADDR));
    }

    @Test
    @DisplayName("manifest-aware: a return that is not a well-formed config tuple reverts")
    void verifyConfigWithManifestMalformedTupleReverts() {
        givenConfig();
        givenDispatchReturns(Bytes.wrap(new byte[] {(byte) 0xff, (byte) 0xff}));

        assertThatThrownBy(() -> subject.verifyConfig(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context))
                .isInstanceOf(HandleException.class);
    }

    @Test
    @DisplayName("Verifier dispatch suppresses its child fee but allows EVM gas collection")
    void verifierDispatchAllowsEvmGasCollection() {
        givenConfig();
        givenDispatchReturns(configWithManifestReturn("295", SERVICE_ADDR, 1L));

        subject.verifyConfig(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context);

        final var optionsCaptor = ArgumentCaptor.forClass(DispatchOptions.class);
        verify(context).dispatch(optionsCaptor.capture());
        final var options = optionsCaptor.getValue();
        assertThat(options.customFeeCharging()).isSameAs(DISPATCH_ONLY_NOOP_FEE_CHARGING);
        final var metadata = options.dispatchMetadata()
                .getMetadata(CLPR_DISPATCH, ClprDispatchMetadata.class)
                .orElseThrow();
        assertThat(metadata.senderId()).isEqualTo(CLPR_SERVICE_ACCOUNT_ID);
        assertThat(metadata.senderAddress()).isEqualTo(CLPR_EVM_ADDRESS_BYTES);
    }

    @Test
    @DisplayName(
            "verifyBundleWithManifest: nextMessageId == 0 sentinel decodes to a manifest-only content (null metadata)")
    void verifyBundleWithManifestManifestOnlySentinelDecodesToNullMetadata() {
        givenConfig();
        givenDispatchReturns(bundleWithManifestReturn(0L, 7L, SERVICE_ADDR));

        final var content = subject.verifyBundle(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context);

        // Metadata-absent sentinel → null metadata so ClprSubmitBundleHandler takes its state-update-only path.
        assertThat(content.metadata()).isNull();
        assertThat(content.messages()).isEmpty();
        assertThat(content.newEndpointManifest()).isNotNull();
        assertThat(content.newEndpointManifest().version()).isEqualTo(7L);
        assertThat(content.newEndpointManifest().serviceAddress()).isEqualTo(Bytes.wrap(SERVICE_ADDR));
    }

    @Test
    @DisplayName("verifyBundleWithManifest: a normal bundle (nextMessageId >= 1) keeps its queue metadata")
    void verifyBundleWithManifestNormalBundleKeepsMetadata() {
        givenConfig();
        // manifestVersion 0 = absent; nextMessageId 1 = a real (non-sentinel) bundle.
        givenDispatchReturns(bundleWithManifestReturn(1L, 0L, SERVICE_ADDR));

        final var content = subject.verifyBundle(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context);

        assertThat(content.metadata()).isNotNull();
        assertThat(content.metadata().nextMessageId()).isEqualTo(1L);
        assertThat(content.newEndpointManifest()).isNull();
    }

    @Test
    @DisplayName("verifyBundle: the sixth metadata member decodes to the sender's view of our manifest version")
    void verifyBundleDecodesPeerObservedEndpointManifestVersion() {
        givenConfig();
        givenDispatchReturns(bundleWithManifestReturn(1L, 0L, SERVICE_ADDR, 9L));

        final var content = subject.verifyBundle(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context);

        assertThat(content.metadata()).isNotNull();
        assertThat(content.metadata().endpointManifestVersion()).isEqualTo(9L);
    }

    // ---- helpers ----

    private void givenConfig() {
        final Configuration cfg = HederaTestConfigBuilder.create().getOrCreateConfig();
        given(context.configuration()).willReturn(cfg);
    }

    private void givenDispatchReturns(final Bytes evmResult) {
        lenient().when(context.payer()).thenReturn(PAYER);
        given(context.dispatch(any(DispatchOptions.class))).willReturn(dispatchResult);
        lenient().when(dispatchResult.status()).thenReturn(SUCCESS);
        lenient().when(dispatchResult.getEvmCallResult()).thenReturn(evmResult);
    }

    /** Encodes the manifest-aware return: config fields (7-field throttles, no endpoints) + manifest struct. */
    private static Bytes configWithManifestReturn(
            final String chainId, final byte[] serviceAddr, final long manifestVersion) {
        final Tuple throttles = Tuple.from(
                Long.valueOf(0L),
                BigInteger.ZERO,
                BigInteger.ZERO,
                Long.valueOf(0L),
                BigInteger.ZERO,
                Long.valueOf(0L),
                Long.valueOf(0L));
        final Tuple manifest = Tuple.of(BigInteger.valueOf(manifestVersion), serviceAddr, new Tuple[0]);
        final byte[] encoded = CONFIG_RETURN
                .encode(Tuple.from(
                        new byte[0],
                        chainId,
                        serviceAddr,
                        BigInteger.ZERO,
                        throttles,
                        new byte[0],
                        new byte[0],
                        manifest))
                .array();
        return Bytes.wrap(encoded);
    }

    /**
     * Encodes the manifest-aware bundle return: metadata tuple + empty messages/trust-anchor + manifest struct.
     * {@code nextMessageId == 0} is the metadata-absent sentinel; {@code manifestVersion == 0} means
     * "no manifest".
     */
    private static Bytes bundleWithManifestReturn(
            final long nextMessageId, final long manifestVersion, final byte[] serviceAddr) {
        return bundleWithManifestReturn(nextMessageId, manifestVersion, serviceAddr, 0L);
    }

    private static Bytes bundleWithManifestReturn(
            final long nextMessageId,
            final long manifestVersion,
            final byte[] serviceAddr,
            final long endpointManifestVersion) {
        final Tuple meta = Tuple.of(
                BigInteger.valueOf(nextMessageId),
                new byte[32],
                BigInteger.ZERO,
                new byte[32],
                0,
                BigInteger.valueOf(endpointManifestVersion));
        final Tuple manifest = Tuple.of(BigInteger.valueOf(manifestVersion), serviceAddr, new Tuple[0]);
        final byte[] encoded = BUNDLE_RETURN
                .encode(Tuple.of(meta, new byte[0][], new byte[0], new byte[0], manifest))
                .array();
        return Bytes.wrap(encoded);
    }
}

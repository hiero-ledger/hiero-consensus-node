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
 * contract and decodes the ABI-encoded tuple return. Behind {@code clpr.endpointManifestEnabled} it
 * uses the V3 (context + manifest) ABI {@code verifyConfig(bytes,bytes32,bytes)} and, with the flag
 * off, the V2 (context) ABI {@code verifyConfig(bytes,bytes32)} — synthesizing a bring-up manifest
 * from the returned config. The verifier's {@code context.dispatch(...)} is mocked to return the raw
 * ABI tuple the contract would produce, so no real proof/TSS machinery is involved.
 */
@ExtendWith(MockitoExtension.class)
class EvmClprVerifierTest {

    private static final ContractID VERIFIER =
            ContractID.newBuilder().contractNum(0x16eL).build();
    private static final AccountID PAYER = AccountID.newBuilder().accountNum(2L).build();
    private static final byte[] SERVICE_ADDR = new byte[20];

    // V3 (SC-189) config return tuple — single-sourced with the producer + consumer via ClprVerifierAbi.
    private static final TupleType<Tuple> V3_RETURN = ClprVerifierAbi.VERIFY_CONFIG_V3_RETURN;
    // V2 (mainline) return tuple — mirrors EvmClprVerifier.VERIFY_CONFIG_V2_RETURN.
    private static final TupleType<Tuple> V2_RETURN = TupleType.parse(
            "(bytes,string,bytes,uint96,(uint64,uint64,uint64,uint64,uint64),bytes,bytes,(string,uint32,bytes,bytes)[])");
    // V3 (SC-189) bundle return tuple — single-sourced with the producer + consumer via ClprVerifierAbi.
    private static final TupleType<Tuple> BUNDLE_V3_RETURN = ClprVerifierAbi.VERIFY_BUNDLE_V3_RETURN;
    // V2 (mainline) bundle return tuple — mirrors EvmClprVerifier.VERIFY_BUNDLE_V2_RETURN (no manifest member).
    private static final TupleType<Tuple> BUNDLE_V2_RETURN =
            TupleType.parse("((uint64,bytes32,uint64,bytes32,uint8),bytes[],bytes,bytes)");

    @Mock
    private HandleContext context;

    @Mock
    private HookDispatchStreamBuilder dispatchResult;

    private final EvmClprVerifier subject = new EvmClprVerifier(VERIFIER);

    @Test
    @DisplayName("V3 (flag on): returns the proven config and the state-proven manifest from the tuple return")
    void verifyConfigV3ReturnsProvenConfigAndManifest() {
        givenManifestEnabled(true);
        givenDispatchReturns(v3Return("295", SERVICE_ADDR, 3L));

        final var verified = subject.verifyConfig(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context);

        assertThat(verified.config().chainId()).isEqualTo("295");
        assertThat(verified.config().serviceAddress()).isEqualTo(Bytes.wrap(SERVICE_ADDR));
        assertThat(verified.manifest().version()).isEqualTo(3L);
        assertThat(verified.manifest().serviceAddress()).isEqualTo(Bytes.wrap(SERVICE_ADDR));
    }

    @Test
    @DisplayName("V3: a return that is not a well-formed config tuple reverts")
    void verifyConfigV3MalformedTupleReverts() {
        givenManifestEnabled(true);
        givenDispatchReturns(Bytes.wrap(new byte[] {(byte) 0xff, (byte) 0xff}));

        assertThatThrownBy(() -> subject.verifyConfig(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context))
                .isInstanceOf(HandleException.class);
    }

    @Test
    @DisplayName("V2 (flag off): decodes the context tuple and synthesizes a bring-up manifest")
    void verifyConfigV2WhenFlagOffSynthesizesManifest() {
        givenManifestEnabled(false);
        givenDispatchReturns(v2Return("295", SERVICE_ADDR));

        final var verified = subject.verifyConfig(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context);

        assertThat(verified.config().chainId()).isEqualTo("295");
        assertThat(verified.config().serviceAddress()).isEqualTo(Bytes.wrap(SERVICE_ADDR));
        // No manifest on the V2 wire → a version-1 manifest bound to the service address is synthesized.
        assertThat(verified.manifest().version()).isEqualTo(1L);
        assertThat(verified.manifest().serviceAddress()).isEqualTo(Bytes.wrap(SERVICE_ADDR));
        assertThat(verified.manifest().endpoints()).isEmpty();
    }

    @Test
    @DisplayName("Verifier dispatch suppresses its child fee but allows EVM gas collection")
    void verifierDispatchAllowsEvmGasCollection() {
        givenManifestEnabled(false);
        givenDispatchReturns(v2Return("295", SERVICE_ADDR));

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
    @DisplayName("verifyBundle V3: nextMessageId == 0 sentinel decodes to a manifest-only content (null metadata)")
    void verifyBundleV3ManifestOnlySentinelDecodesToNullMetadata() {
        givenManifestEnabled(true);
        givenDispatchReturns(bundleV3Return(0L, 7L, SERVICE_ADDR));

        final var content = subject.verifyBundle(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context);

        // Metadata-absent sentinel → null metadata so ClprSubmitBundleHandler takes its state-update-only path.
        assertThat(content.metadata()).isNull();
        assertThat(content.messages()).isEmpty();
        assertThat(content.newEndpointManifest()).isNotNull();
        assertThat(content.newEndpointManifest().version()).isEqualTo(7L);
        assertThat(content.newEndpointManifest().serviceAddress()).isEqualTo(Bytes.wrap(SERVICE_ADDR));
    }

    @Test
    @DisplayName("verifyBundle V3: a normal bundle (nextMessageId >= 1) keeps its queue metadata")
    void verifyBundleV3NormalBundleKeepsMetadata() {
        givenManifestEnabled(true);
        // manifestVersion 0 = absent; nextMessageId 1 = a real (non-sentinel) bundle.
        givenDispatchReturns(bundleV3Return(1L, 0L, SERVICE_ADDR));

        final var content = subject.verifyBundle(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context);

        assertThat(content.metadata()).isNotNull();
        assertThat(content.metadata().nextMessageId()).isEqualTo(1L);
        assertThat(content.newEndpointManifest()).isNull();
    }

    @Test
    @DisplayName("verifyBundle V2: nextMessageId == 0 sentinel decodes to null metadata (trust-anchor rotation)")
    void verifyBundleV2AbsentMetadataSentinelDecodesToNullMetadata() {
        givenManifestEnabled(false);
        final byte[] newAnchor = {1, 2, 3};
        givenDispatchReturns(bundleV2Return(0L, newAnchor));

        final var content = subject.verifyBundle(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context);

        // Even off the manifest path, a metadata-absent V2 tuple (a trust-anchor rotation) decodes to null
        // metadata so ClprSubmitBundleHandler takes its state-update-only path.
        assertThat(content.metadata()).isNull();
        assertThat(content.messages()).isEmpty();
        assertThat(content.newTrustAnchor()).isEqualTo(Bytes.wrap(newAnchor));
    }

    @Test
    @DisplayName("verifyBundle V2: a normal bundle (nextMessageId >= 1) keeps its queue metadata")
    void verifyBundleV2NormalBundleKeepsMetadata() {
        givenManifestEnabled(false);
        givenDispatchReturns(bundleV2Return(1L, new byte[0]));

        final var content = subject.verifyBundle(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context);

        assertThat(content.metadata()).isNotNull();
        assertThat(content.metadata().nextMessageId()).isEqualTo(1L);
    }

    // ---- helpers ----

    private void givenManifestEnabled(final boolean enabled) {
        final Configuration cfg = HederaTestConfigBuilder.create()
                .withValue("clpr.endpointManifestEnabled", enabled)
                .getOrCreateConfig();
        given(context.configuration()).willReturn(cfg);
    }

    private void givenDispatchReturns(final Bytes evmResult) {
        lenient().when(context.payer()).thenReturn(PAYER);
        given(context.dispatch(any(DispatchOptions.class))).willReturn(dispatchResult);
        lenient().when(dispatchResult.status()).thenReturn(SUCCESS);
        lenient().when(dispatchResult.getEvmCallResult()).thenReturn(evmResult);
    }

    /** Encodes the V3 return: config fields (7-field throttles, no endpoints) + manifest struct. */
    private static Bytes v3Return(final String chainId, final byte[] serviceAddr, final long manifestVersion) {
        final Tuple throttles = Tuple.from(
                Long.valueOf(0L),
                BigInteger.ZERO,
                BigInteger.ZERO,
                Long.valueOf(0L),
                BigInteger.ZERO,
                Long.valueOf(0L),
                Long.valueOf(0L));
        final Tuple manifest = Tuple.of(BigInteger.valueOf(manifestVersion), serviceAddr, new Tuple[0]);
        final byte[] encoded = V3_RETURN
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

    /** Encodes the mainline V2 return: config fields (5-field throttles) + empty seedEndpoints. */
    private static Bytes v2Return(final String chainId, final byte[] serviceAddr) {
        final Tuple throttles =
                Tuple.of(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
        final byte[] encoded = V2_RETURN
                .encode(Tuple.from(
                        new byte[0],
                        chainId,
                        serviceAddr,
                        BigInteger.ZERO,
                        throttles,
                        new byte[0],
                        new byte[0],
                        new Tuple[0]))
                .array();
        return Bytes.wrap(encoded);
    }

    /**
     * Encodes the V3 bundle return: metadata tuple + empty messages/trust-anchor + manifest struct.
     * {@code nextMessageId == 0} is the metadata-absent sentinel; {@code manifestVersion == 0} means
     * "no manifest".
     */
    private static Bytes bundleV3Return(
            final long nextMessageId, final long manifestVersion, final byte[] serviceAddr) {
        final Tuple meta = Tuple.of(BigInteger.valueOf(nextMessageId), new byte[32], BigInteger.ZERO, new byte[32], 0);
        final Tuple manifest = Tuple.of(BigInteger.valueOf(manifestVersion), serviceAddr, new Tuple[0]);
        final byte[] encoded = BUNDLE_V3_RETURN
                .encode(Tuple.of(meta, new byte[0][], new byte[0], new byte[0], manifest))
                .array();
        return Bytes.wrap(encoded);
    }

    /**
     * Encodes the mainline V2 bundle return: metadata tuple + empty messages + trust-anchor + empty id.
     * {@code nextMessageId == 0} is the metadata-absent sentinel (a trust-anchor rotation).
     */
    private static Bytes bundleV2Return(final long nextMessageId, final byte[] newTrustAnchor) {
        final Tuple meta = Tuple.of(BigInteger.valueOf(nextMessageId), new byte[32], BigInteger.ZERO, new byte[32], 0);
        final byte[] encoded = BUNDLE_V2_RETURN
                .encode(Tuple.of(meta, new byte[0][], newTrustAnchor, new byte[0]))
                .array();
        return Bytes.wrap(encoded);
    }
}

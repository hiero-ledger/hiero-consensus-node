// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_SUBMIT_BUNDLE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_GAS_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_EVM_ADDRESS_BYTES;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_SERVICE_ACCOUNT_ID;
import static com.hedera.node.app.spi.fees.NoopFeeCharging.DISPATCH_ONLY_NOOP_FEE_CHARGING;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.CLPR_DISPATCH;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.CLPR_VERIFIER;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.CONTRACT_GAS_USAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.clpr.ClprSubmitBundleTransactionBody;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.clpr.impl.ClprGasAccounting;
import com.hedera.node.app.service.token.records.HookDispatchStreamBuilder;
import com.hedera.node.app.spi.fees.ExchangeRateInfo;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.ResourcePriceCalculator;
import com.hedera.node.app.spi.workflows.ClprDispatchMetadata;
import com.hedera.node.app.spi.workflows.ContractGasUsage;
import com.hedera.node.app.spi.workflows.DispatchOptions;
import com.hedera.node.app.spi.workflows.FunctionalityResourcePrices;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.math.BigInteger;
import java.time.Instant;
import java.util.HashMap;
import java.util.stream.Stream;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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

    @Mock(extraInterfaces = FeeContext.class)
    private HandleContext context;

    @Mock
    private HookDispatchStreamBuilder dispatchResult;

    private final EvmClprVerifier subject = new EvmClprVerifier(VERIFIER);

    @Test
    @DisplayName("returns the proven config and the state-proven manifest from the tuple return")
    void verifyConfigWithManifestReturnsProvenConfigAndManifest() {
        givenConfig();
        givenDispatchReturns(configWithManifestReturn("295", SERVICE_ADDR, 3L));
        givenDispatchGasUsed(100_000L);

        final var verified = subject.verifyConfig(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context);

        assertThat(verified.config().chainId()).isEqualTo("295");
        assertThat(verified.config().serviceAddress()).isEqualTo(Bytes.wrap(SERVICE_ADDR));
        assertThat(verified.manifest().version()).isEqualTo(3L);
        assertThat(verified.verifierGasUsed()).isEqualTo(100_000L);
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
    @DisplayName("Verifier dispatch treats execution gas as prepaid by the native parent")
    void verifierDispatchTreatsGasAsPrepaid() {
        givenConfig();
        givenDispatchReturns(configWithManifestReturn("295", SERVICE_ADDR, 1L));

        subject.verifyConfig(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context);

        final var optionsCaptor = ArgumentCaptor.forClass(DispatchOptions.class);
        verify(context).dispatch(optionsCaptor.capture());
        final var options = optionsCaptor.getValue();
        assertThat(options.customFeeCharging()).isSameAs(DISPATCH_ONLY_NOOP_FEE_CHARGING);
        assertThat(options.dispatchMetadata().getMetadata(CLPR_VERIFIER, Boolean.class))
                .contains(true);
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
        verify(context, never()).tryToChargePayer(anyLong());
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

    @ParameterizedTest
    @CsvSource({"100000,100000", "500000,300000"})
    void chargesMeasuredBundleVerifierGasUpToTheCap(final long gasUsed, final long gasCharged) {
        final var payload = Bytes.wrap(new byte[100]);
        final long gasLimit = 500_000L;
        final long gasPrice = 21L;
        givenPricedConfig(true, gasLimit);
        givenDispatchReturns(bundleWithManifestReturn(1L, 0L, SERVICE_ADDR));
        givenDispatchGasUsed(gasUsed);
        given(context.simpleFeesSchedule())
                .willReturn(FeeSchedule.newBuilder()
                        .extras(makeExtraDef(Extra.GAS, 852L), makeExtraDef(Extra.PROCESSING_BYTES, 10_000L))
                        .services(makeService(
                                "Clpr",
                                makeServiceFee(
                                        CLPR_SUBMIT_BUNDLE,
                                        100_000_000L,
                                        makeExtraIncluded(Extra.PROCESSING_BYTES, 0))))
                        .build());
        given(context.body())
                .willReturn(TransactionBody.newBuilder()
                        .transactionFee(2_000_000_000L)
                        .clprSubmitBundle(
                                ClprSubmitBundleTransactionBody.newBuilder().bundlePayload(payload))
                        .build());
        final var streamBuilder = context.savepointStack().getBaseBuilder(StreamBuilder.class);
        given(streamBuilder.isUserDispatch()).willReturn(true);
        given(streamBuilder.transactionFee()).willReturn(100_000_000L);

        subject.verifyBundle(payload, Bytes.EMPTY, Bytes.EMPTY, context);
        ClprGasAccounting.refundUnusedGas(context);

        verify(context).tryToCharge(PAYER, gasLimit * gasPrice);
        verify(context).refundHandlerCharge(PAYER, (gasLimit - gasCharged) * gasPrice);
        verify(context, never()).tryToChargePayer(anyLong());
        verify(context, never()).refundServiceFee(any(), anyLong());
    }

    @Test
    void verifyBundleLocallyEncodesTheSameVerifierCallWithoutDispatch() {
        final var bundlePayload = Bytes.wrap(new byte[33]);
        final var trustAnchor = Bytes.fromHex("abcd");
        final var channelContext = Bytes.wrap(new byte[52]);
        final var expectedCall = Bytes.wrap(new Function("verifyBundle(bytes,bytes,bytes)", "()")
                .encodeCall(
                        Tuple.of(bundlePayload.toByteArray(), trustAnchor.toByteArray(), channelContext.toByteArray()))
                .array());

        final var content = subject.verifyBundleLocally(bundlePayload, trustAnchor, channelContext, callData -> {
            assertThat(callData).isEqualTo(expectedCall);
            return bundleWithManifestReturn(1L, 0L, SERVICE_ADDR, 9L);
        });

        assertThat(content.metadata().endpointManifestVersion()).isEqualTo(9L);
        verifyNoInteractions(context, dispatchResult);
    }

    @Test
    void verifyBundleLocallyDecodesTrustAnchorAndEndpointManifest() {
        final var trustAnchor = Bytes.fromHex("aabbcc");
        final var trustAnchorId = Bytes.fromHex("ddeeff");
        final var endpoint = Tuple.of("127.0.0.1", 50211L, new byte[] {1, 2}, new byte[] {3, 4});
        final var manifest = Tuple.of(BigInteger.valueOf(7L), SERVICE_ADDR, new Tuple[] {endpoint});
        final var rawReturn = Bytes.wrap(BUNDLE_RETURN
                .encode(Tuple.of(
                        ClprVerifierAbi.absentMetadataTuple(),
                        new byte[0][],
                        trustAnchor.toByteArray(),
                        trustAnchorId.toByteArray(),
                        manifest))
                .array());

        final var content = subject.verifyBundleLocally(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, ignored -> rawReturn);

        assertThat(content.metadata()).isNull();
        assertThat(content.newTrustAnchor()).isEqualTo(trustAnchor);
        assertThat(content.newTrustAnchorId()).isEqualTo(trustAnchorId);
        final var decodedManifest = content.newEndpointManifestOrThrow();
        assertThat(decodedManifest.version()).isEqualTo(7L);
        assertThat(decodedManifest.serviceAddress()).isEqualTo(Bytes.wrap(SERVICE_ADDR));
        assertThat(decodedManifest.endpoints()).hasSize(1);
        final var decodedEndpoint = decodedManifest.endpoints().getFirst();
        assertThat(decodedEndpoint.serviceEndpointOrThrow().ipAddress()).isEqualTo("127.0.0.1");
        assertThat(decodedEndpoint.serviceEndpointOrThrow().port()).isEqualTo(50211);
        assertThat(decodedEndpoint.tlsCertificate()).isEqualTo(Bytes.fromHex("0102"));
        assertThat(decodedEndpoint.accountId()).isEqualTo(Bytes.fromHex("0304"));
        verifyNoInteractions(context, dispatchResult);
    }

    @ParameterizedTest
    @MethodSource("invalidLocalEvmResults")
    void verifyBundleLocallyRejectsMissingOrMalformedResults(final Bytes result) {
        assertThatThrownBy(() -> subject.verifyBundleLocally(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, ignored -> result))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_BUNDLE_VERIFICATION_FAILED));
        verifyNoInteractions(context, dispatchResult);
    }

    private static Stream<Bytes> invalidLocalEvmResults() {
        return Stream.of(null, Bytes.EMPTY, Bytes.fromHex("ffff"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void verifierGasIsPricedAndUnusedGasRefunded(final boolean configCall) {
        givenPricedConfig(true);
        givenDispatchReturns(
                configCall
                        ? configWithManifestReturn("295", SERVICE_ADDR, 1L)
                        : bundleWithManifestReturn(1L, 0L, SERVICE_ADDR));
        givenDispatchGasUsed(400L);

        if (configCall) {
            subject.verifyConfig(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context);
        } else {
            subject.verifyBundle(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context);
        }

        ClprGasAccounting.refundUnusedGas(context);
        verify(context).tryToCharge(PAYER, 21_000L);
        verify(context).refundHandlerCharge(PAYER, 12_600L);
    }

    @Test
    void revertedVerifierStillPaysForActualGas() {
        givenPricedConfig(true);
        givenDispatchReturns(Bytes.EMPTY);
        given(dispatchResult.status()).willReturn(CONTRACT_REVERT_EXECUTED);
        givenDispatchGasUsed(400L);

        assertThatThrownBy(() -> subject.verifyConfig(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_VERIFIER_CONFIG_FAILED));
        verify(context).tryToCharge(PAYER, 21_000L);
        verify(context, never()).refundHandlerCharge(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void underfundedVerifierIsNotDispatched() {
        givenPricedConfig(false);

        assertThatThrownBy(() -> subject.verifyConfig(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INSUFFICIENT_PAYER_BALANCE));
        verify(context, never()).dispatch(any(DispatchOptions.class));
        verify(context, never()).refundHandlerCharge(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void verifierWithoutExecutionGasReportRefundsPrepayment() {
        givenPricedConfig(true);
        givenDispatchReturns(configWithManifestReturn("295", SERVICE_ADDR, 1L));

        subject.verifyConfig(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context);

        ClprGasAccounting.refundUnusedGas(context);
        verify(context).tryToCharge(PAYER, 21_000L);
        verify(context).refundHandlerCharge(PAYER, 21_000L);
    }

    @Test
    void verifierDispatchWithoutResultDoesNotPreserveAnyGasCharge() {
        givenPricedConfig(true);
        given(context.dispatch(any(DispatchOptions.class))).willReturn(null);

        assertThatThrownBy(() -> subject.verifyConfig(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_VERIFIER_CONFIG_FAILED));
        verify(context).tryToCharge(PAYER, 21_000L);
        verify(context, never()).refundHandlerCharge(any(), org.mockito.ArgumentMatchers.anyLong());
        final var failure = new HandleException(CLPR_VERIFIER_CONFIG_FAILED);
        assertThat(ClprGasAccounting.preservingChargesOnRollback(context, failure))
                .isSameAs(failure);
    }

    @Test
    void verifierDispatchFailureBeforeExecutionDoesNotPreserveAnyGasCharge() {
        givenPricedConfig(true);
        given(context.dispatch(any(DispatchOptions.class))).willThrow(new HandleException(MAX_CHILD_RECORDS_EXCEEDED));

        assertThatThrownBy(() -> subject.verifyConfig(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context))
                .isInstanceOf(HandleException.class)
                .has(responseCode(MAX_CHILD_RECORDS_EXCEEDED));
        verify(context).tryToCharge(PAYER, 21_000L);
        verify(context, never()).refundHandlerCharge(any(), org.mockito.ArgumentMatchers.anyLong());
        final var failure = new HandleException(MAX_CHILD_RECORDS_EXCEEDED);
        assertThat(ClprGasAccounting.preservingChargesOnRollback(context, failure))
                .isSameAs(failure);
    }

    @Test
    void zeroVerifierLimitReportsInsufficientGasInsteadOfBalance() {
        given(context.configuration())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("clpr.verifierGasLimit", 0L)
                        .getOrCreateConfig());

        assertThatThrownBy(() -> subject.verifyConfig(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INSUFFICIENT_GAS));
        verify(context, never()).dispatch(any(DispatchOptions.class));
        verify(context, never()).tryToCharge(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void oversizedVerifierLimitReportsGasLimitInsteadOfBalance() {
        given(context.configuration())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("clpr.verifierGasLimit", 1001L)
                        .withValue("contracts.maxGasPerTransaction", 1000L)
                        .getOrCreateConfig());

        assertThatThrownBy(() -> subject.verifyConfig(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context))
                .isInstanceOf(HandleException.class)
                .has(responseCode(MAX_GAS_LIMIT_EXCEEDED));
        verify(context, never()).dispatch(any(DispatchOptions.class));
        verify(context, never()).tryToCharge(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void realVerifierDispatchReservesItsAllowanceOnlyOnceFromCallbackBudget() {
        givenConfig();
        given(context.configuration())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("clpr.verifierGasLimit", 1000L)
                        .withValue("contracts.maxGasPerTransaction", 5000L)
                        .getOrCreateConfig());
        givenDispatchReturns(bundleWithManifestReturn(1L, 0L, SERVICE_ADDR));
        givenDispatchGasUsed(400L);

        subject.verifyBundle(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context);

        assertThat(ClprGasAccounting.forContext(context).availableApplicationGas(5000L))
                .isEqualTo(4000L);
    }

    @Test
    void exhaustedParentGasReportsGasLimitInsteadOfBalance() {
        givenConfig();
        given(context.configuration())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("clpr.verifierGasLimit", 1000L)
                        .withValue("contracts.maxGasPerTransaction", 1000L)
                        .getOrCreateConfig());
        givenDispatchReturns(bundleWithManifestReturn(1L, 0L, SERVICE_ADDR));
        subject.verifyBundle(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context);

        assertThatThrownBy(() -> subject.verifyBundle(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY, context))
                .isInstanceOf(HandleException.class)
                .has(responseCode(MAX_GAS_LIMIT_EXCEEDED));
        verify(context).dispatch(any(DispatchOptions.class));
    }

    private void givenPricedConfig(final boolean solvent) {
        givenPricedConfig(solvent, 1000L);
    }

    private void givenPricedConfig(final boolean solvent, final long gasLimit) {
        final var cfg = HederaTestConfigBuilder.create()
                .withValue("clpr.verifierGasLimit", gasLimit)
                .withValue("contracts.maxGasPerTransaction", gasLimit)
                .getOrCreateConfig();
        given(context.configuration()).willReturn(cfg);
        given(context.dispatchMetadata()).willReturn(new HandleContext.DispatchMetadata(new HashMap<>()));
        givenBaseBuilder();
        given(context.payer()).willReturn(PAYER);
        given(context.getGasPriceInTinycents()).willReturn(2L);
        final var prices = mock(ResourcePriceCalculator.class);
        given(context.resourcePriceCalculator()).willReturn(prices);
        given(prices.resourcePricesFor(CONTRACT_CALL, DEFAULT))
                .willReturn(new FunctionalityResourcePrices(FeeData.DEFAULT, 3L));
        final var rates = mock(ExchangeRateInfo.class);
        given(context.exchangeRateInfo()).willReturn(rates);
        given(context.consensusNow()).willReturn(Instant.EPOCH);
        given(rates.activeRate(Instant.EPOCH))
                .willReturn(ExchangeRate.newBuilder().hbarEquiv(7).centEquiv(2).build());
        given(context.tryToCharge(PAYER, gasLimit * 21L)).willReturn(solvent);
    }

    // ---- helpers ----

    private void givenConfig() {
        final Configuration cfg = HederaTestConfigBuilder.create().getOrCreateConfig();
        given(context.configuration()).willReturn(cfg);
        given(context.dispatchMetadata()).willReturn(new HandleContext.DispatchMetadata(new HashMap<>()));
    }

    private void givenBaseBuilder() {
        final var stack = mock(HandleContext.SavepointStack.class);
        given(context.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(StreamBuilder.class)).willReturn(mock(StreamBuilder.class));
    }

    private void givenDispatchReturns(final Bytes evmResult) {
        lenient().when(context.payer()).thenReturn(PAYER);
        lenient().when(context.dispatch(any(DispatchOptions.class))).thenReturn(dispatchResult);
        lenient().when(dispatchResult.status()).thenReturn(SUCCESS);
        lenient().when(dispatchResult.getEvmCallResult()).thenReturn(evmResult);
    }

    private void givenDispatchGasUsed(final long gasUsed) {
        given(context.dispatch(any(DispatchOptions.class))).willAnswer(invocation -> {
            final DispatchOptions<?> options = invocation.getArgument(0);
            options.dispatchMetadata()
                    .getMetadata(CONTRACT_GAS_USAGE, ContractGasUsage.class)
                    .orElseThrow()
                    .recordGasUsed(gasUsed);
            return dispatchResult;
        });
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

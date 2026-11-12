// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.calculator;

import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_CLOSE_CHANNEL;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_COMPLETE_CHANNEL;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_COMPLETE_CONNECTOR;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_DEREGISTER_CONNECTOR;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_ENDPOINT_PUBLICATION;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_REGISTER_CHANNEL;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_REGISTER_CONNECTOR;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_SUBMIT_BUNDLE;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_UPDATE_LEDGER_CONFIGURATION;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_STATE_BYTE_FEE_TINYCENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.clpr.ClprCompleteChannelTransactionBody;
import com.hedera.hapi.node.clpr.ClprSubmitBundleTransactionBody;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.clpr.impl.ClprGasAccounting;
import com.hedera.node.app.service.clpr.impl.calculator.ClprFeeCalculator;
import com.hedera.node.app.spi.fees.ExchangeRateInfo;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.ResourcePriceCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import com.hedera.node.app.spi.workflows.ContractGasUsage;
import com.hedera.node.app.spi.workflows.FunctionalityResourcePrices;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ClprFeeCalculatorTest {

    private static final long BASE_FEE_TINYCENTS = 100_000_000L;
    private static final long STATE_BYTE_FEE_TINYCENTS = 1_000_000L;
    private static final long PROCESSING_BYTE_FEE_TINYCENTS = 10_000L;
    private static final long GAS_FEE_TINYCENTS = 852L;
    private static final int INCLUDED_STATE_BYTES = 8_192;

    private static final TransactionBody SUBMIT_BUNDLE = TransactionBody.newBuilder()
            .clprSubmitBundle(ClprSubmitBundleTransactionBody.newBuilder()
                    .bundlePayload(Bytes.wrap(new byte[1_000]))
                    .build())
            .build();
    private static final TransactionBody COMPLETE_CHANNEL = TransactionBody.newBuilder()
            .clprCompleteChannel(ClprCompleteChannelTransactionBody.newBuilder()
                    .configProofBytes(Bytes.wrap(new byte[INCLUDED_STATE_BYTES]))
                    .endpointManifestProofBytes(Bytes.wrap(new byte[500]))
                    .build())
            .build();

    private final ClprFeeCalculator subject =
            new ClprFeeCalculator(CLPR_SUBMIT_BUNDLE, TransactionBody.DataOneOfType.CLPR_SUBMIT_BUNDLE);

    @Test
    @DisplayName("getTransactionType returns the discriminant supplied at construction")
    void returnsTransactionType() {
        assertThat(subject.getTransactionType()).isEqualTo(TransactionBody.DataOneOfType.CLPR_SUBMIT_BUNDLE);
    }

    @Test
    @DisplayName("accumulateServiceFee sets service base fee when schedule has a matching entry")
    void setsServiceBaseFeeWhenScheduleHasEntry() {
        final var feeResult = new FeeResult();
        final var feeSchedule = FeeSchedule.DEFAULT
                .copyBuilder()
                .services(makeService("ClprService", makeServiceFee(CLPR_SUBMIT_BUNDLE, 12_345L)))
                .build();

        subject.accumulateServiceFee(SUBMIT_BUNDLE, mock(SimpleFeeContext.class), feeResult, feeSchedule);

        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(12_345L);
    }

    @Test
    @DisplayName("accumulateServiceFee leaves fee unchanged when functionality not in schedule")
    void noOpWhenScheduleMissingEntry() {
        final var feeResult = new FeeResult();
        final var feeSchedule = FeeSchedule.DEFAULT.copyBuilder().build();

        subject.accumulateServiceFee(TransactionBody.DEFAULT, mock(SimpleFeeContext.class), feeResult, feeSchedule);

        assertThat(feeResult.getServiceTotalTinycents()).isZero();
    }

    @ParameterizedTest(name = "{0} accumulates only its base fee when the schedule lists no extras")
    @MethodSource("clprTransactionOps")
    @DisplayName("Each CLPR transaction op accumulates its base fee")
    void accumulatesBaseFeeForEveryClprOp(
            final HederaFunctionality functionality,
            final TransactionBody.DataOneOfType dataOneOfType,
            final TransactionBody body) {
        final var calculator = new ClprFeeCalculator(functionality, dataOneOfType);
        final var feeResult = new FeeResult();
        final var feeSchedule = FeeSchedule.DEFAULT
                .copyBuilder()
                .services(makeService("ClprService", makeServiceFee(functionality, BASE_FEE_TINYCENTS)))
                .build();

        calculator.accumulateServiceFee(body, mock(SimpleFeeContext.class), feeResult, feeSchedule);

        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(BASE_FEE_TINYCENTS);
    }

    @Test
    @DisplayName("ClprCompleteChannel bills verifier gas and stored bytes during handle")
    void completeChannelAccumulatesOnlyItsBaseFee() {
        final var calculator =
                new ClprFeeCalculator(CLPR_COMPLETE_CHANNEL, TransactionBody.DataOneOfType.CLPR_COMPLETE_CHANNEL);
        final var feeResult = new FeeResult();
        final var feeSchedule = scheduleWith(
                CLPR_COMPLETE_CHANNEL,
                makeExtraIncluded(Extra.STATE_BYTES, INCLUDED_STATE_BYTES),
                makeExtraIncluded(Extra.GAS, 0));

        calculator.accumulateServiceFee(COMPLETE_CHANNEL, mock(SimpleFeeContext.class), feeResult, feeSchedule);

        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(BASE_FEE_TINYCENTS);
    }

    @Test
    @DisplayName("ClprSubmitBundle accrues the CLPR rate for every payload byte before handle")
    void submitBundleChargesPayloadBytesButNoFixedVerifierGas() {
        final var feeResult = new FeeResult();
        final var feeSchedule = scheduleWith(
                CLPR_SUBMIT_BUNDLE, makeExtraIncluded(Extra.PROCESSING_BYTES, 0), makeExtraIncluded(Extra.GAS, 0));

        subject.accumulateServiceFee(SUBMIT_BUNDLE, mock(SimpleFeeContext.class), feeResult, feeSchedule);

        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(BASE_FEE_TINYCENTS + 1_000 * 1_000L);
    }

    @Test
    void completeChannelRecordsBaseStoredBytesAndCappedVerifierGasExactlyOnce() {
        final var fixture = new SettlementFixture(CLPR_COMPLETE_CHANNEL);
        final var accounting = ClprGasAccounting.forContext(fixture.context);
        assertThat(accounting.prepayVerifier(SettlementFixture.PAYER, SettlementFixture.VERIFIER_LIMIT))
                .isTrue();
        accounting.settleVerifierWithGasUsage(
                SettlementFixture.PAYER, SettlementFixture.VERIFIER_LIMIT, gasResult(350_000));

        ClprFeeCalculator.settleCompleteChannelFee(fixture.context, 350_000, SettlementFixture.STORED_BYTES);
        accounting.refundUnusedGas();

        final long storedByteFee =
                (SettlementFixture.STORED_BYTES - SettlementFixture.INCLUDED_BYTES) * SettlementFixture.STATE_RATE;
        assertThat(fixture.recordedFee)
                .hasValue(SettlementFixture.BASE_FEE + storedByteFee + 300_000 * SettlementFixture.GAS_RATE);
        verify(fixture.context).tryToChargePayer(storedByteFee);
    }

    @Test
    void bundleRecordsBasePayloadBytesAndCappedVerifierGasExactlyOnce() {
        final var fixture = new SettlementFixture(CLPR_SUBMIT_BUNDLE);
        final var accounting = ClprGasAccounting.forContext(fixture.context);
        assertThat(accounting.prepayVerifier(SettlementFixture.PAYER, SettlementFixture.VERIFIER_LIMIT))
                .isTrue();
        accounting.settleVerifierWithGasUsage(
                SettlementFixture.PAYER, SettlementFixture.VERIFIER_LIMIT, gasResult(350_000));

        ClprFeeCalculator.settleBundleVerifierFee(fixture.context, 350_000);
        accounting.refundUnusedGas();

        assertThat(fixture.recordedFee)
                .hasValue(SettlementFixture.BASE_FEE
                        + SettlementFixture.PAYLOAD_BYTES * SettlementFixture.PROCESSING_RATE
                        + 300_000 * SettlementFixture.GAS_RATE);
        verify(fixture.context, never()).tryToChargePayer(anyLong());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 100_000, 300_000, 450_000})
    void fallbackVerifierSettlementUsesGlobalGasRateAndCapsTheCharge(final long gasUsed) {
        final var fixture = new SettlementFixture(CLPR_SUBMIT_BUNDLE);

        ClprFeeCalculator.settleBundleVerifierFee(fixture.context, gasUsed);

        final long gasFee = Math.min(gasUsed, 300_000) * SettlementFixture.GAS_RATE;
        assertThat(fixture.recordedFee)
                .hasValue(SettlementFixture.BASE_FEE
                        + SettlementFixture.PAYLOAD_BYTES * SettlementFixture.PROCESSING_RATE
                        + gasFee);
        if (gasFee == 0) {
            verify(fixture.context, never()).tryToChargePayer(anyLong());
        } else {
            verify(fixture.context).tryToChargePayer(gasFee);
        }
        verify(fixture.context, never()).tryToCharge(any(AccountID.class), anyLong());
    }

    @Test
    void deferredStoredBytesMustFitTheRemainingSignedFee() {
        final var fixture = new SettlementFixture(CLPR_COMPLETE_CHANNEL);
        final long storedByteFee =
                (SettlementFixture.STORED_BYTES - SettlementFixture.INCLUDED_BYTES) * SettlementFixture.STATE_RATE;
        final var body = fixture.context
                .body()
                .copyBuilder()
                .transactionFee(SettlementFixture.BASE_FEE + storedByteFee - 1)
                .build();
        given(fixture.context.body()).willReturn(body);

        assertThatThrownBy(() ->
                        ClprFeeCalculator.settleCompleteChannelFee(fixture.context, 0, SettlementFixture.STORED_BYTES))
                .isInstanceOfSatisfying(
                        HandleException.class,
                        failure -> assertThat(failure.getStatus()).isEqualTo(INSUFFICIENT_TX_FEE));
        verify(fixture.context, never()).tryToChargePayer(anyLong());
    }

    private static ContractGasUsage gasResult(final long gasUsed) {
        final var result = new ContractGasUsage();
        result.recordGasUsed(gasUsed);
        return result;
    }

    private static final class SettlementFixture {
        private static final AccountID PAYER =
                AccountID.newBuilder().accountNum(1001).build();
        private static final Instant NOW = Instant.ofEpochSecond(1000);
        private static final long BASE_FEE = 1000;
        private static final long STATE_RATE = CLPR_STATE_BYTE_FEE_TINYCENTS;
        private static final long PROCESSING_RATE = 1_000L;
        private static final long GAS_RATE = 2;
        private static final long VERIFIER_LIMIT = 500_000;
        private static final int INCLUDED_BYTES = 8_192;
        private static final int STORED_BYTES = 9_000;
        private static final int PAYLOAD_BYTES = 1000;
        private final HandleContext context =
                mock(HandleContext.class, withSettings().extraInterfaces(FeeContext.class));
        private final AtomicLong recordedFee = new AtomicLong();

        private SettlementFixture(final HederaFunctionality functionality) {
            final var body = functionality == CLPR_COMPLETE_CHANNEL
                    ? TransactionBody.newBuilder()
                            .transactionFee(500_000_000)
                            .clprCompleteChannel(ClprCompleteChannelTransactionBody.DEFAULT)
                            .build()
                    : TransactionBody.newBuilder()
                            .transactionFee(500_000_000)
                            .clprSubmitBundle(ClprSubmitBundleTransactionBody.newBuilder()
                                    .bundlePayload(Bytes.wrap(new byte[PAYLOAD_BYTES])))
                            .build();
            final var schedule = FeeSchedule.newBuilder()
                    .extras(
                            makeExtraDef(Extra.STATE_BYTES, STATE_BYTE_FEE_TINYCENTS),
                            makeExtraDef(Extra.PROCESSING_BYTES, PROCESSING_BYTE_FEE_TINYCENTS),
                            makeExtraDef(Extra.GAS, GAS_RATE))
                    .services(makeService(
                            "Clpr",
                            makeServiceFee(
                                    functionality,
                                    BASE_FEE,
                                    makeExtraIncluded(Extra.STATE_BYTES, INCLUDED_BYTES),
                                    makeExtraIncluded(Extra.PROCESSING_BYTES, 0))))
                    .build();
            final var upfront = new FeeResult();
            new ClprFeeCalculator(functionality, body.data().kind())
                    .accumulateServiceFee(body, mock(SimpleFeeContext.class), upfront, schedule);
            recordedFee.set(upfront.getServiceTotalTinycents());

            given(context.payer()).willReturn(PAYER);
            given(context.body()).willReturn(body);
            given(context.simpleFeesSchedule()).willReturn(schedule);
            given(context.dispatchMetadata()).willReturn(new HandleContext.DispatchMetadata(new HashMap<>()));
            given(context.configuration())
                    .willReturn(HederaTestConfigBuilder.create()
                            .withValue("contracts.maxGasPerTransaction", 1_000_000L)
                            .withValue("clpr.verifierGasLimit", VERIFIER_LIMIT)
                            .getOrCreateConfig());
            final var fees = (FeeContext) context;
            given(fees.tinybarsFromTinycents(anyLong())).willAnswer(invocation -> invocation.getArgument(0));
            final var stack = mock(HandleContext.SavepointStack.class);
            final var builder = mock(StreamBuilder.class);
            given(context.savepointStack()).willReturn(stack);
            given(stack.getBaseBuilder(StreamBuilder.class)).willReturn(builder);
            given(builder.isUserDispatch()).willReturn(true);
            given(builder.transactionFee()).willAnswer(_ -> recordedFee.get());
            final var prices = mock(ResourcePriceCalculator.class);
            given(context.resourcePriceCalculator()).willReturn(prices);
            final var unitPrices = new FunctionalityResourcePrices(FeeData.DEFAULT, 1);
            given(prices.resourcePricesFor(functionality, DEFAULT)).willReturn(unitPrices);
            given(prices.resourcePricesFor(CONTRACT_CALL, DEFAULT)).willReturn(unitPrices);
            given(context.getGasPriceInTinycents()).willReturn(GAS_RATE);
            final var rates = mock(ExchangeRateInfo.class);
            given(context.exchangeRateInfo()).willReturn(rates);
            given(context.consensusNow()).willReturn(NOW);
            given(rates.activeRate(NOW))
                    .willReturn(
                            ExchangeRate.newBuilder().hbarEquiv(1).centEquiv(1).build());
            given(context.tryToCharge(any(AccountID.class), anyLong())).willAnswer(invocation -> {
                recordedFee.addAndGet(invocation.getArgument(1));
                return true;
            });
            given(context.tryToChargePayer(anyLong())).willAnswer(invocation -> {
                recordedFee.addAndGet(invocation.getArgument(0));
                return true;
            });
            doAnswer(invocation -> {
                        recordedFee.addAndGet(-invocation.<Long>getArgument(1));
                        return null;
                    })
                    .when(context)
                    .refundHandlerCharge(any(AccountID.class), anyLong());
        }
    }

    private static FeeSchedule scheduleWith(
            final HederaFunctionality functionality, final org.hiero.hapi.support.fees.ExtraFeeReference... extras) {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(
                        makeExtraDef(Extra.STATE_BYTES, STATE_BYTE_FEE_TINYCENTS),
                        makeExtraDef(Extra.PROCESSING_BYTES, PROCESSING_BYTE_FEE_TINYCENTS),
                        makeExtraDef(Extra.GAS, GAS_FEE_TINYCENTS))
                .services(makeService("ClprService", makeServiceFee(functionality, BASE_FEE_TINYCENTS, extras)))
                .build();
    }

    /** All CLPR transaction functionalities paired with their TransactionBody discriminants and a body. */
    private static Stream<Arguments> clprTransactionOps() {
        return Stream.of(
                Arguments.of(
                        CLPR_UPDATE_LEDGER_CONFIGURATION,
                        TransactionBody.DataOneOfType.CLPR_UPDATE_LEDGER_CONFIGURATION,
                        TransactionBody.DEFAULT),
                Arguments.of(
                        CLPR_REGISTER_CHANNEL,
                        TransactionBody.DataOneOfType.CLPR_REGISTER_CHANNEL,
                        TransactionBody.DEFAULT),
                Arguments.of(
                        CLPR_COMPLETE_CHANNEL, TransactionBody.DataOneOfType.CLPR_COMPLETE_CHANNEL, COMPLETE_CHANNEL),
                Arguments.of(
                        CLPR_CLOSE_CHANNEL, TransactionBody.DataOneOfType.CLPR_CLOSE_CHANNEL, TransactionBody.DEFAULT),
                Arguments.of(
                        CLPR_REGISTER_CONNECTOR,
                        TransactionBody.DataOneOfType.CLPR_REGISTER_CONNECTOR,
                        TransactionBody.DEFAULT),
                Arguments.of(
                        CLPR_COMPLETE_CONNECTOR,
                        TransactionBody.DataOneOfType.CLPR_COMPLETE_CONNECTOR,
                        TransactionBody.DEFAULT),
                Arguments.of(
                        CLPR_DEREGISTER_CONNECTOR,
                        TransactionBody.DataOneOfType.CLPR_DEREGISTER_CONNECTOR,
                        TransactionBody.DEFAULT),
                Arguments.of(
                        CLPR_ENDPOINT_PUBLICATION,
                        TransactionBody.DataOneOfType.CLPR_ENDPOINT_PUBLICATION,
                        TransactionBody.DEFAULT),
                Arguments.of(CLPR_SUBMIT_BUNDLE, TransactionBody.DataOneOfType.CLPR_SUBMIT_BUNDLE, SUBMIT_BUNDLE));
    }
}

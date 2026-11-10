// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.clpr.ClprCompleteChannelTransactionBody;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.clpr.impl.ClprGasAccounting;
import com.hedera.node.app.spi.fees.ExchangeRateInfo;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.fees.ResourcePriceCalculator;
import com.hedera.node.app.spi.workflows.FunctionalityResourcePrices;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ClprGasAccountingTest {
    private static final AccountID PARENT = account(1001);
    private static final AccountID CONNECTOR_A = account(1002);
    private static final AccountID CONNECTOR_B = account(1003);
    private static final long TOTAL_GAS = 1_000;
    private static final long GAS_PRICE = 6;
    private static final long INITIAL_BALANCE = 20_000;
    private static final Instant NOW = Instant.ofEpochSecond(1_000);
    private HandleContext context;
    private final Map<AccountID, Long> balances = new HashMap<>();
    private final List<String> payments = new ArrayList<>();

    @BeforeEach
    void setUp() {
        context = mock(HandleContext.class);
        given(context.payer()).willReturn(PARENT);
        given(context.configuration())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("contracts.maxGasPerTransaction", TOTAL_GAS)
                        .withValue("clpr.verifierGasLimit", 100L)
                        .getOrCreateConfig());
        given(context.dispatchMetadata()).willReturn(new DispatchMetadata(new HashMap<>()));
        given(context.getGasPriceInTinycents()).willReturn(5L);
        final var prices = mock(ResourcePriceCalculator.class);
        given(context.resourcePriceCalculator()).willReturn(prices);
        given(prices.resourcePricesFor(CONTRACT_CALL, DEFAULT))
                .willReturn(new FunctionalityResourcePrices(FeeData.DEFAULT, 3L));
        final var rates = mock(ExchangeRateInfo.class);
        given(context.exchangeRateInfo()).willReturn(rates);
        given(context.consensusNow()).willReturn(NOW);
        given(rates.activeRate(NOW))
                .willReturn(ExchangeRate.newBuilder().hbarEquiv(2).centEquiv(5).build());
        balances.put(PARENT, INITIAL_BALANCE);
        balances.put(CONNECTOR_A, 0L);
        balances.put(CONNECTOR_B, 0L);
        given(context.tryToCharge(any(AccountID.class), anyLong())).willAnswer(invocation -> {
            final AccountID payer = invocation.getArgument(0);
            final long amount = invocation.getArgument(1);
            final long charged = Math.min(balances.getOrDefault(payer, 0L), amount);
            balances.merge(payer, -charged, Long::sum);
            payments.add(payer.accountNumOrThrow() + ":" + charged);
            return charged == amount;
        });
        doAnswer(invocation -> {
                    final AccountID payer = invocation.getArgument(0);
                    final long amount = invocation.getArgument(1);
                    balances.merge(payer, amount, Long::sum);
                    return null;
                })
                .when(context)
                .refundHandlerCharge(any(AccountID.class), anyLong());
    }

    @Test
    void completionReservesOnlyVerifierAllowanceInsteadOfFullTransactionBudget() {
        given(context.body())
                .willReturn(TransactionBody.newBuilder()
                        .clprCompleteChannel(ClprCompleteChannelTransactionBody.DEFAULT)
                        .build());
        final var subject = ClprGasAccounting.forContext(context);

        assertThat(subject.prepayVerifier(PARENT, 100)).isTrue();
        verify(context).tryToCharge(PARENT, 600L);
        assertThat(subject.settle(PARENT, 100, gasResult(40))).isEqualTo(240L);
        subject.refundUnusedGas();
        verify(context).refundHandlerCharge(PARENT, 360L);
        assertThat(subject.availableApplicationGas(1)).isZero();
    }

    @Test
    void sharesOneUpfrontParentEscrowAcrossVerifierAndConnectors() {
        final var subject = ClprGasAccounting.forContext(context);
        assertThat(ClprGasAccounting.forContext(context)).isSameAs(subject);
        assertThat(subject.costForGas(11)).isEqualTo(66);
        assertThat(subject.reserveParentGas()).isTrue();
        assertThat(subject.reserveParentGas()).isTrue();
        assertThat(subject.prepayVerifier(PARENT, 100)).isTrue();
        assertThat(subject.settle(PARENT, 100, gasResult(80))).isEqualTo(80 * GAS_PRICE);
        assertThat(subject.prepay(CONNECTOR_A, 600)).isTrue();
        assertThat(subject.settle(CONNECTOR_A, 600, gasResult(100))).isEqualTo(100 * GAS_PRICE);
        assertThat(subject.prepay(CONNECTOR_B, 300)).isTrue();
        assertThat(subject.settle(CONNECTOR_B, 300, gasResult(120))).isEqualTo(120 * GAS_PRICE);
        assertThat(subject.availableApplicationGas(1)).isZero();
        assertThat(subject.prepay(CONNECTOR_A, 1)).isFalse();
        assertThat(payments).containsExactly("1001:6000");
        verify(context, never()).refundHandlerCharge(any(), anyLong());
        subject.refundUnusedGas();
        subject.refundUnusedGas();
        verify(context).refundHandlerCharge(PARENT, 700 * GAS_PRICE);
        assertThat(balances.get(PARENT)).isEqualTo(INITIAL_BALANCE - 300 * GAS_PRICE);
    }

    @Test
    void billingRefundsDoNotReopenTheDeclaredExecutionBudget() {
        final var subject = ClprGasAccounting.forContext(context);
        assertThat(subject.prepayVerifier(PARENT, 100)).isTrue();
        subject.settle(PARENT, 100, gasResult(80));
        assertThat(subject.availableApplicationGas(TOTAL_GAS)).isEqualTo(900);
        assertThat(subject.prepay(CONNECTOR_A, 900)).isTrue();
        subject.settle(CONNECTOR_A, 900, gasResult(720));
        assertThat(subject.availableApplicationGas(TOTAL_GAS)).isZero();
        assertThat(subject.prepay(CONNECTOR_B, 1)).isFalse();
        subject.refundUnusedGas();
        assertThat(balances.get(PARENT)).isEqualTo(INITIAL_BALANCE - 800 * GAS_PRICE);
    }

    @Test
    void missingResultsConsumeTheWholeReservationAndFee() {
        final var subject = ClprGasAccounting.forContext(context);
        assertThat(subject.prepayVerifier(PARENT, 100)).isTrue();
        assertThat(subject.settle(PARENT, 100, null)).isEqualTo(100 * GAS_PRICE);
        assertThat(subject.prepay(CONNECTOR_A, 900)).isTrue();
        assertThat(subject.settle(CONNECTOR_A, 900, mock(StreamBuilder.class))).isEqualTo(900 * GAS_PRICE);
        assertThat(subject.availableApplicationGas(1)).isZero();
        subject.refundUnusedGas();
        verify(context, never()).refundHandlerCharge(any(), anyLong());
    }

    @ParameterizedTest
    @EnumSource(
            value = ResponseCodeEnum.class,
            names = {"THROTTLED_AT_CONSENSUS", "CONSENSUS_GAS_EXHAUSTED"})
    void preExecutionThrottleBillsNoGas(final ResponseCodeEnum status) {
        final var subject = ClprGasAccounting.forContext(context);
        assertThat(subject.prepay(CONNECTOR_A, 400)).isTrue();
        final var result = mock(StreamBuilder.class);
        given(result.status()).willReturn(status);

        assertThat(subject.settle(CONNECTOR_A, 400, result)).isZero();
        subject.refundUnusedGas();

        verify(context).refundHandlerCharge(PARENT, TOTAL_GAS * GAS_PRICE);
        assertThat(balances.get(PARENT)).isEqualTo(INITIAL_BALANCE);
    }

    @ParameterizedTest
    @EnumSource(
            value = ResponseCodeEnum.class,
            names = {"THROTTLED_AT_CONSENSUS", "CONSENSUS_GAS_EXHAUSTED"})
    void throttleStatusWithContractResultStillBillsRecordedGas(final ResponseCodeEnum status) {
        final var subject = ClprGasAccounting.forContext(context);
        assertThat(subject.prepay(CONNECTOR_A, 400)).isTrue();
        final var result = gasResult(40);
        given(result.status()).willReturn(status);

        assertThat(subject.settle(CONNECTOR_A, 400, result)).isEqualTo(40 * GAS_PRICE);
        subject.refundUnusedGas();

        assertThat(balances.get(PARENT)).isEqualTo(INITIAL_BALANCE - 40 * GAS_PRICE);
    }

    @Test
    void rollbackCannotAvoidFeesByFundingAConnectorDuringEarlierCallbacks() {
        final var subject = ClprGasAccounting.forContext(context);
        assertThat(subject.reserveParentGas()).isTrue();
        balances.put(CONNECTOR_B, 3_000L);
        assertThat(subject.prepay(CONNECTOR_B, 400)).isTrue();
        subject.settle(CONNECTOR_B, 400, gasResult(400));
        final var original = new HandleException(
                CLPR_BUNDLE_VERIFICATION_FAILED, (fees, rollback) -> rollback.tryToCharge(PARENT, 1_000));
        final var failure = ClprGasAccounting.preservingChargesOnRollback(context, original);
        // The parent rollback restores its escrow and removes the application's funding of B.
        balances.put(CONNECTOR_B, 0L);
        balances.put(PARENT, INITIAL_BALANCE);
        payments.clear();
        failure.maybeReplay(mock(FeeCharging.Context.class), context);
        assertThat(payments).containsExactly("1001:2400", "1001:1000");
        assertThat(balances.get(PARENT)).isEqualTo(INITIAL_BALANCE - 3_400);
        assertThat(balances.get(CONNECTOR_B)).isZero();
    }

    @Test
    void insufficientParentEscrowPreventsExecutionEvenWhenConnectorCanPay() {
        balances.put(PARENT, 100L);
        balances.put(CONNECTOR_A, INITIAL_BALANCE);
        final var subject = ClprGasAccounting.forContext(context);
        assertThat(subject.reserveParentGas()).isFalse();
        assertThat(subject.prepay(CONNECTOR_A, 100)).isFalse();
    }

    @Test
    void fallbackVerifierAllowanceIsReservedExactlyOnce() {
        final var subject = ClprGasAccounting.forContext(context);
        assertThat(subject.reserveParentGas()).isTrue();
        assertThat(subject.availableApplicationGas(TOTAL_GAS)).isEqualTo(900);
        assertThat(subject.availableApplicationGas(TOTAL_GAS)).isEqualTo(900);
        assertThat(subject.prepay(CONNECTOR_A, 900)).isTrue();
        subject.settle(CONNECTOR_A, 900, null);
        assertThat(subject.availableApplicationGas(1)).isZero();
    }

    @Test
    void lookupOnlyCleanupDoesNotCreateEscrowForEarlyValidationFailure() {
        final var failure = new HandleException(CLPR_BUNDLE_VERIFICATION_FAILED);
        assertThat(ClprGasAccounting.preservingChargesOnRollback(context, failure))
                .isSameAs(failure);
        ClprGasAccounting.refundUnusedGas(context);
        assertThat(payments).isEmpty();
        verify(context, never()).refundHandlerCharge(any(), anyLong());
    }

    private static StreamBuilder gasResult(final long gasUsed) {
        final var result = mock(StreamBuilder.class);
        given(result.hasContractResult()).willReturn(true);
        given(result.getGasUsedForContractTxn()).willReturn(gasUsed);
        return result;
    }

    private static AccountID account(final long number) {
        return AccountID.newBuilder().accountNum(number).build();
    }
}

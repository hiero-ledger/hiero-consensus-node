// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.clpr.ClprCompleteChannelTransactionBody;
import com.hedera.hapi.node.clpr.ClprSubmitBundleTransactionBody;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.clpr.impl.ClprGasAccounting;
import com.hedera.node.app.spi.fees.ExchangeRateInfo;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.fees.ResourcePriceCalculator;
import com.hedera.node.app.spi.workflows.ContractGasUsage;
import com.hedera.node.app.spi.workflows.FunctionalityResourcePrices;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.RollbackAwareException;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClprGasAccountingTest {
    private static final AccountID PARENT = account(1001);
    private static final AccountID CONNECTOR_A = account(1002);
    private static final AccountID CONNECTOR_B = account(1003);
    private static final long TOTAL_GAS = 1_000;
    private static final long GAS_PRICE = 6;
    private static final long INITIAL_BALANCE = 20_000;
    private static final Instant NOW = Instant.ofEpochSecond(1_000);
    private HandleContext context;
    private StreamBuilder streamBuilder;
    private final AtomicLong recordedFee = new AtomicLong();
    private final Map<AccountID, Long> balances = new HashMap<>();
    private final List<String> payments = new ArrayList<>();

    @BeforeEach
    void setUp() {
        context = mock(HandleContext.class);
        given(context.payer()).willReturn(PARENT);
        given(context.body()).willReturn(TransactionBody.DEFAULT);
        final var stack = mock(HandleContext.SavepointStack.class);
        streamBuilder = mock(StreamBuilder.class);
        given(context.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(StreamBuilder.class)).willReturn(streamBuilder);
        given(streamBuilder.transactionFee()).willAnswer(_ -> recordedFee.get());
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
            recordedFee.addAndGet(charged);
            return charged == amount;
        });
        doAnswer(invocation -> {
                    final AccountID payer = invocation.getArgument(0);
                    final long amount = invocation.getArgument(1);
                    balances.merge(payer, amount, Long::sum);
                    recordedFee.addAndGet(-amount);
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
        assertThat(subject.settleVerifierWithGasUsage(PARENT, 100, gasResult(40)))
                .isEqualTo(240L);
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
        assertThat(subject.settleVerifierWithGasUsage(PARENT, 100, gasResult(80)))
                .isEqualTo(80 * GAS_PRICE);
        assertThat(subject.prepay(CONNECTOR_A, 600)).isTrue();
        assertThat(subject.settleWithGasUsage(CONNECTOR_A, 600, gasResult(100))).isEqualTo(100 * GAS_PRICE);
        assertThat(subject.prepay(CONNECTOR_B, 300)).isTrue();
        assertThat(subject.settleWithGasUsage(CONNECTOR_B, 300, gasResult(120))).isEqualTo(120 * GAS_PRICE);
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
        subject.settleVerifierWithGasUsage(PARENT, 100, gasResult(80));
        assertThat(subject.availableApplicationGas(TOTAL_GAS)).isEqualTo(900);
        assertThat(subject.prepay(CONNECTOR_A, 900)).isTrue();
        subject.settleWithGasUsage(CONNECTOR_A, 900, gasResult(720));
        assertThat(subject.availableApplicationGas(TOTAL_GAS)).isZero();
        assertThat(subject.prepay(CONNECTOR_B, 1)).isFalse();
        subject.refundUnusedGas();
        assertThat(balances.get(PARENT)).isEqualTo(INITIAL_BALANCE - 800 * GAS_PRICE);
    }

    @Test
    void preExecutionFailuresBillNoGasButStillConsumeTheDeclaredExecutionBudget() {
        final var subject = ClprGasAccounting.forContext(context);
        assertThat(subject.prepayVerifier(PARENT, 100)).isTrue();
        assertThat(subject.settleVerifierWithGasUsage(PARENT, 100, new ContractGasUsage()))
                .isZero();
        assertThat(subject.prepay(CONNECTOR_A, 900)).isTrue();
        assertThat(subject.settleWithGasUsage(CONNECTOR_A, 900, new ContractGasUsage()))
                .isZero();
        assertThat(subject.availableApplicationGas(1)).isZero();

        subject.refundUnusedGas();

        verify(context).refundHandlerCharge(PARENT, TOTAL_GAS * GAS_PRICE);
        assertThat(balances.get(PARENT)).isEqualTo(INITIAL_BALANCE);
        assertThat(recordedFee).hasValue(0);
    }

    @Test
    void rollbackCannotAvoidFeesByFundingAConnectorDuringEarlierCallbacks() {
        final var subject = ClprGasAccounting.forContext(context);
        assertThat(subject.reserveParentGas()).isTrue();
        balances.put(CONNECTOR_B, 3_000L);
        assertThat(subject.prepay(CONNECTOR_B, 400)).isTrue();
        subject.settleWithGasUsage(CONNECTOR_B, 400, gasResult(400));
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
        subject.settleWithGasUsage(CONNECTOR_A, 900, new ContractGasUsage());
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

    @Test
    void bundleReservesTheSharedBudgetWhenVerifierLimitIsSmaller() {
        given(context.body())
                .willReturn(TransactionBody.newBuilder()
                        .clprSubmitBundle(ClprSubmitBundleTransactionBody.DEFAULT)
                        .build());
        final var subject = ClprGasAccounting.forContext(context);

        assertThat(subject.prepayVerifier(PARENT, 100)).isTrue();
        subject.settleVerifierWithGasUsage(PARENT, 100, gasResult(40));

        verify(context).tryToCharge(PARENT, TOTAL_GAS * GAS_PRICE);
        assertThat(subject.availableApplicationGas(TOTAL_GAS)).isEqualTo(900);
    }

    @Test
    void verifierBillingCeilingIsSharedAcrossAllVerificationCallsWithoutCappingCallbackGas() {
        given(context.configuration())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("contracts.maxGasPerTransaction", 1_000_000L)
                        .withValue("clpr.verifierGasLimit", 400_000L)
                        .getOrCreateConfig());
        balances.put(PARENT, 10_000_000L);
        final var subject = ClprGasAccounting.forContext(context);

        assertThat(subject.prepayVerifier(PARENT, 400_000)).isTrue();
        assertThat(subject.settleVerifierWithGasUsage(PARENT, 400_000, gasResult(250_000)))
                .isEqualTo(250_000 * GAS_PRICE);
        assertThat(subject.prepayVerifier(PARENT, 400_000)).isTrue();
        assertThat(subject.settleVerifierWithGasUsage(PARENT, 400_000, gasResult(200_000)))
                .isEqualTo(50_000 * GAS_PRICE);
        assertThat(subject.availableApplicationGas(1_000_000)).isEqualTo(200_000);
        assertThat(subject.prepay(CONNECTOR_A, 200_000)).isTrue();
        assertThat(subject.settleWithGasUsage(CONNECTOR_A, 200_000, gasResult(100_000)))
                .isEqualTo(100_000 * GAS_PRICE);

        subject.refundUnusedGas();

        assertThat(recordedFee).hasValue((300_000 + 100_000) * GAS_PRICE);
        assertThat(balances.get(PARENT)).isEqualTo(10_000_000L - 400_000 * GAS_PRICE);
        assertThat(subject.availableApplicationGas(1)).isZero();
    }

    @Test
    void rejectsSignedOfferThatDoesNotCoverBaseBytesAndGasEscrowBeforeCharging() {
        final long baseFee = 200;
        final long byteFee = 100;
        givenUserDispatch(baseFee + byteFee, baseFee + byteFee + TOTAL_GAS * GAS_PRICE - 1);
        final var subject = ClprGasAccounting.forContext(context);

        assertThatThrownBy(() -> subject.prepayVerifier(PARENT, 100))
                .isInstanceOfSatisfying(
                        HandleException.class,
                        failure -> assertThat(failure.getStatus()).isEqualTo(INSUFFICIENT_TX_FEE));

        assertThat(payments).isEmpty();
        assertThat(recordedFee).hasValue(baseFee + byteFee);
        assertThat(balances.get(PARENT)).isEqualTo(INITIAL_BALANCE - baseFee - byteFee);
    }

    @Test
    void reservingRollbackFeeRejectsAnInsufficientOfferWithoutCollectingThePenalty() {
        final long alreadyCharged = 300;
        final long penalty = 1_000;
        givenUserDispatch(alreadyCharged, alreadyCharged + penalty - 1);
        final var subject = ClprGasAccounting.forContext(context);

        assertThatThrownBy(() -> subject.reserveRollbackFee(penalty))
                .isInstanceOfSatisfying(
                        HandleException.class,
                        failure -> assertThat(failure.getStatus()).isEqualTo(INSUFFICIENT_TX_FEE));

        assertThat(payments).isEmpty();
        assertThat(recordedFee).hasValue(alreadyCharged);
        assertThat(balances.get(PARENT)).isEqualTo(INITIAL_BALANCE - alreadyCharged);
    }

    @Test
    void gasEscrowRequiresSignedHeadroomForTheFullPossiblePenalty() {
        final long alreadyCharged = 300;
        final long penalty = 1_000;
        givenUserDispatch(alreadyCharged, alreadyCharged + TOTAL_GAS * GAS_PRICE + penalty - 1);
        final var subject = ClprGasAccounting.forContext(context);
        subject.reserveRollbackFee(penalty);

        assertThatThrownBy(() -> subject.prepayVerifier(PARENT, 100))
                .isInstanceOfSatisfying(
                        HandleException.class,
                        failure -> assertThat(failure.getStatus()).isEqualTo(INSUFFICIENT_TX_FEE));

        assertThat(payments).isEmpty();
        assertThat(recordedFee).hasValue(alreadyCharged);
    }

    @Test
    void successfulFinalRecordFitsBaseBytesGasAndFullPenaltyAtTheSignedOfferBoundary() {
        final long alreadyCharged = 300;
        final long penalty = 1_000;
        final long offered = alreadyCharged + TOTAL_GAS * GAS_PRICE + penalty;
        givenUserDispatch(alreadyCharged, offered);
        final var subject = ClprGasAccounting.forContext(context);
        subject.reserveRollbackFee(penalty);
        assertThat(payments).isEmpty();
        assertThat(subject.prepayVerifier(PARENT, 100)).isTrue();
        subject.settleVerifierWithGasUsage(PARENT, 100, gasResult(100));
        assertThat(subject.prepay(CONNECTOR_A, 900)).isTrue();
        subject.settleWithGasUsage(CONNECTOR_A, 900, gasResult(900));

        assertThat(context.tryToCharge(PARENT, penalty)).isTrue();
        subject.refundUnusedGas();

        assertThat(recordedFee).hasValue(offered);
        assertThat(balances.get(PARENT)).isEqualTo(INITIAL_BALANCE - offered);
        assertThat(payments).containsExactly("1001:6000", "1001:1000");
    }

    @Test
    void rollbackFinalRecordFitsRechargedBaseBytesGasAndFullPenaltyAtTheSignedOfferBoundary() {
        final long alreadyCharged = 300;
        final long penalty = 1_000;
        final long offered = alreadyCharged + TOTAL_GAS * GAS_PRICE + penalty;
        givenUserDispatch(alreadyCharged, offered);
        final var subject = ClprGasAccounting.forContext(context);
        subject.reserveRollbackFee(penalty);
        assertThat(subject.prepayVerifier(PARENT, 100)).isTrue();
        subject.settleVerifierWithGasUsage(PARENT, 100, gasResult(100));
        assertThat(subject.prepay(CONNECTOR_A, 900)).isTrue();
        subject.settleWithGasUsage(CONNECTOR_A, 900, gasResult(900));
        final var original = new HandleException(
                CLPR_BUNDLE_VERIFICATION_FAILED, (fees, rollback) -> rollback.tryToCharge(PARENT, penalty));
        final var failure = subject.preservingChargesOnRollback(original);
        recordedFee.set(alreadyCharged);
        balances.put(PARENT, INITIAL_BALANCE - alreadyCharged);
        payments.clear();

        failure.maybeReplay(mock(FeeCharging.Context.class), context);

        assertThat(recordedFee).hasValue(offered);
        assertThat(balances.get(PARENT)).isEqualTo(INITIAL_BALANCE - offered);
        assertThat(payments).containsExactly("1001:6000", "1001:1000");
    }

    @Test
    void successfulFinalRecordIncludesBaseBytesAndActualGasWithinSignedOffer() {
        final long alreadyCharged = 300;
        final long offered = alreadyCharged + TOTAL_GAS * GAS_PRICE;
        givenUserDispatch(alreadyCharged, offered);
        final var subject = ClprGasAccounting.forContext(context);
        assertThat(subject.prepayVerifier(PARENT, 100)).isTrue();
        assertThat(recordedFee).hasValue(offered);
        subject.settleVerifierWithGasUsage(PARENT, 100, gasResult(80));
        assertThat(subject.prepay(CONNECTOR_A, 400)).isTrue();
        subject.settleWithGasUsage(CONNECTOR_A, 400, gasResult(300));

        subject.refundUnusedGas();

        final long actualFee = alreadyCharged + (80 + 300) * GAS_PRICE;
        assertThat(recordedFee).hasValue(actualFee);
        assertThat(recordedFee.get()).isLessThanOrEqualTo(offered);
        assertThat(balances.get(PARENT)).isEqualTo(INITIAL_BALANCE - actualFee);
    }

    @Test
    void rollbackFinalRecordIncludesRechargedBaseBytesAndActualGasWithinSignedOffer() {
        final long alreadyCharged = 300;
        final long offered = alreadyCharged + TOTAL_GAS * GAS_PRICE;
        givenUserDispatch(alreadyCharged, offered);
        final var subject = ClprGasAccounting.forContext(context);
        assertThat(subject.prepayVerifier(PARENT, 100)).isTrue();
        subject.settleVerifierWithGasUsage(PARENT, 100, gasResult(80));
        final var failure = ClprGasAccounting.preservingChargesOnRollback(
                context, new HandleException(CLPR_BUNDLE_VERIFICATION_FAILED));
        // The workflow rolls back escrow and recharges the original base/byte fee before replay.
        recordedFee.set(alreadyCharged);
        balances.put(PARENT, INITIAL_BALANCE - alreadyCharged);
        payments.clear();

        failure.maybeReplay(mock(FeeCharging.Context.class), context);

        final long actualFee = alreadyCharged + 80 * GAS_PRICE;
        assertThat(recordedFee).hasValue(actualFee);
        assertThat(recordedFee.get()).isLessThanOrEqualTo(offered);
        assertThat(balances.get(PARENT)).isEqualTo(INITIAL_BALANCE - actualFee);
        assertThat(payments).containsExactly("1001:480");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void intentionallyFreeContractExecutionDoesNotLookUpMissingGasRate(final boolean globallyFree) {
        given(context.configuration())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("contracts.maxGasPerTransaction", TOTAL_GAS)
                        .withValue("clpr.verifierGasLimit", 100L)
                        .withValue("fees.simpleFeesAreFree", globallyFree)
                        .getOrCreateConfig());
        final var serviceFee = makeServiceFee(CONTRACT_CALL, 0)
                .copyBuilder()
                .free(!globallyFree)
                .build();
        given(context.simpleFeesSchedule())
                .willReturn(FeeSchedule.DEFAULT
                        .copyBuilder()
                        .services(makeService("ContractService", serviceFee))
                        .build());
        given(context.getGasPriceInTinycents()).willThrow(new IllegalStateException("No GAS extra configured"));
        final var subject = ClprGasAccounting.forContext(context);

        assertThat(subject.prepayVerifier(PARENT, 100)).isTrue();
        assertThat(subject.settleVerifierWithGasUsage(PARENT, 100, gasResult(80)))
                .isZero();
        assertThat(subject.prepay(CONNECTOR_A, 400)).isTrue();
        assertThat(subject.settleWithGasUsage(CONNECTOR_A, 400, gasResult(300))).isZero();
        subject.refundUnusedGas();

        verify(context, never()).getGasPriceInTinycents();
        verify(context, never()).tryToCharge(any(), anyLong());
        verify(context, never()).refundHandlerCharge(any(), anyLong());
        assertThat(recordedFee).hasValue(0);
    }

    @Test
    void unexpectedFailureWithoutExecutedGasKeepsItsOriginalTypeAndCause() {
        final var failure = new IllegalStateException("Failure before EVM work");
        assertThat(ClprGasAccounting.preservingChargesOnRollback(context, failure))
                .isSameAs(failure);
        final var subject = ClprGasAccounting.forContext(context);
        assertThat(subject.prepayVerifier(PARENT, 100)).isTrue();
        subject.settleVerifierWithGasUsage(PARENT, 100, new ContractGasUsage());

        assertThat(ClprGasAccounting.preservingChargesOnRollback(context, failure))
                .isSameAs(failure);
    }

    @Test
    void unexpectedFailureRetainsMeasuredGasReplayAndOriginalCauseWithoutDoubleWrapping() {
        final var subject = ClprGasAccounting.forContext(context);
        assertThat(subject.prepayVerifier(PARENT, 100)).isTrue();
        subject.settleVerifierWithGasUsage(PARENT, 100, gasResult(40));
        final var original = new IllegalStateException("Failure after EVM work");
        final var failure = ClprGasAccounting.preservingChargesOnRollback(context, original);
        assertThat(failure).isInstanceOf(RollbackAwareException.class).hasCause(original);
        assertThat(ClprGasAccounting.preservingChargesOnRollback(context, failure))
                .isSameAs(failure);
        balances.put(PARENT, INITIAL_BALANCE);
        recordedFee.set(0);
        payments.clear();

        ((RollbackAwareException) failure).maybeReplay(mock(FeeCharging.Context.class), context);

        assertThat(payments).containsExactly("1001:240");
        assertThat(recordedFee).hasValue(240);
        assertThat(balances.get(PARENT)).isEqualTo(INITIAL_BALANCE - 240);
    }

    private void givenUserDispatch(final long alreadyCharged, final long offered) {
        given(streamBuilder.isUserDispatch()).willReturn(true);
        given(context.body())
                .willReturn(TransactionBody.newBuilder()
                        .transactionFee(offered)
                        .clprSubmitBundle(ClprSubmitBundleTransactionBody.DEFAULT)
                        .build());
        recordedFee.set(alreadyCharged);
        balances.put(PARENT, INITIAL_BALANCE - alreadyCharged);
    }

    private static ContractGasUsage gasResult(final long gasUsed) {
        final var result = new ContractGasUsage();
        result.recordGasUsed(gasUsed);
        return result;
    }

    private static AccountID account(final long number) {
        return AccountID.newBuilder().accountNum(number).build();
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.transfer.customfees;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_FEE_MUST_BE_POSITIVE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_FEE_OUTSIDE_NUMERIC_RANGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hedera.hapi.node.base.TokenType.FUNGIBLE_COMMON;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.asToken;
import static com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AdjustmentUtils.safeFractionMultiply;
import static com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase.withFixedFee;
import static com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase.withFractionalFee;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TransferUtil.asAccountAmount;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TransferUtil.asTokenTransferList;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Fraction;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.hapi.node.transaction.FractionalFee;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AssessedFeeWithPayerDebits;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AssessmentResult;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.CustomFixedFeeAssessor;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.CustomFractionalFeeAssessor;
import com.hedera.node.app.spi.workflows.HandleException;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class CustomFractionalFeeAssessorTest {
    @Mock
    private CustomFixedFeeAssessor fixedFeeAssessor;

    private CustomFractionalFeeAssessor subject;
    private AssessmentResult result;

    private final AccountID payer = asAccount(0L, 0L, 4001);
    private final TokenID tokenWithFractionalFee = asToken(3000);
    private final AccountID minter = asAccount(0L, 0L, 6000);
    private final AccountID firstReclaimedAcount = asAccount(0L, 0L, 8000);
    private final AccountID secondReclaimedAcount = asAccount(0L, 0L, 9000);
    private final long vanillaTriggerAmount = 5000L;
    private final long firstCreditAmount = 4000L;
    private final long secondCreditAmount = 1000L;
    private final long minApplicableTriggerAmount = 50L;
    private final long maxApplicableTriggerAmount = 50_000L;
    private final long firstMinAmountOfFractionalFee = 2L;
    private final long firstMaxAmountOfFractionalFee = 100L;
    private final long firstNumerator = 1L;
    private final long firstDenominator = 100L;
    private final long netOfTransfersMinAmountOfFractionalFee = 3L;
    private final long netOfTransfersMaxAmountOfFractionalFee = 101L;
    private final long netOfTransfersNumerator = 2L;
    private final long netOfTransfersDenominator = 101L;
    private final long secondMinAmountOfFractionalFee = 10L;
    private final long secondMaxAmountOfFractionalFee = 1000L;
    private final long secondNumerator = 1L;
    private final long secondDenominator = 10L;
    private final long nonsenseNumerator = Long.MAX_VALUE;
    private final long nonsenseDenominator = 1L;
    private final boolean notNetOfTransfers = false;
    private final AccountID firstFractionalFeeCollector = asAccount(0L, 0L, 6666L);
    private final AccountID secondFractionalFeeCollector = asAccount(0L, 0L, 7777L);
    private final AccountID netOfTransfersFeeCollector = asAccount(0L, 0L, 8888L);

    final FractionalFee netOfTransferFractionalFee = FractionalFee.newBuilder()
            .maximumAmount(netOfTransfersMaxAmountOfFractionalFee)
            .minimumAmount(netOfTransfersMinAmountOfFractionalFee)
            .netOfTransfers(true)
            .fractionalAmount(Fraction.newBuilder()
                    .numerator(netOfTransfersNumerator)
                    .denominator(netOfTransfersDenominator)
                    .build())
            .build();
    final FractionalFee firstFractionalFeeWithoutNetOfTransfers = FractionalFee.newBuilder()
            .maximumAmount(firstMaxAmountOfFractionalFee)
            .minimumAmount(firstMinAmountOfFractionalFee)
            .netOfTransfers(false)
            .fractionalAmount(Fraction.newBuilder()
                    .numerator(firstNumerator)
                    .denominator(firstDenominator)
                    .build())
            .build();
    final FractionalFee secondFractionalFeeWithoutNetOfTransfers = FractionalFee.newBuilder()
            .maximumAmount(secondMaxAmountOfFractionalFee)
            .minimumAmount(secondMinAmountOfFractionalFee)
            .netOfTransfers(false)
            .fractionalAmount(Fraction.newBuilder()
                    .numerator(secondNumerator)
                    .denominator(secondDenominator)
                    .build())
            .build();

    final FractionalFee exemptFractionalFeeWithoutNetOfTransfers = FractionalFee.newBuilder()
            .maximumAmount(secondMaxAmountOfFractionalFee)
            .minimumAmount(firstMinAmountOfFractionalFee)
            .netOfTransfers(false)
            .fractionalAmount(Fraction.newBuilder()
                    .numerator(firstNumerator)
                    .denominator(firstDenominator)
                    .build())
            .build();

    final FractionalFee nonsenseFractionalFee = FractionalFee.newBuilder()
            .maximumAmount(1)
            .minimumAmount(1)
            .netOfTransfers(false)
            .fractionalAmount(Fraction.newBuilder()
                    .numerator(nonsenseNumerator)
                    .denominator(nonsenseDenominator)
                    .build())
            .build();

    private final CustomFee skippedFixedFee =
            withFixedFee(FixedFee.newBuilder().amount(100L).build(), null, notNetOfTransfers);
    private final CustomFee fractionalCustomFeeNetOfTransfers =
            withFractionalFee(netOfTransferFractionalFee, netOfTransfersFeeCollector, true);
    private final CustomFee firstFractionalCustomFee =
            withFractionalFee(firstFractionalFeeWithoutNetOfTransfers, firstFractionalFeeCollector, false);
    private final CustomFee secondFractionalCustomFee =
            withFractionalFee(secondFractionalFeeWithoutNetOfTransfers, secondFractionalFeeCollector, false);
    private final CustomFee exemptFractionalCustomFee =
            withFractionalFee(exemptFractionalFeeWithoutNetOfTransfers, payer, false);
    private final CustomFee nonsenseCustomFee =
            withFractionalFee(nonsenseFractionalFee, secondFractionalFeeCollector, false);
    private final TokenTransferList triggerTransferList = asTokenTransferList(
            tokenWithFractionalFee,
            List.of(
                    asAccountAmount(payer, -vanillaTriggerAmount),
                    asAccountAmount(firstReclaimedAcount, +firstCreditAmount),
                    asAccountAmount(secondReclaimedAcount, +secondCreditAmount)));
    private final AccountID[] effPayerAccountNums = new AccountID[] {firstReclaimedAcount, secondReclaimedAcount};

    @BeforeEach
    void setUp() {
        subject = new CustomFractionalFeeAssessor(fixedFeeAssessor);
    }

    @Test
    void appliesFeesAsExpected() {
        result = new AssessmentResult(List.of(triggerTransferList), List.of());
        final Token token = withCustomToken(
                List.of(
                        firstFractionalCustomFee,
                        secondFractionalCustomFee,
                        exemptFractionalCustomFee,
                        skippedFixedFee,
                        fractionalCustomFeeNetOfTransfers),
                FUNGIBLE_COMMON);

        subject.assessFractionalFees(token, payer, result);

        // firstExpectedFee = 5000 * 1 / 100 = 50
        final var firstExpectedFee = subject.assessNonNetOfTransferForDebit(
                        1L, 1L, vanillaTriggerAmount, firstFractionalCustomFee.fractionalFee())
                .amount();
        final var secondExpectedFee = subject.assessNonNetOfTransferForDebit(
                        1L, 1L, vanillaTriggerAmount, secondFractionalCustomFee.fractionalFee())
                .amount();

        final var netFee = subject.assessNonNetOfTransferForDebit(
                        1L, 1L, vanillaTriggerAmount, fractionalCustomFeeNetOfTransfers.fractionalFee())
                .amount();
        final var fixedFee = withFixedFee(
                FixedFee.newBuilder()
                        .denominatingTokenId(tokenWithFractionalFee)
                        .amount(netFee)
                        .build(),
                netOfTransfersFeeCollector,
                true);

        final var expectedAssessedFee1 = AssessedCustomFee.newBuilder()
                .feeCollectorAccountId(firstFractionalFeeCollector)
                .effectivePayerAccountId(effPayerAccountNums)
                .amount(firstExpectedFee)
                .tokenId(tokenWithFractionalFee)
                .build();
        final var expectedAssessedFee2 = AssessedCustomFee.newBuilder()
                .feeCollectorAccountId(secondFractionalFeeCollector)
                .effectivePayerAccountId(effPayerAccountNums)
                .amount(secondExpectedFee)
                .tokenId(tokenWithFractionalFee)
                .build();

        verify(fixedFeeAssessor).assessFixedFee(token, payer, fixedFee, result);

        assertThat(result.getAssessedFeesWithPayerDebits()).isNotEmpty();
        final var deltas = result.getAssessedFeesWithPayerDebits();

        final var assessedFees = deltas.stream()
                .map(AssessedFeeWithPayerDebits::assessedCustomFee)
                .toList();
        assertThat(assessedFees).contains(expectedAssessedFee1);
        assertThat(assessedFees).contains(expectedAssessedFee2);

        assertThat(result.getRoyaltiesPaid()).isEmpty();
    }

    @Test
    void nonNetOfTransfersWithoutExemptions() {
        result = new AssessmentResult(List.of(triggerTransferList), List.of());
        final Token token = withCustomToken(List.of(firstFractionalCustomFee), FUNGIBLE_COMMON);

        subject.assessFractionalFees(token, payer, result);

        // firstExpectedFee = 5000 * 1 / 100 = 50
        final var firstExpectedFee = subject.assessNonNetOfTransferForDebit(
                        1L, 1L, vanillaTriggerAmount, firstFractionalCustomFee.fractionalFee())
                .amount();
        // secondExpectedFee = 5000 * 1 / 10 = 500
        final var secondExpectedFee = subject.assessNonNetOfTransferForDebit(
                        1L, 1L, vanillaTriggerAmount, secondFractionalCustomFee.fractionalFee())
                .amount();
        // netFee = 5000 * 2 / 101 = 99
        final var netFee = subject.assessNonNetOfTransferForDebit(
                        1L, 1L, vanillaTriggerAmount, fractionalCustomFeeNetOfTransfers.fractionalFee())
                .amount();
        final var totalReclaimedFees = firstExpectedFee + secondExpectedFee;
        final var fixedFee = withFixedFee(
                FixedFee.newBuilder()
                        .denominatingTokenId(tokenWithFractionalFee)
                        .amount(netFee)
                        .build(),
                netOfTransfersFeeCollector,
                true);

        // first assessed fee for fee collector is fraction of credit amounts 4000, 1000. So it is 50
        final var expectedAssessedFee1 = AssessedCustomFee.newBuilder()
                .feeCollectorAccountId(firstFractionalFeeCollector)
                .effectivePayerAccountId(effPayerAccountNums)
                .amount(firstExpectedFee)
                .tokenId(tokenWithFractionalFee)
                .build();

        // This is not Net of transfers fee, so it is not assessed
        verify(fixedFeeAssessor, never()).assessFixedFee(token, payer, fixedFee, result);
        assertThat(result.getAssessedFeesWithPayerDebits()).hasSize(1);
        final var assessedFees = result.getAssessedFeesWithPayerDebits().stream()
                .map(AssessedFeeWithPayerDebits::assessedCustomFee)
                .toList();
        assertThat(assessedFees).contains(expectedAssessedFee1);

        assertThat(result.getRoyaltiesPaid()).isEmpty();
    }

    @Test
    void nonNetOfTransfersRespectExemptionsIfPayerIsCollector() {
        result = new AssessmentResult(List.of(triggerTransferList), List.of());
        // two custom fees set. First sender is exempt from fees
        final Token token = withCustomToken(
                List.of(
                        firstFractionalCustomFee
                                .copyBuilder()
                                .feeCollectorAccountId(payer)
                                .build(),
                        secondFractionalCustomFee),
                FUNGIBLE_COMMON);

        subject.assessFractionalFees(token, payer, result);

        // firstExpectedFee is ignored due to the fee collector being same as the payer
        // secondExpectedFee = 5000 * 1 / 10 = 500
        final var secondExpectedFee = subject.assessNonNetOfTransferForDebit(
                        1L, 1L, vanillaTriggerAmount, secondFractionalCustomFee.fractionalFee())
                .amount();

        // first assessed fee for fee collector is fraction of credit amounts 4000, 1000. So it is 50
        final var expectedAssessedFee1 = AssessedCustomFee.newBuilder()
                .feeCollectorAccountId(secondFractionalFeeCollector)
                .effectivePayerAccountId(effPayerAccountNums)
                .amount(secondExpectedFee)
                .tokenId(tokenWithFractionalFee)
                .build();

        // This is not Net of transfers fee, so it is not assessed
        verify(fixedFeeAssessor, never()).assessFixedFee(any(), any(), any(), any());
        assertThat(result.getAssessedFeesWithPayerDebits()).hasSize(1);
        final var assessedFees = result.getAssessedFeesWithPayerDebits().stream()
                .map(AssessedFeeWithPayerDebits::assessedCustomFee)
                .toList();
        assertThat(assessedFees).contains(expectedAssessedFee1);

        assertThat(result.getRoyaltiesPaid()).isEmpty();
    }

    @Test
    void ifNonNetOfTransfersFindsAllExemptThenNoFeesAssessed() {
        result = new AssessmentResult(List.of(triggerTransferList), List.of());
        // two custom fees set. First sender is exempt from fees
        final Token token = withCustomToken(
                List.of(secondFractionalCustomFee
                        .copyBuilder()
                        .feeCollectorAccountId(payer)
                        .allCollectorsAreExempt(true)
                        .build()),
                FUNGIBLE_COMMON);

        subject.assessFractionalFees(token, payer, result);

        // This is not Net of transfers fee, so it is not assessed
        verify(fixedFeeAssessor, never()).assessFixedFee(any(), any(), any(), any());
        assertThat(result.getAssessedFeesWithPayerDebits()).isEmpty();
        assertThat(result.getRoyaltiesPaid()).isEmpty();
    }

    @Test
    void abortsOnCreditOverflow() {
        final TokenTransferList triggerTransferList = asTokenTransferList(
                tokenWithFractionalFee,
                List.of(
                        asAccountAmount(payer, -vanillaTriggerAmount),
                        asAccountAmount(firstReclaimedAcount, Long.MAX_VALUE),
                        asAccountAmount(secondReclaimedAcount, Long.MAX_VALUE)));
        result = new AssessmentResult(List.of(triggerTransferList), List.of());
        // two custom fees set. First sender is exempt from fees
        final Token token =
                withCustomToken(List.of(firstFractionalCustomFee, secondFractionalCustomFee), FUNGIBLE_COMMON);

        assertThatThrownBy(() -> subject.assessFractionalFees(token, payer, result))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CUSTOM_FEE_OUTSIDE_NUMERIC_RANGE));

        // This is not Net of transfers fee, so it is not assessed
        verify(fixedFeeAssessor, never()).assessFixedFee(any(), any(), any(), any());
        assertThat(result.getAssessedFeesWithPayerDebits()).isEmpty();
        assertThat(result.getRoyaltiesPaid()).isEmpty();
    }

    @Test
    void cannotOverflowWithCrazyFraction() {
        final TokenTransferList triggerTransferList = asTokenTransferList(
                tokenWithFractionalFee,
                List.of(
                        asAccountAmount(payer, -vanillaTriggerAmount),
                        asAccountAmount(firstReclaimedAcount, firstCreditAmount),
                        asAccountAmount(secondReclaimedAcount, secondCreditAmount)));
        result = new AssessmentResult(List.of(triggerTransferList), List.of());
        // two custom fees set. First sender is exempt from fees
        final Token token = withCustomToken(List.of(nonsenseCustomFee, secondFractionalCustomFee), FUNGIBLE_COMMON);

        assertThatThrownBy(() -> subject.assessFractionalFees(token, payer, result))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CUSTOM_FEE_OUTSIDE_NUMERIC_RANGE));

        // This is not Net of transfers fee, so it is not assessed
        verify(fixedFeeAssessor, never()).assessFixedFee(any(), any(), any(), any());
        assertThat(result.getAssessedFeesWithPayerDebits()).isEmpty();
        assertThat(result.getRoyaltiesPaid()).isEmpty();
    }

    @Test
    void failsWithInsufficientBalanceWhenAppropriate() {
        final TokenTransferList triggerTransferList = asTokenTransferList(
                tokenWithFractionalFee, List.of(asAccountAmount(payer, -1), asAccountAmount(firstReclaimedAcount, 1)));
        result = new AssessmentResult(List.of(triggerTransferList), List.of());
        // two custom fees set. First sender is exempt from fees
        final Token token = withCustomToken(
                List.of(nonsenseCustomFee, firstFractionalCustomFee, secondFractionalCustomFee), FUNGIBLE_COMMON);

        assertThatThrownBy(() -> subject.assessFractionalFees(token, payer, result))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE));
    }

    @Test
    void failsFastOnJustPositiveAdjustment() {
        final TokenTransferList triggerTransferList =
                asTokenTransferList(tokenWithFractionalFee, List.of(asAccountAmount(payer, Long.MAX_VALUE)));
        result = new AssessmentResult(List.of(triggerTransferList), List.of());
        // two custom fees set. First sender is exempt from fees
        final Token token = withCustomToken(List.of(), FUNGIBLE_COMMON);

        assertThatThrownBy(() -> subject.assessFractionalFees(token, payer, result))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CUSTOM_FEE_MUST_BE_POSITIVE));
    }

    @Test
    void handlesEasyCase() {
        long reasonable = 1_234_567L;
        long n = 10;
        long d = 9;

        final var expected = reasonable * n / d;

        assertThat(safeFractionMultiply(n, d, reasonable)).isEqualTo(expected);
    }

    @Test
    void fallsBackToArbitraryPrecisionIfNeeded() {
        long huge = Long.MAX_VALUE / 2;
        long n = 10;
        long d = 9;
        final var expected = BigInteger.valueOf(huge)
                .multiply(BigInteger.valueOf(n))
                .divide(BigInteger.valueOf(d))
                .longValueExact();

        assertThat(safeFractionMultiply(n, d, huge)).isEqualTo(expected);
    }

    @Test
    void propagatesArithmeticExceptionOnOverflow() {
        long huge = Long.MAX_VALUE - 1;
        long n = 10;
        long d = 9;

        assertThatThrownBy(() -> safeFractionMultiply(n, d, huge)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void computesCheckingAmountOwned() {
        assertThat(subject.assessNonNetOfTransferForDebit(
                        1L, 1L, vanillaTriggerAmount, firstFractionalCustomFee.fractionalFee()))
                .isEqualTo(new CustomFractionalFeeAssessor.NonNetAssessment(
                        vanillaTriggerAmount / firstDenominator, false));
    }

    @Test
    void enforcesMax() {
        assertThat(subject.assessNonNetOfTransferForDebit(
                        1L, 1L, maxApplicableTriggerAmount, firstFractionalCustomFee.fractionalFee()))
                .isEqualTo(new CustomFractionalFeeAssessor.NonNetAssessment(firstMaxAmountOfFractionalFee, false));
    }

    @Test
    void enforcesMin() {
        assertThat(subject.assessNonNetOfTransferForDebit(
                        1L, 1L, minApplicableTriggerAmount, firstFractionalCustomFee.fractionalFee()))
                .isEqualTo(new CustomFractionalFeeAssessor.NonNetAssessment(firstMinAmountOfFractionalFee, true));
    }

    @Test
    void reclaimsRemainderAsExpected() {
        final TokenTransferList triggerTransferList = asTokenTransferList(
                tokenWithFractionalFee,
                List.of(
                        asAccountAmount(payer, -vanillaTriggerAmount),
                        asAccountAmount(firstReclaimedAcount, firstCreditAmount),
                        asAccountAmount(secondReclaimedAcount, secondCreditAmount)));
        result = new AssessmentResult(List.of(triggerTransferList), List.of());
        final Token token =
                withCustomToken(List.of(firstFractionalCustomFee, secondFractionalCustomFee), FUNGIBLE_COMMON);

        subject.assessFractionalFees(token, payer, result);

        final var firstExpectedFee = subject.assessNonNetOfTransferForDebit(
                        1L, 1L, vanillaTriggerAmount, firstFractionalCustomFee.fractionalFee())
                .amount();
        final var secondExpectedFee = subject.assessNonNetOfTransferForDebit(
                        1L, 1L, vanillaTriggerAmount, secondFractionalCustomFee.fractionalFee())
                .amount();

        final var expectedAssessedFee1 = AssessedCustomFee.newBuilder()
                .feeCollectorAccountId(firstFractionalFeeCollector)
                .effectivePayerAccountId(effPayerAccountNums)
                .amount(firstExpectedFee)
                .tokenId(tokenWithFractionalFee)
                .build();
        final var expectedAssessedFee2 = AssessedCustomFee.newBuilder()
                .feeCollectorAccountId(secondFractionalFeeCollector)
                .effectivePayerAccountId(effPayerAccountNums)
                .amount(secondExpectedFee)
                .tokenId(tokenWithFractionalFee)
                .build();

        verify(fixedFeeAssessor, never()).assessFixedFee(any(), any(), any(), any());

        assertThat(result.getAssessedFeesWithPayerDebits()).isNotEmpty();

        final var assessedFees = result.getAssessedFeesWithPayerDebits().stream()
                .map(AssessedFeeWithPayerDebits::assessedCustomFee)
                .toList();
        assertThat(assessedFees).contains(expectedAssessedFee1);
        assertThat(assessedFees).contains(expectedAssessedFee2);

        assertThat(result.getRoyaltiesPaid()).isEmpty();
    }

    @Test
    void doesNothingIfNoValueExchangedAndNoFallback() {
        final var transfersList =
                asTokenTransferList(tokenWithFractionalFee, List.of(asAccountAmount(payer, -vanillaTriggerAmount)));
        result = new AssessmentResult(List.of(transfersList), List.of());

        final Token token = withCustomToken(
                List.of(
                        firstFractionalCustomFee,
                        secondFractionalCustomFee,
                        exemptFractionalCustomFee,
                        skippedFixedFee,
                        fractionalCustomFeeNetOfTransfers),
                FUNGIBLE_COMMON);

        subject.assessFractionalFees(token, payer, result);

        verify(fixedFeeAssessor, never()).assessFixedFee(any(), any(), any(), any());
        assertThat(result.getAssessedFeesWithPayerDebits()).isEmpty();
        assertThat(result.getRoyaltiesPaid()).isEmpty();
    }

    public Token withCustomToken(List<CustomFee> customFees, TokenType tokenType) {
        return Token.newBuilder()
                .customFees(customFees)
                .tokenId(tokenWithFractionalFee)
                .tokenType(tokenType)
                .treasuryAccountId(minter)
                .build();
    }
}

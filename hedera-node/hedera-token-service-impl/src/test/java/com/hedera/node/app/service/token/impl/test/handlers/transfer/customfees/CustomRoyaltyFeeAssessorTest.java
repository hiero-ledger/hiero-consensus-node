// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.transfer.customfees;

import static com.hedera.hapi.node.base.TokenType.NON_FUNGIBLE_UNIQUE;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.asToken;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.asAccountWithAlias;
import static com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase.withFixedFee;
import static com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase.withRoyaltyFee;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TransferUtil.asNftTransferList;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TransferUtil.asTokenTransferList;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TransferUtil.asTransferList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Fraction;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.hapi.node.transaction.RoyaltyFee;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AssessedFeeWithPayerDebits;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AssessmentResult;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.CustomFixedFeeAssessor;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.CustomRoyaltyFeeAssessor;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class CustomRoyaltyFeeAssessorTest {
    @Mock
    private CustomFixedFeeAssessor fixedFeeAssessor;

    private CustomRoyaltyFeeAssessor subject;
    private AssessmentResult result;

    private final long originalUnits = 100;
    private final AccountID payer = asAccount(0L, 0L, 4001);
    private final AccountID otherCollector = asAccount(0L, 0L, 1001);
    private final AccountID targetCollector = asAccount(0L, 0L, 2001);
    private final AccountID funding = asAccount(0L, 0L, 98);
    private final TokenID firstFungibleTokenId = asToken(3000);
    private final AccountID minter = asAccount(0L, 0L, 6000);
    private final AccountID alias = asAccountWithAlias(Bytes.wrap("01234567890123456789012345678901"));
    private final TokenID nonFungibleTokenId = asToken(70000);
    private final TransferList hbarPayerTransferList = asTransferList(originalUnits, payer);
    private final TokenTransferList htsPayerTokenTransferList =
            asTokenTransferList(firstFungibleTokenId, originalUnits, payer);
    private final TokenTransferList nftTransferList = asNftTransferList(nonFungibleTokenId, payer, funding, 1);
    private final TokenTransferList nftTransferListWithAlias = asNftTransferList(nonFungibleTokenId, payer, alias, 1);
    private final AssessedCustomFee hbarAssessedFee = AssessedCustomFee.newBuilder()
            .amount(originalUnits / 2)
            .effectivePayerAccountId(payer)
            .feeCollectorAccountId(targetCollector)
            .build();

    private final AssessedCustomFee htsAssessedFee = AssessedCustomFee.newBuilder()
            .amount(originalUnits / 2)
            .tokenId(firstFungibleTokenId)
            .effectivePayerAccountId(payer)
            .feeCollectorAccountId(targetCollector)
            .build();

    final FixedFee hbarFallbackFee = FixedFee.newBuilder().amount(33).build();
    final FixedFee htsFallbackFee = FixedFee.newBuilder()
            .amount(33)
            .denominatingTokenId(firstFungibleTokenId)
            .build();
    final FixedFee fixedFee = FixedFee.newBuilder()
            .denominatingTokenId(firstFungibleTokenId)
            .amount(1)
            .build();
    final RoyaltyFee royaltyFee = RoyaltyFee.newBuilder()
            .exchangeValueFraction(
                    Fraction.newBuilder().numerator(1).denominator(2).build())
            .build();

    @BeforeEach
    void setUp() {
        subject = new CustomRoyaltyFeeAssessor(fixedFeeAssessor);
    }

    @Test
    void doesNothingIfNoValueExchangedAndNoFallback() {
        result = new AssessmentResult(List.of(nftTransferList), List.of());

        final Token token = withCustomToken(
                List.of(withFixedFee(fixedFee, otherCollector, false), withRoyaltyFee(royaltyFee, targetCollector)),
                NON_FUNGIBLE_UNIQUE);

        subject.assessRoyaltyFees(token, payer, funding, result);

        assertThat(result.getAssessedFeesWithPayerDebits()).isEmpty();
        verify(fixedFeeAssessor, never()).assessFixedFee(any(), any(), any(), any());

        // We add to the set of royalties paid to track the royalties paid.
        // Even though nothing is paid, once its analyzed it should be added to the set
        assertThat(result.getRoyaltiesPaid()).contains(Pair.of(funding, token.tokenId()));
        assertThat(result.getAssessedFeesWithPayerDebits()).isEmpty();
    }

    @Test
    void chargesHbarFallbackAsExpected() {
        result = new AssessmentResult(List.of(nftTransferList), List.of());

        final var royaltyCustomFee = withRoyaltyFee(
                royaltyFee.copyBuilder().fallbackFee(hbarFallbackFee).build(), targetCollector);
        final var royaltyFixedFee = withFixedFee(fixedFee, otherCollector, false);

        final Token token = withCustomToken(List.of(royaltyFixedFee, royaltyCustomFee), NON_FUNGIBLE_UNIQUE);

        subject.assessRoyaltyFees(token, payer, funding, result);

        assertThat(result.getAssessedFeesWithPayerDebits()).isEmpty();
        // receiver will pay the fallback fee
        verify(fixedFeeAssessor)
                .assessFixedFee(token, funding, withFixedFee(hbarFallbackFee, targetCollector, false), result);

        // We add to the set of royalties paid to track the royalties paid. It should have an entry with receiver
        assertThat(result.getRoyaltiesPaid()).contains(Pair.of(funding, token.tokenId()));
    }

    @Test
    void chargesHtsFallbackAsExpected() {
        result = new AssessmentResult(List.of(nftTransferList), List.of());

        final var royaltyCustomFee = withRoyaltyFee(
                royaltyFee.copyBuilder().fallbackFee(htsFallbackFee).build(), targetCollector);
        final var royaltyFixedFee = withFixedFee(fixedFee, otherCollector, false);

        final Token token = withCustomToken(List.of(royaltyFixedFee, royaltyCustomFee), NON_FUNGIBLE_UNIQUE);

        subject.assessRoyaltyFees(token, payer, funding, result);

        assertThat(result.getAssessedFeesWithPayerDebits()).isEmpty();
        // receiver will pay the fallback fee
        verify(fixedFeeAssessor)
                .assessFixedFee(token, funding, withFixedFee(htsFallbackFee, targetCollector, false), result);

        // We add to the set of royalties paid to track the royalties paid. It should have an entry with receiver
        assertThat(result.getRoyaltiesPaid()).contains(Pair.of(funding, token.tokenId()));
    }

    @Test
    void doesntFailIfFallbackNftTransferredToUnknownAlias() {
        result = new AssessmentResult(List.of(nftTransferListWithAlias), List.of());

        final var royaltyCustomFee = withRoyaltyFee(
                royaltyFee.copyBuilder().fallbackFee(htsFallbackFee).build(), targetCollector);
        final var fixedFee = withFixedFee(this.fixedFee, otherCollector, false);

        final Token token = withCustomToken(List.of(fixedFee, royaltyCustomFee), NON_FUNGIBLE_UNIQUE);

        subject.assessRoyaltyFees(token, payer, funding, result);

        assertThat(result.getAssessedFeesWithPayerDebits()).isEmpty();
        // receiver will pay the fallback fee
        verify(fixedFeeAssessor)
                .assessFixedFee(token, funding, withFixedFee(htsFallbackFee, targetCollector, false), result);

        // We add to the set of royalties paid to track the royalties paid. It should have an entry with receiver
        assertThat(result.getRoyaltiesPaid()).contains(Pair.of(funding, token.tokenId()));
    }

    @Test
    void skipsIfRoyaltyAlreadyPaidByReceiver() {
        result = new AssessmentResult(List.of(nftTransferListWithAlias), List.of());
        // Include royalty already paid
        result.addToRoyaltiesPaid(Pair.of(funding, firstFungibleTokenId));

        final var royaltyCustomFee = withRoyaltyFee(
                royaltyFee.copyBuilder().fallbackFee(htsFallbackFee).build(), targetCollector);
        final var fixedFee = withFixedFee(this.fixedFee, otherCollector, false);

        final Token token = withCustomToken(List.of(fixedFee, royaltyCustomFee), NON_FUNGIBLE_UNIQUE);

        subject.assessRoyaltyFees(token, payer, funding, result);

        assertThat(result.getAssessedFeesWithPayerDebits()).isEmpty();

        verify(fixedFeeAssessor, never()).assessFixedFee(any(), any(), any(), any());
    }

    @Test
    void assessRoyaltyOnlyOncePerTokenType() {
        result = new AssessmentResult(List.of(nftTransferListWithAlias), List.of());
        // Include royalty already paid by sender
        result.addToRoyaltiesPaid(Pair.of(payer, firstFungibleTokenId));

        final var royaltyCustomFee = withRoyaltyFee(
                royaltyFee.copyBuilder().fallbackFee(htsFallbackFee).build(), targetCollector);
        final var fixedFee = withFixedFee(this.fixedFee, otherCollector, false);

        final Token token = withCustomToken(List.of(fixedFee, royaltyCustomFee), NON_FUNGIBLE_UNIQUE);

        subject.assessRoyaltyFees(token, payer, funding, result);

        assertThat(result.getAssessedFeesWithPayerDebits()).isEmpty();

        verify(fixedFeeAssessor, never()).assessFixedFee(any(), any(), any(), any());
    }

    @Test
    void reclaimsFromExchangeValueWhenAvailable() {
        final var accountAmounts = List.of(AccountAmount.newBuilder()
                .accountID(payer)
                .amount(originalUnits)
                .build());
        result = new AssessmentResult(
                List.of(
                        nftTransferList,
                        htsPayerTokenTransferList
                                .copyBuilder()
                                .transfers(accountAmounts)
                                .build()),
                accountAmounts);

        final var royaltyCustomFee = withRoyaltyFee(
                royaltyFee.copyBuilder().fallbackFee(htsFallbackFee).build(), targetCollector);
        final var royaltyFixedFee = withFixedFee(fixedFee, otherCollector, false);

        final Token token = withCustomToken(List.of(royaltyFixedFee, royaltyCustomFee), NON_FUNGIBLE_UNIQUE);

        subject.assessRoyaltyFees(token, payer, funding, result);

        assertThat(result.getAssessedFeesWithPayerDebits()).isNotEmpty();
        assertThat(result.getAssessedFeesWithPayerDebits())
                .contains(new AssessedFeeWithPayerDebits(hbarAssessedFee, null));
        assertThat(result.getAssessedFeesWithPayerDebits())
                .contains(new AssessedFeeWithPayerDebits(htsAssessedFee, null));
        // sender will pay from exchange credits
        verify(fixedFeeAssessor, never()).assessFixedFees(any(), any(), any());

        // We add to the set of royalties paid to track the royalties paid. It should have an entry with sender
        assertThat(result.getRoyaltiesPaid()).contains(Pair.of(payer, token.tokenId()));
    }

    @Test
    void doesntCollectRoyaltyIfOriginalPayerIsExempt() {
        final var accountAmounts = List.of(AccountAmount.newBuilder()
                .accountID(payer)
                .amount(originalUnits)
                .build());
        result = new AssessmentResult(
                List.of(
                        nftTransferList,
                        htsPayerTokenTransferList
                                .copyBuilder()
                                .transfers(accountAmounts)
                                .build()),
                accountAmounts);

        final var royaltyCustomFee = withRoyaltyFee(
                royaltyFee.copyBuilder().fallbackFee(htsFallbackFee).build(), payer);
        final var royaltyFixedFee = withFixedFee(fixedFee, payer, false);

        final Token token = withCustomToken(List.of(royaltyFixedFee, royaltyCustomFee), NON_FUNGIBLE_UNIQUE);

        subject.assessRoyaltyFees(token, payer, funding, result);

        assertThat(result.getAssessedFeesWithPayerDebits()).isEmpty();
        // sender will pay from exchange credits
        verify(fixedFeeAssessor, never()).assessFixedFees(any(), any(), any());
    }

    public Token withCustomToken(List<CustomFee> customFees, TokenType tokenType) {
        return Token.newBuilder()
                .customFees(customFees)
                .tokenId(firstFungibleTokenId)
                .tokenType(tokenType)
                .treasuryAccountId(minter)
                .build();
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.aaWith;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.TRANSACTION_FIXED_FEE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.*;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.FixedCustomFee;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.handlers.transfer.AssociateTokenRecipientsStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.CustomFeeAssessmentStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.EnsureAliasesStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.ReplaceAliasesWithIDsInOp;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferContextImpl;
import com.hedera.node.app.service.token.impl.test.handlers.util.TestStoreFactory;
import com.hedera.node.app.service.token.records.CryptoTransferStreamBuilder;
import com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CustomFeeAssessmentStepTest extends StepsBase {
    private TransferContextImpl transferContext;
    private CustomFeeAssessmentStep subject;
    private Token fungibleWithNoKyc;
    private Token nonFungibleWithNoKyc;

    @BeforeEach
    public void setUp() {
        super.setUp();
        refreshWritableStores();

        givenStoresAndConfig(handleContext);
        given(handleContext.dispatchMetadata()).willReturn(DispatchMetadata.EMPTY_METADATA);
        givenTxn();
        given(handleContext.body()).willReturn(txn);
        given(stack.getBaseBuilder(CryptoTransferStreamBuilder.class)).willReturn(xferRecordBuilder);
        givenAutoCreationDispatchEffects(payerId);

        transferContext = new TransferContextImpl(handleContext);
        ensureAliasesStep = new EnsureAliasesStep(body);
        replaceAliasesWithIDsInOp = new ReplaceAliasesWithIDsInOp();
        associateTokenRecepientsStep = new AssociateTokenRecipientsStep(body);

        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        final var replacedOp = getReplacedOp();
        subject = new CustomFeeAssessmentStep(replacedOp);
    }

    @Test
    @DisplayName("Transfer which triggers hbar fixed fee, fractional fee and royalty fee with fall back")
    void hbarFixedFeeAndRoyaltyFeeWithFallback() {
        // fungible token transfer with custom hbar fixed fee
        // and a fractional fee with netOfTransfers false
        // NFT transfer with royalty fee (fraction 1/2), fallbackFee with fixed hbar fee
        final var hbarsReceiver = asAccount(0L, 0L, hbarReceiver);
        final var tokensReceiver = asAccount(0L, 0L, tokenReceiver);

        givenTxn();

        final var listOfOps = subject.assessCustomFees(new TransferContextImpl(handleContext, false));
        assertThat(listOfOps).hasSize(2);

        final var givenOp = listOfOps.get(0);
        final var level1Op = listOfOps.get(1);

        final var expectedLevel1Trasfers = Map.of(
                feeCollectorId,
                2000L,
                tokensReceiver,
                -1000L, // royalty fee
                ownerId,
                -1000L); // fixed fee
        final var expectedGivenOpTokenTransfers = Map.of(
                fungibleTokenId,
                Map.of(
                        feeCollectorId, 10L, // fractional fee
                        tokensReceiver, 990L,
                        ownerId, -1000L));
        final var expectedGivenOpHbarTransfers = Map.of(hbarsReceiver, 1000L, ownerId, -1000L);

        assertThatTransfersContains(level1Op.transfers().accountAmounts(), expectedLevel1Trasfers);
        assertThatTransferListContains(givenOp.tokenTransfers(), expectedGivenOpTokenTransfers);
        assertThatTransfersContains(givenOp.transfers().accountAmounts(), expectedGivenOpHbarTransfers);
    }

    @Test
    @DisplayName("Transfer which adds a custom fee to the metadata")
    void hbarFixedCustomFee() {
        final var amount = 1000;
        // tests the CustomFeeAssessor.setTransactionFeesAsAssessed() method
        body = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(List.of(
                                AccountAmount.newBuilder()
                                        .accountID(ownerId)
                                        .amount(-amount)
                                        .build(),
                                AccountAmount.newBuilder()
                                        .accountID(payerId)
                                        .amount(amount)
                                        .build()))
                        .build())
                .build();
        givenDifferentTxn(body, payerId);
        given(handleContext.dispatchMetadata())
                .willReturn(new DispatchMetadata(
                        TRANSACTION_FIXED_FEE,
                        FixedCustomFee.newBuilder()
                                .fixedFee(FixedFee.newBuilder().amount(amount).build())
                                .feeCollectorAccountId(ownerId)
                                .build()));

        final var listOfOps = subject.assessCustomFees(transferContext);
        assertThat(listOfOps).hasSize(1);
        assertThat(listOfOps.get(0)).isEqualTo(body);
        assertThat(transferContext.getAssessedCustomFees().size()).isEqualTo(1);
        assertThat(transferContext.getAssessedCustomFees().getFirst().amount()).isEqualTo(amount);
    }

    @Test
    @DisplayName("Transfer which triggers hts fixed fee that is self denominated, fractional fee "
            + "and royalty fee with fall back that is hts fixed fee")
    void htsFixedFeeAndRoyaltyFeeWithFallbackSelfDenomination() {
        // fungible token transfer with custom hts fixed fee
        // and a fractional fee with netOfTransfers false
        // NFT transfer with royalty fee (fraction 1/2), fallbackFee with fixed hts fee
        final var hbarsReceiver = asAccount(0L, 0L, hbarReceiver);
        final var tokensReceiver = asAccount(0L, 0L, tokenReceiver);
        final var customfees = List.of(withFixedFee(htsFixedFee));
        writableTokenStore.put(
                fungibleWithNoKyc.copyBuilder().customFees(customfees).build());
        writableTokenStore.put(nonFungibleWithNoKyc
                .copyBuilder()
                .customFees(List.of(withRoyaltyFee(
                        royaltyFee.copyBuilder().fallbackFee(htsFixedFee).build())))
                .build());
        given(storeFactory.writableStore(WritableTokenStore.class)).willReturn(writableTokenStore);
        given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(writableTokenStore);

        givenTxn();

        final var listOfOps = subject.assessCustomFees(transferContext);
        assertThat(listOfOps).hasSize(2);

        final var givenOp = listOfOps.get(0);
        final var level1Op = listOfOps.get(1);

        final var expectedLevel1TokenTransfers = Map.of(
                fungibleTokenId,
                Map.of(feeCollectorId, 20L, tokensReceiver, -20L)); // First 10 is for fallback royalty fee from level 0
        // second 10 is for next assessment of hts fee, since sef denominated will be modified in existing txn

        final var expectedGivenOpTokenTransfers = Map.of(
                fungibleTokenId,
                Map.of(
                        tokensReceiver, 1000L,
                        ownerId, -1010L,
                        feeCollectorId, 10L)); // hts self denominated fee will be adjusted in given txn
        final var expectedGivenOpHbarTransfers = Map.of(hbarsReceiver, 1000L, ownerId, -1000L);

        assertThatTransferListContains(level1Op.tokenTransfers(), expectedLevel1TokenTransfers);
        assertThatTransferListContains(givenOp.tokenTransfers(), expectedGivenOpTokenTransfers);
        assertThatTransfersContains(givenOp.transfers().accountAmounts(), expectedGivenOpHbarTransfers);
    }

    @Test
    @DisplayName("Transfer which triggers hbar fixed fee and royalty fee with no fall back")
    void hbarFixedFeeAndRoyaltyFeeNoFallback() {
        // fungible token transfer with custom hbar fixed fee
        // and a fractional fee with netOfTransfers false
        // NFT transfer with royalty fee (fraction 1/2), no fallbackFee
        writableTokenStore.put(nonFungibleToken
                .copyBuilder()
                .customFees(List.of(withRoyaltyFee(
                        royaltyFee.copyBuilder().fallbackFee((FixedFee) null).build())))
                .build());
        given(storeFactory.writableStore(WritableTokenStore.class)).willReturn(writableTokenStore);
        given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(writableTokenStore);

        final var hbarsReceiver = asAccount(0L, 0L, hbarReceiver);
        final var tokensReceiver = asAccount(0L, 0L, tokenReceiver);

        givenTxn();

        final var listOfOps = subject.assessCustomFees(transferContext);
        assertThat(listOfOps).hasSize(2);

        final var givenOp = listOfOps.get(0);
        final var level1Op = listOfOps.get(1);
        // since no fallback fee, there is no fallback fee deduction
        final var expectedLevel1Trasfers = Map.of(feeCollectorId, 1000L, ownerId, -1000L); // fixed fee
        final var expectedGivenOpTokenTransfers = Map.of(
                fungibleTokenId,
                Map.of(
                        feeCollectorId, 10L, // fractional fee
                        tokensReceiver, 990L,
                        ownerId, -1000L));
        final var expectedGivenOpHbarTransfers = Map.of(hbarsReceiver, 1000L, ownerId, -1000L);

        assertThatTransfersContains(level1Op.transfers().accountAmounts(), expectedLevel1Trasfers);
        assertThatTransferListContains(givenOp.tokenTransfers(), expectedGivenOpTokenTransfers);
        assertThatTransfersContains(givenOp.transfers().accountAmounts(), expectedGivenOpHbarTransfers);
    }

    @Test
    @DisplayName(
            "Transfer which triggers hts fixed fee that is self denominated " + "and royalty fee with no fall back")
    void htsFixedFeeSelfDenominationAndRoyaltyFeeNoFallback() {
        // fungible token transfer with custom hts fixed fee
        // and a fractional fee with netOfTransfers false
        // NFT transfer with royalty fee (fraction 1/2), no fallbackFee
        final var hbarsReceiver = asAccount(0L, 0L, hbarReceiver);
        final var tokensReceiver = asAccount(0L, 0L, tokenReceiver);
        final var customfees = List.of(withFixedFee(htsFixedFee));
        writableTokenStore.put(
                fungibleToken.copyBuilder().customFees(customfees).build());
        writableTokenStore.put(nonFungibleToken
                .copyBuilder()
                .customFees(List.of(withRoyaltyFee(
                        royaltyFee.copyBuilder().fallbackFee((FixedFee) null).build())))
                .build());
        given(storeFactory.writableStore(WritableTokenStore.class)).willReturn(writableTokenStore);
        given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(writableTokenStore);

        givenTxn();

        final var listOfOps = subject.assessCustomFees(transferContext);
        assertThat(listOfOps).hasSize(1);

        final var givenOp = listOfOps.get(0);

        final var expectedGivenOpTokenTransfers = Map.of(
                fungibleTokenId,
                Map.of(
                        tokensReceiver, 1000L,
                        ownerId, -1010L,
                        feeCollectorId, 10L)); // fixed fee with self denomination will be adjusted in same txn body
        final var expectedGivenOpHbarTransfers = Map.of(hbarsReceiver, 1000L, ownerId, -1000L);

        assertThatTransferListContains(givenOp.tokenTransfers(), expectedGivenOpTokenTransfers);
        assertThatTransfersContains(givenOp.transfers().accountAmounts(), expectedGivenOpHbarTransfers);
    }

    @Test
    @DisplayName("Transfer which triggers hbar fixed fee, fractional fee with netOfTransfers is true "
            + "and royalty fee with fall back hbar fixed fee")
    void hbarFixedFeeAndRoyaltyFeeWithFallbackNetOfTransfers() {
        // fungible token transfer with custom hbar fixed fee
        // and a fractional fee with netOfTransfers true
        // NFT transfer with royalty fee (fraction 1/2), fallbackFee with fixed hbar fee

        final var customfees = List.of(
                withFixedFee(hbarFixedFee),
                withFractionalFee(
                        fractionalFee.copyBuilder().netOfTransfers(true).build()));
        writableTokenStore.put(
                fungibleToken.copyBuilder().customFees(customfees).build());
        given(storeFactory.writableStore(WritableTokenStore.class)).willReturn(writableTokenStore);
        given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(writableTokenStore);

        final var hbarsReceiver = asAccount(0L, 0L, hbarReceiver);
        final var tokensReceiver = asAccount(0L, 0L, tokenReceiver);

        givenTxn();

        final var listOfOps = subject.assessCustomFees(new TransferContextImpl(handleContext, false));
        assertThat(listOfOps).hasSize(2);

        final var givenOp = listOfOps.get(0);
        final var level1Op = listOfOps.get(1);

        final var expectedLevel1Trasfers = Map.of(
                feeCollectorId,
                2000L,
                tokensReceiver,
                -1000L, // royalty fee
                ownerId,
                -1000L); // fixed fee
        final var expectedLevel1TokenTransfers = Map.of(
                fungibleTokenId,
                Map.of(
                        feeCollectorId,
                                10L, // since netOfTransfers is true fractional fee is charged to payer in next level
                        ownerId, -10L));
        final var expectedGivenOpTokenTransfers = Map.of(
                fungibleTokenId,
                Map.of(
                        tokensReceiver, 1000L,
                        ownerId, -1010L,
                        feeCollectorId, 10L)); // fractional fees all are adjusted to input txn
        final var expectedGivenOpHbarTransfers = Map.of(hbarsReceiver, 1000L, ownerId, -1000L);

        assertThatTransfersContains(level1Op.transfers().accountAmounts(), expectedLevel1Trasfers);
        assertThatTransferListContains(level1Op.tokenTransfers(), expectedLevel1TokenTransfers);
        assertThatTransferListContains(givenOp.tokenTransfers(), expectedGivenOpTokenTransfers);
        assertThatTransfersContains(givenOp.transfers().accountAmounts(), expectedGivenOpHbarTransfers);
    }

    @Test
    @DisplayName("Transfer which triggers hts fixed fee with self denomination, fractional fee "
            + "with netOfTransfers is true and royalty fee with fall back hbar fixed fee")
    void htsFixedFeeAndRoyaltyFeeWithFallbackNetOfTransfersSelfDenomination() {
        // fungible token transfer with custom hts fixed fee
        // and a fractional fee with netOfTransfers true
        // NFT transfer with royalty fee (fraction 1/2), fallbackFee with fixed hts fee
        final var hbarsReceiver = asAccount(0L, 0L, hbarReceiver);
        final var tokensReceiver = asAccount(0L, 0L, tokenReceiver);
        final var customfees = List.of(
                withFixedFee(htsFixedFee),
                withFractionalFee(
                        fractionalFee.copyBuilder().netOfTransfers(true).build()));
        // fractional fee with self denomination will modify given transaction
        writableTokenStore.put(
                fungibleWithNoKyc.copyBuilder().customFees(customfees).build());
        writableTokenStore.put(nonFungibleWithNoKyc
                .copyBuilder()
                .customFees(List.of(withRoyaltyFee(
                        royaltyFee.copyBuilder().fallbackFee(htsFixedFee).build())))
                .build());
        given(storeFactory.writableStore(WritableTokenStore.class)).willReturn(writableTokenStore);
        given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(writableTokenStore);

        givenTxn();

        final var listOfOps = subject.assessCustomFees(transferContext);
        assertThat(listOfOps).hasSize(2);

        final var givenOp = listOfOps.get(0);
        final var level1Op = listOfOps.get(1);

        final var expectedLevel1TokenTransfers = Map.of(
                fungibleTokenId,
                Map.of(
                        feeCollectorId,
                        20L,
                        tokensReceiver,
                        -20L)); // 10 is for fallback royalty fee from given transaction.
        // When assessing this level, we have a fixed fee of 10 and fractional fee of 1 (self denominated)

        final var expectedGivenOpTokenTransfers = Map.of(
                fungibleTokenId,
                Map.of(
                        tokensReceiver, 1000L,
                        ownerId, -1020L,
                        feeCollectorId,
                                20L)); // fixed hts fee self denomination and fractional fees with self denomination
        // all are adjusted to input txn
        final var expectedGivenOpHbarTransfers = Map.of(hbarsReceiver, 1000L, ownerId, -1000L);

        assertThatTransferListContains(level1Op.tokenTransfers(), expectedLevel1TokenTransfers);
        assertThatTransferListContains(givenOp.tokenTransfers(), expectedGivenOpTokenTransfers);
        assertThatTransfersContains(givenOp.transfers().accountAmounts(), expectedGivenOpHbarTransfers);
    }

    @Test
    void multiLevelTransfers() {
        body = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(
                        TokenTransferList.newBuilder()
                                .token(fungibleTokenId)
                                .expectedDecimals(1000)
                                .transfers(List.of(aaWith(ownerId, -1_00), aaWith(payerId, +1_00)))
                                .build(),
                        TokenTransferList.newBuilder()
                                .token(fungibleTokenIDB)
                                .expectedDecimals(1000)
                                .transfers(List.of(aaWith(payerId, -10), aaWith(ownerId, +10)))
                                .build())
                .build();
        givenDifferentTxn(body, payerId);

        writableTokenStore.put(fungibleWithNoKyc
                .copyBuilder()
                .customFees(withFractionalFee(
                        fractionalFee.copyBuilder().netOfTransfers(true).build()))
                .build());
        given(storeFactory.writableStore(WritableTokenStore.class)).willReturn(writableTokenStore);
        given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(writableTokenStore);

        final var listOfOps = subject.assessCustomFees(transferContext);
        assertThat(listOfOps).hasSize(3);

        final var givenOp = listOfOps.get(0);
        final var level1Op = listOfOps.get(1);
        final var level2Op = listOfOps.get(2);

        final var expectedGivenOpTokenTransfers = Map.of(
                fungibleTokenId,
                Map.of(
                        ownerId, -101L, // 100 is given transfers, fractional fee self denominated
                        // is 1/100 so 1 addition change to input txn
                        payerId, 100L,
                        feeCollectorId, 1L),
                fungibleTokenIDB,
                Map.of(
                        payerId, -10L, // These are original changes, fixed custom fee is charged in next level
                        ownerId, 10L));
        final var expectedLevel1TokenTransfers = Map.of(
                fungibleTokenIDC,
                Map.of(
                        payerId, -1000L, // fixed fee is charged here from fungibleTokenIDB changes
                        feeCollectorId, 1000L));
        final var expectedLevel2TokenTransfers = Map.of(
                fungibleTokenId,
                Map.of(
                        payerId, -40L, // when fungibleTokenIDC is assessed it has fixed fee in A , so moves
                        // to this level and when assessing this level , C has fractional fee of A.
                        // Since fractional fee is self denominated, causes change in same level
                        feeCollectorId, 40L));

        assertThatTransferListContains(givenOp.tokenTransfers(), expectedGivenOpTokenTransfers);
        assertThatTransferListContains(level1Op.tokenTransfers(), expectedLevel1TokenTransfers);
        assertThatTransferListContains(level2Op.tokenTransfers(), expectedLevel2TokenTransfers);
    }

    private void givenDifferentTxn(final CryptoTransferTransactionBody body, final AccountID payerId) {
        givenStoresAndConfig(handleContext);
        givenTxn(body, payerId);
        given(handleContext.savepointStack()).willReturn(stack);

        transferContext = new TransferContextImpl(handleContext);
        ensureAliasesStep = new EnsureAliasesStep(body);
        replaceAliasesWithIDsInOp = new ReplaceAliasesWithIDsInOp();
        associateTokenRecepientsStep = new AssociateTokenRecipientsStep(body);
        final var replacedOp = getReplacedOp();
        subject = new CustomFeeAssessmentStep(replacedOp);
    }

    private void assertThatTransferListContains(
            final List<TokenTransferList> tokenTransferLists,
            final Map<TokenID, Map<AccountID, Long>> expectedTransfers) {
        for (final var tokenTransferList : tokenTransferLists) {
            final var tokenId = tokenTransferList.token();
            final var fungibleTransfers = tokenTransferList.transfers();
            if (expectedTransfers.containsKey(tokenId)) {
                assertThatTransfersContains(fungibleTransfers, expectedTransfers.get(tokenId));
            }
        }
    }

    private void assertThatTransfersContains(
            final List<AccountAmount> transfers, final Map<AccountID, Long> expectedTransfers) {
        for (final var entry : expectedTransfers.entrySet()) {
            assertThat(transfers)
                    .contains(AccountAmount.newBuilder()
                            .accountID(entry.getKey())
                            .amount(entry.getValue())
                            .build());
        }
    }

    CryptoTransferTransactionBody getReplacedOp() {
        fungibleWithNoKyc = givenValidFungibleToken(ownerId, false, false, false, false, false);
        writableTokenStore.put(fungibleWithNoKyc);
        nonFungibleWithNoKyc = givenValidNonFungibleToken(false);
        writableTokenStore.put(nonFungibleWithNoKyc);
        final var fungibleWithNoKycB = fungibleTokenB
                .copyBuilder()
                .kycKey((Key) null)
                .tokenId(fungibleTokenIDB)
                .build();
        writableTokenStore.put(fungibleWithNoKycB);

        ensureAliasesStep.doIn(transferContext);
        associateTokenRecepientsStep.doIn(transferContext);

        final var tokenRel = writableTokenRelStore.get(asAccount(0L, 0L, tokenReceiver), fungibleWithNoKyc.tokenId());
        readableTokenRelStore = TestStoreFactory.newReadableStoreWithTokenRels(
                tokenRel.copyBuilder().balance(1000).build(),
                tokenRel.copyBuilder()
                        .accountId(payerId)
                        .tokenId(fungibleTokenIDC)
                        .balance(1000)
                        .build(),
                tokenRel.copyBuilder()
                        .accountId(payerId)
                        .tokenId(fungibleTokenId)
                        .balance(1000)
                        .build(),
                tokenRel.copyBuilder()
                        .accountId(ownerId)
                        .tokenId(fungibleTokenIDB)
                        .balance(1000)
                        .build(),
                tokenRel.copyBuilder()
                        .accountId(ownerId)
                        .tokenId(fungibleTokenId)
                        .balance(2000)
                        .build(),
                tokenRel.copyBuilder()
                        .accountId(payerId)
                        .tokenId(fungibleTokenIDB)
                        .balance(1000)
                        .build(),
                tokenRel.copyBuilder()
                        .accountId(payerId)
                        .tokenId(fungibleTokenId)
                        .balance(1000)
                        .build());

        when(storeFactory.readableStore(ReadableTokenRelationStore.class)).thenReturn(readableTokenRelStore);

        return replaceAliasesWithIDsInOp.replaceAliasesWithIds(body, transferContext);
    }
}

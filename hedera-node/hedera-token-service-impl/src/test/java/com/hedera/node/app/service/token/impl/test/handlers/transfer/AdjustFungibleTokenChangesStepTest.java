// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNEXPECTED_TOKEN_DECIMALS;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.aaWith;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.aaWithAllowance;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.token.impl.handlers.transfer.AdjustFungibleTokenChangesStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.AssociateTokenRecipientsStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.EnsureAliasesStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.ReplaceAliasesWithIDsInOp;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferContextImpl;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdjustFungibleTokenChangesStepTest extends StepsBase {
    @Override
    @BeforeEach
    public void setUp() {
        super.setUp();
        refreshWritableStores();
        // since we can't change NFT owner with auto association if KYC key exists on token
        writableTokenStore.put(nonFungibleToken.copyBuilder().kycKey((Key) null).build());
        givenStoresAndConfig(handleContext);
        givenTxn();
        ensureAliasesStep = new EnsureAliasesStep(body);
        replaceAliasesWithIDsInOp = new ReplaceAliasesWithIDsInOp();
        associateTokenRecepientsStep = new AssociateTokenRecipientsStep(body);
        transferContext = new TransferContextImpl(handleContext);
        writableTokenStore.put(givenValidFungibleToken(ownerId, false, false, false, false, false));
    }

    @Test
    void doesTokenBalanceChangesWithoutAllowances() {
        final var receiver = asAccount(0L, 0L, tokenReceiver);
        given(handleContext.payer()).willReturn(spenderId);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(handleContext.savepointStack()).willReturn(stack);
        given(handleContext.dispatchMetadata()).willReturn(HandleContext.DispatchMetadata.EMPTY_METADATA);
        final var replacedOp = getReplacedOp();
        adjustFungibleTokenChangesStep = new AdjustFungibleTokenChangesStep(replacedOp.tokenTransfers(), payerId);

        final var senderAccountBefore = writableAccountStore.getAliasedAccountById(ownerId);
        final var receiverAccountBefore = writableAccountStore.getAliasedAccountById(receiver);
        final var senderRelBefore = writableTokenRelStore.get(ownerId, fungibleTokenId);
        final var receiverRelBefore = writableTokenRelStore.get(receiver, fungibleTokenId);
        writableTokenRelStore.put(receiverRelBefore
                .copyBuilder()
                .balance(0L)
                .kycGranted(true)
                .accountId(tokenReceiverId)
                .build());

        assertThat(senderAccountBefore.numberPositiveBalances()).isEqualTo(2);
        assertThat(receiverAccountBefore.numberPositiveBalances()).isEqualTo(2);
        assertThat(senderRelBefore.balance()).isEqualTo(1000L);

        adjustFungibleTokenChangesStep.doIn(transferContext);

        // see numPositiveBalances and numOwnedNfts change
        final var senderAccountAfter = writableAccountStore.getAliasedAccountById(ownerId);
        final var receiverAccountAfter = writableAccountStore.getAliasedAccountById(receiver);
        final var senderRelAfter = writableTokenRelStore.get(ownerId, fungibleTokenId);
        final var receiverRelAfter = writableTokenRelStore.get(receiver, fungibleTokenId);

        // numPositiveBalancesChanged since all 1000 token Rel balance is transferred and new balance is 0
        assertThat(senderAccountAfter.numberPositiveBalances())
                .isEqualTo(senderAccountBefore.numberPositiveBalances() - 1);
        assertThat(receiverAccountAfter.numberPositiveBalances())
                .isEqualTo(receiverAccountBefore.numberPositiveBalances() + 1);
        assertThat(senderRelAfter.balance()).isEqualTo(senderRelBefore.balance() - 1000);
        assertThat(receiverRelAfter.balance()).isEqualTo(1000);
    }

    @Test
    void doesTokenBalanceChangesWithAllowances() {
        givenTxnWithAllowances();
        ensureAliasesStep = new EnsureAliasesStep(body);
        replaceAliasesWithIDsInOp = new ReplaceAliasesWithIDsInOp();
        associateTokenRecepientsStep = new AssociateTokenRecipientsStep(body);
        given(handleContext.body()).willReturn(txn);
        given(handleContext.savepointStack()).willReturn(stack);
        given(handleContext.dispatchMetadata()).willReturn(HandleContext.DispatchMetadata.EMPTY_METADATA);

        final var receiver = asAccount(0L, 0L, tokenReceiver);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        final var replacedOp = getReplacedOp();
        // payer is spender for allowances
        adjustFungibleTokenChangesStep = new AdjustFungibleTokenChangesStep(replacedOp.tokenTransfers(), spenderId);

        final var senderAccountBefore = writableAccountStore.getAliasedAccountById(ownerId);
        final var receiverAccountBefore = writableAccountStore.getAliasedAccountById(receiver);
        final var senderRelBefore = writableTokenRelStore.get(ownerId, fungibleTokenId);
        final var receiverRelBefore = writableTokenRelStore.get(receiver, fungibleTokenId);
        writableTokenRelStore.put(receiverRelBefore
                .copyBuilder()
                .kycGranted(true)
                .accountId(tokenReceiverId)
                .build());

        assertThat(senderAccountBefore.numberPositiveBalances()).isEqualTo(2);
        assertThat(receiverAccountBefore.numberPositiveBalances()).isEqualTo(2);
        assertThat(senderRelBefore.balance()).isEqualTo(1000L);
        // There is an association happening during the transfer for auto creation
        assertThat(receiverRelBefore.balance()).isEqualTo(0);

        assertThat(senderAccountBefore.tokenAllowances()).hasSize(1);

        adjustFungibleTokenChangesStep.doIn(transferContext);

        // see numPositiveBalances and numOwnedNfts change
        final var senderAccountAfter = writableAccountStore.getAliasedAccountById(ownerId);
        final var receiverAccountAfter = writableAccountStore.getAliasedAccountById(receiver);
        final var senderRelAfter = writableTokenRelStore.get(ownerId, fungibleTokenId);
        final var receiverRelAfter = writableTokenRelStore.get(receiver, fungibleTokenId);

        // numPositiveBalancesChanged since all 1000 token Rel balance is transferred and new balance is 0
        assertThat(senderAccountAfter.numberPositiveBalances())
                .isEqualTo(senderAccountBefore.numberPositiveBalances() - 1);
        assertThat(receiverAccountAfter.numberPositiveBalances())
                .isEqualTo(receiverAccountBefore.numberPositiveBalances() + 1);
        assertThat(senderRelAfter.balance()).isEqualTo(senderRelBefore.balance() - 1000);
        assertThat(receiverRelAfter.balance()).isEqualTo(receiverRelBefore.balance() + 1000);

        // Total allowance becomes zero after 1000 transfer, so allowance is removed from map
        assertThat(senderAccountAfter.tokenAllowances()).isEmpty();
    }

    @Test
    void failsWhenExpectedDecimalsDiffer() {
        body = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(aaWithAllowance(ownerId, -1_000), aaWith(unknownAliasedId, +1_000))
                        .build())
                .tokenTransfers(TokenTransferList.newBuilder()
                        .expectedDecimals(20)
                        .token(fungibleTokenId)
                        .transfers(List.of(aaWith(ownerId, -1_000), aaWith(unknownAliasedId1, +1_000)))
                        .build())
                .build();
        givenTxn(body, payerId);
        ensureAliasesStep = new EnsureAliasesStep(body);
        replaceAliasesWithIDsInOp = new ReplaceAliasesWithIDsInOp();
        associateTokenRecepientsStep = new AssociateTokenRecipientsStep(body);
        given(handleContext.body()).willReturn(txn);
        given(handleContext.payer()).willReturn(spenderId);
        given(handleContext.savepointStack()).willReturn(stack);
        given(handleContext.dispatchMetadata()).willReturn(HandleContext.DispatchMetadata.EMPTY_METADATA);

        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        final var replacedOp = getReplacedOp();
        // payer is spender for allowances
        adjustFungibleTokenChangesStep = new AdjustFungibleTokenChangesStep(replacedOp.tokenTransfers(), payerId);

        assertThatThrownBy(() -> adjustFungibleTokenChangesStep.doIn(transferContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(UNEXPECTED_TOKEN_DECIMALS));
    }

    @Test
    void allowanceWithGreaterThanAllowedAllowanceFails() {
        body = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(aaWith(ownerId, -1_000), aaWith(unknownAliasedId, +1_000))
                        .build())
                .tokenTransfers(TokenTransferList.newBuilder()
                        .expectedDecimals(1000)
                        .token(fungibleTokenId)
                        .transfers(List.of(aaWithAllowance(ownerId, -1_001), aaWith(unknownAliasedId1, +1_001)))
                        .build())
                .build();
        givenTxn(body, spenderId);
        ensureAliasesStep = new EnsureAliasesStep(body);
        replaceAliasesWithIDsInOp = new ReplaceAliasesWithIDsInOp();
        associateTokenRecepientsStep = new AssociateTokenRecipientsStep(body);
        given(handleContext.body()).willReturn(txn);
        given(handleContext.savepointStack()).willReturn(stack);
        given(handleContext.dispatchMetadata()).willReturn(HandleContext.DispatchMetadata.EMPTY_METADATA);

        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        final var replacedOp = getReplacedOp();
        adjustFungibleTokenChangesStep = new AdjustFungibleTokenChangesStep(replacedOp.tokenTransfers(), spenderId);
        final var tokenRel = writableTokenRelStore.get(tokenReceiverId, fungibleTokenId);
        writableTokenRelStore.put(tokenRel.copyBuilder()
                .kycGranted(true)
                .accountId(tokenReceiverId)
                .build());

        assertThatThrownBy(() -> adjustFungibleTokenChangesStep.doIn(transferContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ResponseCodeEnum.AMOUNT_EXCEEDS_ALLOWANCE));
    }

    @Test
    void transferGreaterThanTokenRelBalanceFails() {
        body = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(aaWith(ownerId, -1_000), aaWith(unknownAliasedId, +1_000))
                        .build())
                .tokenTransfers(TokenTransferList.newBuilder()
                        .token(fungibleTokenId)
                        .transfers(List.of(aaWith(ownerId, -1_0000), aaWith(unknownAliasedId1, +1_0000)))
                        .build())
                .build();
        givenTxn(body, spenderId);
        ensureAliasesStep = new EnsureAliasesStep(body);
        replaceAliasesWithIDsInOp = new ReplaceAliasesWithIDsInOp();
        associateTokenRecepientsStep = new AssociateTokenRecipientsStep(body);
        given(handleContext.body()).willReturn(txn);
        given(handleContext.payer()).willReturn(spenderId);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(handleContext.savepointStack()).willReturn(stack);
        given(handleContext.dispatchMetadata()).willReturn(HandleContext.DispatchMetadata.EMPTY_METADATA);

        final var replacedOp = getReplacedOp();
        adjustFungibleTokenChangesStep = new AdjustFungibleTokenChangesStep(replacedOp.tokenTransfers(), spenderId);
        final var tokenRel = writableTokenRelStore.get(tokenReceiverId, fungibleTokenId);
        writableTokenRelStore.put(tokenRel.copyBuilder()
                .kycGranted(true)
                .accountId(tokenReceiverId)
                .build());

        assertThatThrownBy(() -> adjustFungibleTokenChangesStep.doIn(transferContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE));
    }

    CryptoTransferTransactionBody getReplacedOp() {
        givenAutoCreationDispatchEffects();
        ensureAliasesStep.doIn(transferContext);
        associateTokenRecepientsStep.doIn(transferContext);
        return replaceAliasesWithIDsInOp.replaceAliasesWithIds(body, transferContext);
    }
}

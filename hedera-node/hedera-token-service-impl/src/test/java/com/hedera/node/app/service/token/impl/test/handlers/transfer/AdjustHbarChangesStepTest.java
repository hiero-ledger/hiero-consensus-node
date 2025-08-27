// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.aaWith;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.aaWithAllowance;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.nftTransferWithAllowance;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.AccountCryptoAllowance;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.token.impl.handlers.transfer.AdjustHbarChangesStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.AssociateTokenRecipientsStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.EnsureAliasesStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.ReplaceAliasesWithIDsInOp;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferContextImpl;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdjustHbarChangesStepTest extends StepsBase {
    @BeforeEach
    public void setUp() {
        super.setUp();
        refreshWritableStores();
        // since we can't change NFT owner with auto association if KYC key exists on token
        writableTokenStore.put(nonFungibleToken.copyBuilder().kycKey((Key) null).build());
        // balances of two accounts
        writableAccountStore.put(
                hbarReceiverAccount.copyBuilder().tinybarBalance(10000L).build());
        writableAccountStore.put(
                tokenReceiverAccount.copyBuilder().tinybarBalance(10000L).build());
        givenStoresAndConfig(handleContext);
        givenTxn();
        ensureAliasesStep = new EnsureAliasesStep(body);
        replaceAliasesWithIDsInOp = new ReplaceAliasesWithIDsInOp();
        associateTokenRecepientsStep = new AssociateTokenRecipientsStep(body);
        transferContext = new TransferContextImpl(handleContext);
        writableTokenStore.put(givenValidFungibleToken(ownerId, false, false, false, false, false));
    }

    @Test
    void doesHbarBalanceChangesWithoutAllowances() {
        final var receiver = asAccount(0L, 0L, hbarReceiver);
        given(handleContext.payer()).willReturn(spenderId);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(handleContext.savepointStack()).willReturn(stack);
        given(handleContext.dispatchMetadata()).willReturn(HandleContext.DispatchMetadata.EMPTY_METADATA);
        final var replacedOp = getReplacedOp();
        adjustHbarChangesStep = new AdjustHbarChangesStep(replacedOp, payerId);

        final var senderAccount = writableAccountStore.getAliasedAccountById(ownerId);
        final var receiverAccount = writableAccountStore.getAliasedAccountById(receiver);

        assertThat(senderAccount.tinybarBalance()).isEqualTo(10000L);
        assertThat(receiverAccount.tinybarBalance()).isEqualTo(10000L);

        adjustHbarChangesStep.doIn(transferContext);

        // see numPositiveBalances and numOwnedNfts change
        final var senderAccountAfter = writableAccountStore.getAliasedAccountById(ownerId);
        final var receiverAccountAfter = writableAccountStore.getAliasedAccountById(receiver);

        assertThat(senderAccountAfter.tinybarBalance()).isEqualTo(senderAccount.tinybarBalance() - 1000);
        assertThat(receiverAccountAfter.tinybarBalance()).isEqualTo(receiverAccount.tinybarBalance() + 1000);
    }

    @Test
    void doesHbarBalanceChangesWithAllowances() {
        givenTxnWithAllowances();
        ensureAliasesStep = new EnsureAliasesStep(body);
        replaceAliasesWithIDsInOp = new ReplaceAliasesWithIDsInOp();
        associateTokenRecepientsStep = new AssociateTokenRecipientsStep(body);
        given(handleContext.body()).willReturn(txn);
        given(handleContext.savepointStack()).willReturn(stack);
        given(handleContext.dispatchMetadata()).willReturn(HandleContext.DispatchMetadata.EMPTY_METADATA);

        final var receiver = asAccount(0L, 0L, hbarReceiver);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        final var replacedOp = getReplacedOp();
        adjustHbarChangesStep =
                new AdjustHbarChangesStep(replacedOp, txn.transactionIDOrThrow().accountIDOrThrow());

        final var senderAccount = writableAccountStore.getAliasedAccountById(ownerId);
        final var receiverAccount = writableAccountStore.getAliasedAccountById(receiver);

        assertThat(senderAccount.tinybarBalance()).isEqualTo(10000L);
        assertThat(receiverAccount.tinybarBalance()).isEqualTo(10000L);

        // Total allowance becomes zero after 1000 transfer, so allowance is removed from map
        assertThat(senderAccount.cryptoAllowances()).hasSize(1);

        adjustHbarChangesStep.doIn(transferContext);

        // see numPositiveBalances and numOwnedNfts change
        final var senderAccountAfter = writableAccountStore.getAliasedAccountById(ownerId);
        final var receiverAccountAfter = writableAccountStore.getAliasedAccountById(receiver);

        assertThat(senderAccountAfter.tinybarBalance()).isEqualTo(senderAccount.tinybarBalance() - 1000);
        assertThat(receiverAccountAfter.tinybarBalance()).isEqualTo(receiverAccount.tinybarBalance() + 1000);
        // Total allowance becomes zero after 1000 transfer, so allowance is removed from map
        assertThat(senderAccountAfter.cryptoAllowances()).isEmpty();
    }

    @Test
    void doesHbarBalanceChangesWithLeftoverAllowances() {
        // setups transaction with allowance of 1000 and transfer of 900
        // resulting in leftover allowance of 100
        final var amount = 900;
        final var leftover = 100;
        body = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(aaWithAllowance(ownerId, -amount), aaWith(unknownAliasedId, amount))
                        .build())
                .tokenTransfers(
                        TokenTransferList.newBuilder()
                                .expectedDecimals(1000)
                                .token(fungibleTokenId)
                                .transfers(
                                        List.of(aaWithAllowance(ownerId, -1_0000), aaWith(unknownAliasedId1, +1_0000)))
                                .build(),
                        TokenTransferList.newBuilder()
                                .token(nonFungibleTokenId)
                                .nftTransfers(nftTransferWithAllowance(ownerId, unknownAliasedId1, 1))
                                .build())
                .build();
        givenTxn(body, spenderId);

        ensureAliasesStep = new EnsureAliasesStep(body);
        replaceAliasesWithIDsInOp = new ReplaceAliasesWithIDsInOp();
        associateTokenRecepientsStep = new AssociateTokenRecipientsStep(body);
        given(handleContext.body()).willReturn(txn);
        given(handleContext.savepointStack()).willReturn(stack);
        given(handleContext.dispatchMetadata()).willReturn(HandleContext.DispatchMetadata.EMPTY_METADATA);

        final var receiver = asAccount(0L, 0L, hbarReceiver);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        final var replacedOp = getReplacedOp();
        adjustHbarChangesStep =
                new AdjustHbarChangesStep(replacedOp, txn.transactionIDOrThrow().accountIDOrThrow());

        final var senderAccount = writableAccountStore.getAliasedAccountById(ownerId);
        final var receiverAccount = writableAccountStore.getAliasedAccountById(receiver);

        assertThat(senderAccount.tinybarBalance()).isEqualTo(10000L);
        assertThat(receiverAccount.tinybarBalance()).isEqualTo(10000L);

        assertThat(senderAccount.cryptoAllowances()).hasSize(1);

        adjustHbarChangesStep.doIn(transferContext);

        // see numPositiveBalances and numOwnedNfts change
        final var senderAccountAfter = writableAccountStore.getAliasedAccountById(ownerId);
        final var receiverAccountAfter = writableAccountStore.getAliasedAccountById(receiver);

        assertThat(senderAccountAfter.tinybarBalance()).isEqualTo(senderAccount.tinybarBalance() - amount);
        assertThat(receiverAccountAfter.tinybarBalance()).isEqualTo(receiverAccount.tinybarBalance() + amount);
        // Total allowance has leftover, so allowance is not removed from map
        assertThat(senderAccountAfter.cryptoAllowances()).hasSize(1);
        assertThat(senderAccountAfter.cryptoAllowances().get(0).amount()).isEqualTo(leftover);
    }

    @Test
    void allowanceWithGreaterThanAllowedAllowanceFails() {
        body = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(aaWithAllowance(ownerId, -1_0000), aaWith(unknownAliasedId, +1_000))
                        .build())
                .tokenTransfers()
                .build();
        givenTxn(body, spenderId);
        ensureAliasesStep = new EnsureAliasesStep(body);
        replaceAliasesWithIDsInOp = new ReplaceAliasesWithIDsInOp();
        associateTokenRecepientsStep = new AssociateTokenRecipientsStep(body);
        given(handleContext.body()).willReturn(txn);
        given(handleContext.dispatchMetadata()).willReturn(HandleContext.DispatchMetadata.EMPTY_METADATA);

        final var receiver = asAccount(0L, 0L, hbarReceiver);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        final var replacedOp = getReplacedOp();
        adjustHbarChangesStep =
                new AdjustHbarChangesStep(replacedOp, txn.transactionIDOrThrow().accountIDOrThrow());

        final var senderAccount = writableAccountStore.getAliasedAccountById(ownerId);
        final var receiverAccount = writableAccountStore.getAliasedAccountById(receiver);

        assertThat(senderAccount.tinybarBalance()).isEqualTo(10000L);
        assertThat(receiverAccount.tinybarBalance()).isEqualTo(10000L);

        assertThatThrownBy(() -> adjustHbarChangesStep.doIn(transferContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ResponseCodeEnum.AMOUNT_EXCEEDS_ALLOWANCE));
    }

    @Test
    void transferGreaterThanBalanceFails() {
        body = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(aaWith(ownerId, -1_0001), aaWith(unknownAliasedId, +1_000))
                        .build())
                .tokenTransfers()
                .build();
        givenTxn(body, spenderId);
        ensureAliasesStep = new EnsureAliasesStep(body);
        replaceAliasesWithIDsInOp = new ReplaceAliasesWithIDsInOp();
        associateTokenRecepientsStep = new AssociateTokenRecipientsStep(body);
        given(handleContext.body()).willReturn(txn);
        given(handleContext.dispatchMetadata()).willReturn(HandleContext.DispatchMetadata.EMPTY_METADATA);

        final var receiver = asAccount(0L, 0L, hbarReceiver);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        final var replacedOp = getReplacedOp();
        adjustHbarChangesStep =
                new AdjustHbarChangesStep(replacedOp, txn.transactionIDOrThrow().accountIDOrThrow());

        final var senderAccount = writableAccountStore.getAliasedAccountById(ownerId);
        final var receiverAccount = writableAccountStore.getAliasedAccountById(receiver);

        assertThat(senderAccount.tinybarBalance()).isEqualTo(10000L);
        assertThat(receiverAccount.tinybarBalance()).isEqualTo(10000L);

        assertThatThrownBy(() -> adjustHbarChangesStep.doIn(transferContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE));
    }

    final long getAllowanceAmount(List<AccountCryptoAllowance> allowances, AccountID spender) {
        for (final var entry : allowances) {
            if (entry.spenderId().equals(spender)) {
                return entry.amount();
            }
        }
        return 0;
    }

    CryptoTransferTransactionBody getReplacedOp() {
        givenAutoCreationDispatchEffects();
        ensureAliasesStep.doIn(transferContext);
        associateTokenRecepientsStep.doIn(transferContext);
        return replaceAliasesWithIDsInOp.replaceAliasesWithIds(body, transferContext);
    }
}

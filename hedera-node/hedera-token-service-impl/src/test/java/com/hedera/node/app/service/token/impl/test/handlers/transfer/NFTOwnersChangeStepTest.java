// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SPENDER_DOES_NOT_HAVE_ALLOWANCE;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.aaWith;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.nftTransferWithAllowance;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.token.impl.handlers.transfer.AssociateTokenRecipientsStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.EnsureAliasesStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.NFTOwnersChangeStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.ReplaceAliasesWithIDsInOp;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferContextImpl;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NFTOwnersChangeStepTest extends StepsBase {

    @BeforeEach
    public void setUp() {
        super.setUp();
        refreshWritableStores();
        // since we can't change NFT owner with auto association if KYC key exists on token
        writableTokenStore.put(nonFungibleToken.copyBuilder().kycKey((Key) null).build());
        writableNftStore.put(writableNftStore
                .get(nftIdSl1)
                .copyBuilder()
                .spenderId(spenderId)
                .build());
        writableNftStore.put(writableNftStore
                .get(nftIdSl2)
                .copyBuilder()
                .spenderId(spenderId)
                .build());
        writableAccountStore.put(writableAccountStore
                .get(tokenReceiverId)
                .copyBuilder()
                .headNftId((NftID) null)
                .headNftSerialNumber(0L)
                .build());

        givenStoresAndConfig(handleContext);
        givenTxn();
        ensureAliasesStep = new EnsureAliasesStep(body);
        replaceAliasesWithIDsInOp = new ReplaceAliasesWithIDsInOp();
        associateTokenRecepientsStep = new AssociateTokenRecipientsStep(body);
        transferContext = new TransferContextImpl(handleContext);
        writableTokenStore.put(givenValidFungibleToken(ownerId, false, false, false, false, false));
    }

    @Test
    void changesNftOwners() {
        final var receiver = asAccount(0L, 0L, tokenReceiver);
        given(handleContext.payer()).willReturn(spenderId);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(handleContext.savepointStack()).willReturn(stack);
        given(handleContext.dispatchMetadata()).willReturn(HandleContext.DispatchMetadata.EMPTY_METADATA);
        final var replacedOp = getReplacedOp();
        changeNFTOwnersStep = new NFTOwnersChangeStep(replacedOp.tokenTransfers(), payerId);
        final var nft = writableNftStore.get(nftIdSl1);
        assertThat(nft.ownerId()).isEqualTo(ownerId);

        // check pointer updates
        assertThat(nft.hasOwnerPreviousNftId()).isFalse();
        assertThat(nft.hasOwnerNextNftId()).isTrue();
        assertThat(nft.ownerNextNftId()).isEqualTo(nftIdSl2);

        final var nextNftBefore = writableNftStore.get(nftIdSl2);
        assertThat(nextNftBefore.hasOwnerPreviousNftId()).isTrue();
        assertThat(nextNftBefore.hasOwnerNextNftId()).isFalse();

        final var senderAccount = writableAccountStore.getAliasedAccountById(ownerId);
        final var receiverAccount = writableAccountStore.getAliasedAccountById(receiver);

        final var numNftsOwnedBySender = senderAccount.numberOwnedNfts();
        assertThat(numNftsOwnedBySender).isEqualTo(2);
        final var numNftsOwnedByReceiver = receiverAccount.numberOwnedNfts();
        assertThat(numNftsOwnedByReceiver).isEqualTo(2);
        final var numPositiveBalancesSender = senderAccount.numberPositiveBalances();
        assertThat(numPositiveBalancesSender).isEqualTo(2);
        final var numPositiveBalancesReceiver = receiverAccount.numberPositiveBalances();
        assertThat(numPositiveBalancesReceiver).isEqualTo(2);

        final var senderTokenRelBalance =
                writableTokenRelStore.get(ownerId, nonFungibleTokenId).balance();
        assertThat(senderTokenRelBalance).isEqualTo(1);
        final var receiverTokenRelBalance =
                writableTokenRelStore.get(receiver, nonFungibleTokenId).balance();
        assertThat(receiverTokenRelBalance).isEqualTo(0);

        changeNFTOwnersStep.doIn(transferContext);

        // owner Id on NFT should change to receiver
        final var nftChanged = writableNftStore.get(nftIdSl1);
        assertThat(nftChanged.ownerId()).isEqualTo(receiver);

        // check pointer updates
        // Because receiver has no Nfts before
        assertThat(nftChanged.hasOwnerPreviousNftId()).isFalse();
        assertThat(nftChanged.hasOwnerNextNftId()).isFalse();

        final var nextNft = writableNftStore.get(nftIdSl2);
        assertThat(nextNft.hasOwnerPreviousNftId()).isFalse();
        assertThat(nextNft.hasOwnerNextNftId()).isFalse();

        // see numPositiveBalances and numOwnedNfts change
        final var senderAccountAfter = writableAccountStore.getAliasedAccountById(ownerId);
        final var receiverAccountAfter = writableAccountStore.getAliasedAccountById(receiver);

        final var numNftsOwnedBySenderAfter = senderAccountAfter.numberOwnedNfts();
        assertThat(numNftsOwnedBySenderAfter).isEqualTo(numNftsOwnedBySender - 1);
        final var numNftsOwnedByReceiverAfter = receiverAccountAfter.numberOwnedNfts();
        assertThat(numNftsOwnedByReceiverAfter).isEqualTo(numNftsOwnedByReceiver + 1);
        final var numPositiveBalancesSenderAfter = senderAccountAfter.numberPositiveBalances();
        assertThat(numPositiveBalancesSenderAfter).isEqualTo(numPositiveBalancesSender - 1);
        final var numPositiveBalancesReceiverAfter = receiverAccountAfter.numberPositiveBalances();
        assertThat(numPositiveBalancesReceiverAfter).isEqualTo(numPositiveBalancesReceiver + 1);

        // see token relation balances for sender and receiver change
        final var senderTokenRelBalanceAfter = writableTokenRelStore.get(ownerId, nonFungibleTokenId);
        final var receiverTokenRelBalanceAfter = writableTokenRelStore.get(receiver, nonFungibleTokenId);
        assertThat(senderTokenRelBalanceAfter.balance()).isEqualTo(senderTokenRelBalance - 1);
        assertThat(receiverTokenRelBalanceAfter.balance()).isEqualTo(receiverTokenRelBalance + 1);

        assertThat(senderAccountAfter.headNftId()).isEqualTo(nftIdSl2);
        assertThat(receiverAccountAfter.headNftId()).isEqualTo(nftIdSl1);
    }

    @Test
    void changesNftOwnersWithAllowance() {
        body = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(aaWith(ownerId, -1_000), aaWith(unknownAliasedId, +1_000))
                        .build())
                .tokenTransfers(TokenTransferList.newBuilder()
                        .token(nonFungibleTokenId)
                        .expectedDecimals(1000)
                        .nftTransfers(nftTransferWithAllowance(ownerId, unknownAliasedId1, 1))
                        .build())
                .build();
        givenTxn(body, spenderId);
        given(handleContext.savepointStack()).willReturn(stack);
        given(handleContext.dispatchMetadata()).willReturn(HandleContext.DispatchMetadata.EMPTY_METADATA);
        ensureAliasesStep = new EnsureAliasesStep(body);
        replaceAliasesWithIDsInOp = new ReplaceAliasesWithIDsInOp();
        associateTokenRecepientsStep = new AssociateTokenRecipientsStep(body);
        transferContext = new TransferContextImpl(handleContext);

        final var receiver = asAccount(0L, 0L, tokenReceiver);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        final var replacedOp = getReplacedOp();
        changeNFTOwnersStep = new NFTOwnersChangeStep(replacedOp.tokenTransfers(), spenderId);

        final var nft = writableNftStore.get(nftIdSl1);
        assertThat(nft.ownerId()).isEqualTo(ownerId);

        final var senderAccount = writableAccountStore.getAliasedAccountById(ownerId);
        final var receiverAccount = writableAccountStore.getAliasedAccountById(receiver);

        final var numNftsOwnedBySender = senderAccount.numberOwnedNfts();
        assertThat(numNftsOwnedBySender).isEqualTo(2);
        final var numNftsOwnedByReceiver = receiverAccount.numberOwnedNfts();
        assertThat(numNftsOwnedByReceiver).isEqualTo(2);
        final var numPositiveBalancesSender = senderAccount.numberPositiveBalances();
        assertThat(numPositiveBalancesSender).isEqualTo(2);
        final var numPositiveBalancesReceiver = receiverAccount.numberPositiveBalances();
        assertThat(numPositiveBalancesReceiver).isEqualTo(2);

        final var senderTokenRelBalance =
                writableTokenRelStore.get(ownerId, nonFungibleTokenId).balance();
        assertThat(senderTokenRelBalance).isEqualTo(1);
        final var receiverTokenRelBalance =
                writableTokenRelStore.get(receiver, nonFungibleTokenId).balance();
        // association already happened in association step
        assertThat(receiverTokenRelBalance).isEqualTo(0);

        changeNFTOwnersStep.doIn(transferContext);

        // owner Id on NFT should change to receiver
        final var nftChanged = writableNftStore.get(nftIdSl1);
        assertThat(nftChanged.ownerId()).isEqualTo(receiver);

        // see numPositiveBalances and numOwnedNfts change
        final var senderAccountAfter = writableAccountStore.getAliasedAccountById(ownerId);
        final var receiverAccountAfter = writableAccountStore.getAliasedAccountById(receiver);

        final var numNftsOwnedBySenderAfter = senderAccountAfter.numberOwnedNfts();
        assertThat(numNftsOwnedBySenderAfter).isEqualTo(numNftsOwnedBySender - 1);
        final var numNftsOwnedByReceiverAfter = receiverAccountAfter.numberOwnedNfts();
        assertThat(numNftsOwnedByReceiverAfter).isEqualTo(numNftsOwnedByReceiver + 1);
        final var numPositiveBalancesSenderAfter = senderAccountAfter.numberPositiveBalances();
        assertThat(numPositiveBalancesSenderAfter).isEqualTo(numPositiveBalancesSender - 1);
        final var numPositiveBalancesReceiverAfter = receiverAccountAfter.numberPositiveBalances();
        assertThat(numPositiveBalancesReceiverAfter).isEqualTo(numPositiveBalancesReceiver + 1);

        // see token relation balances for sender and receiver change
        final var senderTokenRelBalanceAfter = writableTokenRelStore.get(ownerId, nonFungibleTokenId);
        final var receiverTokenRelBalanceAfter = writableTokenRelStore.get(receiver, nonFungibleTokenId);
        assertThat(senderTokenRelBalanceAfter.balance()).isEqualTo(senderTokenRelBalance - 1);
        assertThat(receiverTokenRelBalanceAfter.balance()).isEqualTo(receiverTokenRelBalance + 1);
    }

    @Test
    void failsWhenSpenderNotSameAsPayer() {
        body = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(aaWith(ownerId, -1_000), aaWith(unknownAliasedId, +1_000))
                        .build())
                .tokenTransfers(TokenTransferList.newBuilder()
                        .token(nonFungibleTokenId)
                        .nftTransfers(nftTransferWithAllowance(ownerId, unknownAliasedId1, 1))
                        .build())
                .build();
        givenTxn(body, spenderId);
        given(handleContext.savepointStack()).willReturn(stack);
        given(handleContext.dispatchMetadata()).willReturn(HandleContext.DispatchMetadata.EMPTY_METADATA);
        ensureAliasesStep = new EnsureAliasesStep(body);
        replaceAliasesWithIDsInOp = new ReplaceAliasesWithIDsInOp();
        associateTokenRecepientsStep = new AssociateTokenRecipientsStep(body);
        transferContext = new TransferContextImpl(handleContext);

        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        final var replacedOp = getReplacedOp();
        changeNFTOwnersStep = new NFTOwnersChangeStep(replacedOp.tokenTransfers(), payerId);

        assertThatThrownBy(() -> changeNFTOwnersStep.doIn(transferContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(SPENDER_DOES_NOT_HAVE_ALLOWANCE));
    }

    CryptoTransferTransactionBody getReplacedOp() {
        givenAutoCreationDispatchEffects();
        ensureAliasesStep.doIn(transferContext);
        associateTokenRecepientsStep.doIn(transferContext);
        return replaceAliasesWithIDsInOp.replaceAliasesWithIds(body, transferContext);
    }
}
